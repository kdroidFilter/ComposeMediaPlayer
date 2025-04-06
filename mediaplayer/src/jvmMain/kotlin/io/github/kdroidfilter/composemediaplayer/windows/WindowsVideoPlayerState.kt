package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.WString
import com.sun.jna.ptr.*
import io.github.kdroidfilter.composemediaplayer.*
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.*
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class WindowsVideoPlayerState : PlatformVideoPlayerState {
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isInitialized by mutableStateOf(false)
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia get() = _hasMedia
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying get() = _isPlaying
    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) { _volume = value.coerceIn(0f, 1f) }
    private var _currentTime by mutableStateOf(0.0)
    private var _duration by mutableStateOf(0.0)
    private var _progress by mutableStateOf(0f)
    override var sliderPos: Float
        get() = _progress * 1000f
        set(value) {
            _progress = (value / 1000f).coerceIn(0f, 1f)
            if (!userDragging) seekTo(value)
        }
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) { _userDragging = value }
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) { _loop = value }
    override val leftLevel: Float = 0f
    override val rightLevel: Float = 0f
    private var _error: VideoPlayerError? = null
    override val error get() = _error
    override fun clearError() { _error = null; errorMessage = null }
    var _currentFrame: Bitmap? by mutableStateOf(null)
    val bitmapLock = ReentrantReadWriteLock()
    fun getLockedComposeImageBitmap(): ImageBitmap? =
        bitmapLock.read { _currentFrame?.asComposeImageBitmap() }
    override val metadata = VideoMetadata()
    override var subtitlesEnabled = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    override fun selectSubtitleTrack(track: SubtitleTrack?) {}
    override fun disableSubtitles() {}
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    private var errorMessage: String? by mutableStateOf(null)
    private var videoJob: Job? = null
    var videoWidth by mutableStateOf(0)
    var videoHeight by mutableStateOf(0)

    // Sémaphore pour contrôler les opérations critiques comme openUri et stop
    private val mediaOperationMutex = kotlinx.coroutines.sync.Mutex()

    // Channel pour la gestion des images, avec capacité fixe et stratégie DROP_OLDEST
    private val frameChannelCapacity = 5
    private var frameChannel = Channel<FrameData>(
        capacity = frameChannelCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Pools pour réutiliser les bitmaps et tableaux de bytes avec taille adaptative
    private val maxPoolCapacity = 5
    private var bitmapPool = ArrayBlockingQueue<Bitmap>(maxPoolCapacity)
    private var byteArrayPool = ArrayBlockingQueue<ByteArray>(maxPoolCapacity)

    // Taille actuelle de buffer pour les frames
    private var currentFrameBufferSize = 0

    data class FrameData(val bitmap: Bitmap, val timestamp: Double, val buffer: ByteArray) {
        override fun equals(other: Any?) =
            other is FrameData && bitmap == other.bitmap && timestamp == other.timestamp
        override fun hashCode() = 31 * bitmap.hashCode() + timestamp.hashCode()
    }

    private var isResizing by mutableStateOf(false)
        private set
    private var resizeJob: Job? = null

    init {
        try {
            val hr = player.InitMediaFoundation()
            isInitialized = hr >= 0
            if (!isInitialized) setError("Failed to initialize Media Foundation (hr=0x${hr.toString(16)})")
        } catch (e: Exception) { setError("Exception during initialization: ${e.message}") }
    }

    override fun dispose() {
        scope.launch {
            try {
                mediaOperationMutex.withLock {
                    _isPlaying = false
                    player.SetPlaybackState(false)
                    videoJob?.cancelAndJoin()
                    player.CloseMedia()
                    clearResources()
                    player.ShutdownMediaFoundation()
                }
            } catch (e: Exception) {
                println("Error during dispose: ${e.message}")
            } finally {
                isInitialized = false
                _hasMedia = false
                scope.cancel()
            }
        }
    }

    // Méthode de nettoyage complet des ressources
    private suspend fun clearResources() {
        videoJob?.cancelAndJoin()
        clearFrameChannel()
        bitmapLock.write {
            _currentFrame?.close()
            _currentFrame = null
        }
        recreatePools()
    }

    // Vide le channel en recyclant les ressources dans les pools
    private fun clearFrameChannel() {
        try {
            // Vider d'abord le channel existant
            while (true) {
                val result = frameChannel.tryReceive()
                val frameData = result.getOrNull() ?: break
                // Libérer les ressources sans les remettre dans le pool
                frameData.bitmap.close()
            }

            // Fermer et recréer le channel
            frameChannel.close()
            frameChannel = Channel(
                capacity = frameChannelCapacity,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        } catch (e: Exception) {
            println("Error clearing frame channel: ${e.message}")
        }
    }

    // Recréation des pools avec des tailles appropriées
    private fun recreatePools() {
        try {
            // Vider et fermer les pools existants
            while (bitmapPool.poll()?.let { it.close() } != null) {}
            byteArrayPool.clear()

            // Recréer les pools
            bitmapPool = ArrayBlockingQueue(maxPoolCapacity)
            byteArrayPool = ArrayBlockingQueue(maxPoolCapacity)

            // Réinitialiser la taille de buffer
            currentFrameBufferSize = 0
        } catch (e: Exception) {
            println("Error recreating pools: ${e.message}")
        }
    }

    private suspend fun releaseResources() {
        try {
            // Arrêter les traitements en cours
            videoJob?.cancelAndJoin()

            // Vider le channel
            clearFrameChannel()

            // Nettoyer le bitmap actuel
            bitmapLock.write {
                _currentFrame?.close()
                _currentFrame = null
            }

            // Recréer les pools pour éviter les fuites
            recreatePools()
        } catch (e: Exception) {
            println("Error releasing resources: ${e.message}")
        }
    }

    override fun openUri(uri: String) {
        if (!isInitialized) {
            setError("Player is not initialized.")
            return
        }

        scope.launch {
            // Utilisation du mutex pour éviter les accès concurrents pendant les opérations sur le média
            mediaOperationMutex.withLock {
                try {
                    isLoading = true

                    // S'assurer que toutes les ressources sont proprement libérées
                    val wasPlaying = _isPlaying
                    if (wasPlaying) {
                        player.SetPlaybackState(false)
                        _isPlaying = false
                        delay(50) // Petit délai pour s'assurer que la lecture est bien arrêtée
                    }

                    // Annuler les coroutines de traitement vidéo avant de fermer le média
                    videoJob?.cancelAndJoin()

                    // Nettoyer complètement les ressources
                    releaseResources()

                    // Fermer le média actuel
                    player.CloseMedia()

                    // Réinitialiser les variables d'état
                    _currentTime = 0.0
                    _progress = 0f
                    _duration = 0.0
                    _hasMedia = false

                    if (!uri.startsWith("http", ignoreCase = true) && !File(uri).exists()) {
                        setError("File not found: $uri")
                        return@withLock
                    }

                    // Ouvrir le nouveau média
                    val hrOpen = player.OpenMedia(WString(uri))
                    if (hrOpen < 0) {
                        setError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        return@withLock
                    }

                    _hasMedia = true

                    // Obtenir les dimensions de la vidéo
                    val wRef = IntByReference()
                    val hRef = IntByReference()
                    player.GetVideoSize(wRef, hRef)

                    // Vérifier simplement que les valeurs sont positives
                    if (wRef.value > 0 && hRef.value > 0) {
                        videoWidth = wRef.value
                        videoHeight = hRef.value
                    } else {
                        videoWidth = 1280
                        videoHeight = 720
                    }

                    // Calculer la taille de buffer nécessaire pour la nouvelle résolution
                    currentFrameBufferSize = videoWidth * videoHeight * 4

                    // Obtenir la durée du média
                    val durationRef = LongByReference()
                    val hrDuration = player.GetMediaDuration(durationRef)
                    if (hrDuration < 0) {
                        setError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
                        return@withLock
                    }
                    _duration = durationRef.value / 10000000.0

                    // Démarrer les nouveaux jobs de traitement vidéo - assurer qu'ils sont bien démarrés avant de jouer
                    videoJob = scope.launch {
                        launch { produceFrames() }
                        launch { consumeFrames() }
                    }

                    // Donner un peu de temps aux jobs pour démarrer
                    delay(100)

                    // Démarrer la lecture si nécessaire
                    play()
                } catch (e: Exception) {
                    setError("Error opening media: ${e.message}")
                    _hasMedia = false
                } finally {
                    if (!_hasMedia) isLoading = false
                }
            }
        }
    }

    // --- Fonctions d'attente réactive pour la synchronisation ---

    // Attend que l'état de lecture corresponde à la valeur attendue
    private suspend inline fun awaitPlaybackState(expected: Boolean) {
        snapshotFlow { _isPlaying }
            .filter { it == expected }
            .first()
    }

    // Attend la fin du redimensionnement
    private suspend inline fun awaitNotResizing() {
        snapshotFlow { isResizing }
            .filter { !it }
            .first()
    }

    // --- Production et consommation des frames ---

    private suspend inline fun produceFrames() {
        while (scope.isActive && _hasMedia) {
            if (player.IsEOF()) {
                if (loop) {
                    try {
                        player.SeekMedia(0)
                        _currentTime = 0.0
                        _progress = 0f
                        play()
                    } catch (e: Exception) {
                        setError("Error during SeekMedia for looping: ${e.message}")
                    }
                } else {
                    pause()
                    break
                }
            }

            if (!_isPlaying) {
                // Attendre que la lecture reprenne plutôt que d'utiliser un délai fixe
                try {
                    awaitPlaybackState(true)
                } catch (e: CancellationException) {
                    break // Sortir proprement si la coroutine est annulée
                }
                continue
            }

            try {
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val readResult = player.ReadVideoFrame(ptrRef, sizeRef)

                if (readResult < 0 || ptrRef.value == null || sizeRef.value <= 0) {
                    yield()
                    continue
                }

                // Vérifier que la taille du buffer est cohérente
                val requiredSize = sizeRef.value.coerceAtLeast(currentFrameBufferSize)

                // Obtenir un buffer de la taille appropriée
                val frameBuffer = byteArrayPool.poll()?.takeIf { it.size >= requiredSize }
                    ?: ByteArray(requiredSize)

                // Copier les données de la frame
                ptrRef.value.getByteBuffer(0, sizeRef.value.toLong()).get(frameBuffer, 0, sizeRef.value)
                player.UnlockVideoFrame()

                // Obtenir ou créer un bitmap
                val frameBitmap = bitmapPool.poll() ?: Bitmap().apply {
                    allocPixels(ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
                }

                // Configurer le bitmap avec les données de la frame
                try {
                    frameBitmap.installPixels(
                        ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                        frameBuffer, videoWidth * 4
                    )

                    // Obtenir le timestamp de la frame
                    val posRef = LongByReference()
                    val frameTime = if (player.GetMediaPosition(posRef) >= 0) posRef.value / 10000000.0 else 0.0

                    // Envoyer la frame au channel
                    val frameData = FrameData(frameBitmap, frameTime, frameBuffer)
                    val sendResult = frameChannel.trySend(frameData)

                    // Si l'envoi a échoué, recycler les ressources
                    if (!sendResult.isSuccess) {
                        bitmapPool.offer(frameBitmap)
                        byteArrayPool.offer(frameBuffer)
                    }
                } catch (e: Exception) {
                    // En cas d'erreur sur l'installation des pixels, recycler les ressources
                    bitmapPool.offer(frameBitmap)
                    byteArrayPool.offer(frameBuffer)
                }
            } catch (e: CancellationException) {
                break // Sortir proprement si la coroutine est annulée
            } catch (e: Exception) {
                // Eviter de spammer les erreurs
                if (scope.isActive && _hasMedia) {
                    setError("Error reading frame: ${e.message}")
                }
                yield()
            }
        }
    }

    private suspend inline fun consumeFrames() {
        while (scope.isActive && _hasMedia) {
            if (!_isPlaying) {
                try {
                    awaitPlaybackState(true)
                } catch (e: CancellationException) {
                    break // Sortir proprement si la coroutine est annulée
                }
                continue
            }

            if (isResizing) {
                try {
                    awaitNotResizing()
                } catch (e: CancellationException) {
                    break // Sortir proprement si la coroutine est annulée
                }
                continue
            }

            try {
                val result = frameChannel.tryReceive()
                val frameData = result.getOrNull()

                if (frameData != null) {
                    bitmapLock.write {
                        // Recycler le bitmap précédent si présent
                        _currentFrame?.let { oldBitmap ->
                            if (bitmapPool.size < maxPoolCapacity) {
                                bitmapPool.offer(oldBitmap)
                            } else {
                                oldBitmap.close()
                            }
                        }

                        // Mettre à jour le bitmap courant
                        _currentFrame = frameData.bitmap
                    }

                    // Mettre à jour les informations de temps
                    _currentTime = frameData.timestamp
                    _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                    isLoading = false

                    // Recycler le buffer utilisé
                    if (byteArrayPool.size < maxPoolCapacity) {
                        byteArrayPool.offer(frameData.buffer)
                    }
                } else {
                    yield()
                }
            } catch (e: CancellationException) {
                break // Sortir proprement si la coroutine est annulée
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia) {
                    setError("Error processing frame: ${e.message}")
                }
                yield()
            }

            delay(16)
        }
    }

    override fun play() {
        if (!isInitialized || !_hasMedia) return

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    if (!_isPlaying) {
                        val res = player.SetPlaybackState(true)
                        if (res < 0) {
                            setError("Error starting playback (hr=0x${res.toString(16)})")
                            return@withLock
                        }
                        _isPlaying = true
                    }
                } catch (e: Exception) {
                    setError("Error starting playback: ${e.message}")
                }
            }
        }
    }

    override fun pause() {
        if (!_isPlaying) return

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    val res = player.SetPlaybackState(false)
                    if (res < 0) {
                        setError("Error pausing (hr=0x${res.toString(16)})")
                        return@withLock
                    }
                    _isPlaying = false
                } catch (e: Exception) {
                    setError("Error pausing: ${e.message}")
                }
            }
        }
    }

    override fun stop() {
        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    // Arrêter la lecture
                    _isPlaying = false
                    player.SetPlaybackState(false)
                    delay(50) // Petit délai pour s'assurer que la lecture est bien arrêtée

                    // Libérer les ressources
                    videoJob?.cancelAndJoin()

                    // S'assurer que le bitmap est libéré
                    bitmapLock.write {
                        _currentFrame?.close()
                        _currentFrame = null
                    }

                    // Vider le channel et les pools
                    clearFrameChannel()
                    recreatePools()

                    // Réinitialiser les états
                    _hasMedia = false
                    _progress = 0f
                    _currentTime = 0.0
                    isLoading = false
                    errorMessage = null
                    _error = null

                    // Fermer le média
                    player.CloseMedia()
                } catch (e: Exception) {
                    setError("Error stopping playback: ${e.message}")
                }
            }
        }
    }

    override fun seekTo(value: Float) {
        if (!_hasMedia) return

        scope.launch {
            mediaOperationMutex.withLock {
                val wasPlaying = _isPlaying
                try {
                    // Mettre en pause pendant l'opération de seek
                    if (_isPlaying) {
                        val pauseRes = player.SetPlaybackState(false)
                        if (pauseRes < 0) {
                            setError("Error pausing for seek (hr=0x${pauseRes.toString(16)})")
                            return@withLock
                        }
                        _isPlaying = false
                        delay(50) // Petit délai pour s'assurer que la lecture est bien arrêtée
                    }

                    isLoading = true

                    // Vider le channel pour éviter d'afficher des frames obsolètes
                    clearFrameChannel()

                    // S'assurer que le frame est déverrouillé
                    try {
                        player.UnlockVideoFrame()
                    } catch (e: Exception) {
                        // Ignorer les erreurs si le frame n'était pas verrouillé
                    }

                    // Effectuer le seek
                    val targetPos = (_duration * (value / 1000f) * 10000000).toLong()
                    var hr = player.SeekMedia(targetPos)

                    if (hr < 0) {
                        // Attendre un peu et réessayer
                        delay(50)
                        hr = player.SeekMedia(targetPos)

                        if (hr < 0) {
                            setError("Échec du seek (hr=0x${hr.toString(16)})")
                            return@withLock
                        }
                    }

                    // Mise à jour de la position actuelle
                    val posRef = LongByReference()
                    if (player.GetMediaPosition(posRef) >= 0) {
                        _currentTime = posRef.value / 10000000.0
                        _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                    }

                    // Attendre un peu pour laisser le temps au seek de s'effectuer
                    delay(50)

                    // Reprendre la lecture si nécessaire
                    if (wasPlaying) {
                        hr = player.SetPlaybackState(true)
                        if (hr < 0) {
                            setError("Impossible de reprendre la lecture après le seek (hr=0x${hr.toString(16)})")
                        } else {
                            _isPlaying = true
                        }
                    }
                } catch (e: Exception) {
                    setError("Exception lors du seek : ${e.message}")

                    // Tentative de reprise de la lecture en cas d'erreur
                    if (wasPlaying) {
                        try {
                            player.SetPlaybackState(true)
                            _isPlaying = true
                        } catch (e: Exception) {
                            // Ignorer les erreurs supplémentaires
                        }
                    }
                } finally {
                    delay(50)
                    isLoading = false
                }
            }
        }
    }

    fun onResized() {
        isResizing = true
        scope.launch {
            try {
                // Vider le channel de frames pour éviter les problèmes d'affichage pendant le redimensionnement
                clearFrameChannel()
            } finally {
                // Annuler tout travail de redimensionnement précédent
                resizeJob?.cancel()

                // Programmer la fin du redimensionnement après un délai
                resizeJob = scope.launch {
                    delay(200)
                    isResizing = false
                }
            }
        }
    }

    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
    }

    override fun hideMedia() {}
    override fun showMedia() {}
}