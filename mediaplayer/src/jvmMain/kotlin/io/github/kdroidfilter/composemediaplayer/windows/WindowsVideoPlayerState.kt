package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.github.kdroidfilter.composemediaplayer.PlatformVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import kotlinx.coroutines.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File

/**
 * Implémentation Windows "offscreen" pour lire une vidéo via la DLL OffscreenPlayer.
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Indicateur d'init
    var isInitialized by mutableStateOf(false)
        private set

    // A-t-on un média ouvert ?
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    // Lecture en cours ?
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
        }

    // Position, durée, etc. (non entièrement géré ici)
    private var _currentTime by mutableStateOf(0.0)
    private var _duration by mutableStateOf(0.0)
    private var _progress by mutableStateOf(0f)
    override var sliderPos: Float
        get() = _progress * 1000f
        set(value) {
            _progress = (value / 1000f).coerceIn(0f, 1f)
        }

    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            _userDragging = value
        }

    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
        }

    override val leftLevel: Float get() = 0f
    override val rightLevel: Float get() = 0f

    // Frame courante
    var currentFrame: Bitmap? by mutableStateOf(null)
        private set

    // Erreur
    private var _error: VideoPlayerError? = null
    override val error: VideoPlayerError? get() = _error

    override fun clearError() {
        _error = null
        errorMessage = null
    }

    // Sous-titres (non gérés)
    override val metadata: VideoMetadata = VideoMetadata()
    override var subtitlesEnabled: Boolean = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override fun selectSubtitleTrack(track: SubtitleTrack?) {}
    override fun disableSubtitles() {}

    // Divers
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)

    // Pour afficher/masquer la vidéo
    override fun showMedia() {
        _hasMedia = true
    }
    override fun hideMedia() {
        _hasMedia = false
    }

    // Pour logs d'erreur
    var errorMessage: String? by mutableStateOf(null)
        private set

    // Job de lecture vidéo
    private var videoJob: Job? = null

    // Taille réelle vidéo (détectée)
    var videoWidth: Int = 0
    var videoHeight: Int = 0

    init {
        // Init MediaFoundation
        val hr = player.InitMediaFoundation()
        isInitialized = (hr >= 0)
        if (!isInitialized) {
            setError("InitMediaFoundation a échoué (hr=0x${hr.toString(16)})")
        }
    }

    override fun dispose() {
        videoJob?.cancel()
        player.CloseMedia()
        isInitialized = false
        _isPlaying = false
        _hasMedia = false
    }

    override fun openUri(uri: String) {
        if (!isInitialized) {
            setError("Player non initialisé.")
            return
        }
        videoJob?.cancel()
        player.CloseMedia()

        // Vérifier fichier
        val file = File(uri)
        if (!uri.startsWith("http", ignoreCase = true) && !file.exists()) {
            setError("Fichier introuvable : $uri")
            return
        }

        val hrOpen = player.OpenMedia(WString(uri))
        if (hrOpen < 0) {
            setError("OpenMedia($uri) a échoué (hr=0x${hrOpen.toString(16)})")
            return
        }

        _hasMedia = true
        _isPlaying = false

        // Récupérer la taille réelle
        val wRef = IntByReference()
        val hRef = IntByReference()
        player.GetVideoSize(wRef, hRef)
        videoWidth = wRef.value
        videoHeight = hRef.value
        if (videoWidth <= 0 || videoHeight <= 0) {
            // Valeur par défaut si la vidéo ne fournit pas d'info
            videoWidth = 1280
            videoHeight = 720
        }

        play()

        // Coroutine de lecture vidéo
        videoJob = scope.launch {
            var lastFrameTime = 0L
            val numRef = IntByReference()
            val denomRef = IntByReference()
            player.GetVideoFrameRate(numRef, denomRef)
            val frameRateNum = numRef.value
            val frameRateDenom = denomRef.value
            val frameDurationMs = if (frameRateNum > 0) {
                (1000.0 * frameRateDenom / frameRateNum).toLong()
            } else {
                33L // roughly 30 FPS
            }

            while (isActive && !player.IsEOF()) {
                if (_isPlaying) {
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - lastFrameTime

                    // Only process a new frame if enough time has passed
                    if (elapsedTime >= frameDurationMs) {
                        val ptrRef = PointerByReference()
                        val sizeRef = IntByReference()
                        val hrFrame = player.ReadVideoFrame(ptrRef, sizeRef)

                        if (hrFrame >= 0) {
                            val pFrame = ptrRef.value
                            val dataSize = sizeRef.value
                            if (pFrame != null && dataSize > 0) {
                                val byteArray = ByteArray(dataSize)
                                pFrame.read(0, byteArray, 0, dataSize)

                                // Unlock
                                player.UnlockVideoFrame()

                                // Conversion en Bitmap Skia (BGRA)
                                val bmp = Bitmap().apply {
                                    allocPixels(
                                        ImageInfo(
                                            width = videoWidth,
                                            height = videoHeight,
                                            colorType = ColorType.BGRA_8888,
                                            alphaType = ColorAlphaType.OPAQUE
                                        )
                                    )
                                    installPixels(
                                        imageInfo,
                                        byteArray,
                                        rowBytes = videoWidth * 4
                                    )
                                }

                                withContext(Dispatchers.Main) {
                                    currentFrame = bmp
                                }

                                lastFrameTime = currentTime
                            }
                        }
                    } else {
                        delay(1)
                    }
                } else {
                    // En pause => on attend un peu
                    delay(50)
                }
            }
        }
    }

    override fun play() {
        if (!isInitialized || !_hasMedia) return
        _isPlaying = true
        player.StartAudioPlayback() // Lance l'audio
    }

    override fun pause() {
        _isPlaying = false
        player.StopAudioPlayback()
    }

    override fun stop() {
        _isPlaying = false
        player.StopAudioPlayback()
        currentFrame = null
        // Pas de "seek" implémenté : pour revenir à 0, il faudrait rouvrir le média ou faire un seek via SourceReader
    }

    override fun seekTo(value: Float) {
        // Non implémenté ici
    }

    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
    }
}
