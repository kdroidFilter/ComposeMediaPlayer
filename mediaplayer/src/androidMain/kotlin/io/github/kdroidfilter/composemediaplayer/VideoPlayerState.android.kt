package io.github.kdroidfilter.composemediaplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*

/**
 * Logger for WebAssembly video player surface
 */
internal val androidVideoLogger = Logger.withTag("AndroidVideoPlayerSurface")
    .apply { Logger.setMinSeverity(Severity.Warn) }

@UnstableApi
@Stable
actual open class VideoPlayerState {
    private val context: Context = ContextProvider.getContext()
    internal var exoPlayer: ExoPlayer? = null
    private var updateJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioProcessor = AudioLevelProcessor()

    // Protection contre les race conditions
    private var isPlayerReleased = false
    private val playerInitializationLock = Object()
    private var playerListener: Player.Listener? = null

    // Screen lock detection
    private var screenLockReceiver: BroadcastReceiver? = null
    private var wasPlayingBeforeScreenLock: Boolean = false

    private var _hasMedia by mutableStateOf(false)
    actual val hasMedia: Boolean get() = _hasMedia

    // State properties
    private var _isPlaying by mutableStateOf(false)
    actual val isPlaying: Boolean get() = _isPlaying

    private var _isLoading by mutableStateOf(false)
    actual val isLoading: Boolean get() = _isLoading

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    actual val error: VideoPlayerError? get() = _error

    private var _metadata = VideoMetadata()
    actual val metadata: VideoMetadata get() = _metadata

    // Subtitle state
    actual var subtitlesEnabled by mutableStateOf(false)
    actual var currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    actual val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    actual var subtitleTextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    )

    actual var subtitleBackgroundColor by mutableStateOf(Color.Black.copy(alpha = 0.5f))

    private var playerView: PlayerView? = null

    // Select an external subtitle track
    actual fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null) {
            disableSubtitles()
            return
        }

        currentSubtitleTrack = track
        subtitlesEnabled = true

        exoPlayer?.let { player ->
            val trackParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            player.trackSelectionParameters = trackParameters

            playerView?.subtitleView?.visibility = android.view.View.GONE
        }
    }

    actual fun disableSubtitles() {
        currentSubtitleTrack = null
        subtitlesEnabled = false

        exoPlayer?.let { player ->
            val parameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            player.trackSelectionParameters = parameters

            playerView?.subtitleView?.visibility = android.view.View.GONE
        }
    }

    internal fun attachPlayerView(view: PlayerView?) {
        if (view == null) {
            // Détacher la vue actuelle
            playerView?.player = null
            playerView = null
            return
        }

        playerView = view
        exoPlayer?.let { player ->
            try {
                view.player = player
                view.subtitleView?.setStyle(CaptionStyleCompat.DEFAULT)
            } catch (e: Exception) {
                androidVideoLogger.e { "Error attaching player to view: ${e.message}" }
            }
        }
    }

    // Volume control
    private var _volume by mutableFloatStateOf(1f)
    actual var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
            exoPlayer?.volume = _volume
        }

    // Slider position
    private var _sliderPos by mutableFloatStateOf(0f)
    actual var sliderPos: Float
        get() = _sliderPos
        set(value) {
            _sliderPos = value.coerceIn(0f, 1000f)
            if (!userDragging) {
                seekTo(value)
            }
        }

    // User interaction states
    actual var userDragging by mutableStateOf(false)

    // Loop control
    private var _loop by mutableStateOf(false)
    actual var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
            exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        }

    // Playback speed control
    private var _playbackSpeed by mutableFloatStateOf(1.0f)
    actual var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            _playbackSpeed = value.coerceIn(0.5f, 2.0f)
            exoPlayer?.let { player ->
                player.playbackParameters = PlaybackParameters(_playbackSpeed)
            }
        }

    // Audio levels
    private var _leftLevel by mutableFloatStateOf(0f)
    private var _rightLevel by mutableFloatStateOf(0f)
    actual val leftLevel: Float get() = _leftLevel
    actual val rightLevel: Float get() = _rightLevel

    // Aspect ratio
    private var _aspectRatio by mutableFloatStateOf(16f / 9f)
    actual val aspectRatio: Float get() = _aspectRatio

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    actual var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            _isFullscreen = value
        }

    // Time tracking
    private var _currentTime by mutableDoubleStateOf(0.0)
    private var _duration by mutableDoubleStateOf(0.0)
    actual val positionText: String get() = formatTime(_currentTime)
    actual val durationText: String get() = formatTime(_duration)
    actual val currentTime: Double get() = _currentTime


    init {
        audioProcessor.setOnAudioLevelUpdateListener { left, right ->
            _leftLevel = left
            _rightLevel = right
        }
        initializePlayer()
        registerScreenLockReceiver()
    }

    private fun shouldUseConservativeCodecHandling(): Boolean {
        val device = android.os.Build.DEVICE
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL

        // Liste des appareils connus pour avoir des problèmes MediaCodec
        val problematicDevices = setOf(
            "SM-A155F", // Galaxy A15
            "SM-A156B", // Galaxy A15 5G
            // Ajouter d'autres modèles problématiques ici
        )

        return device in problematicDevices ||
                model in problematicDevices ||
                manufacturer.equals("mediatek", ignoreCase = true)
    }

    private fun registerScreenLockReceiver() {
        unregisterScreenLockReceiver()

        screenLockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        androidVideoLogger.d { "Screen turned off (locked)" }
                        synchronized(playerInitializationLock) {
                            if (!isPlayerReleased && exoPlayer != null) {
                                wasPlayingBeforeScreenLock = _isPlaying
                                if (_isPlaying) {
                                    try {
                                        androidVideoLogger.d { "Pausing playback due to screen lock" }
                                        exoPlayer?.pause()
                                    } catch (e: Exception) {
                                        androidVideoLogger.e { "Error pausing on screen lock: ${e.message}" }
                                    }
                                }
                            }
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        androidVideoLogger.d { "Screen turned on (unlocked)" }
                        synchronized(playerInitializationLock) {
                            if (!isPlayerReleased && wasPlayingBeforeScreenLock && exoPlayer != null) {
                                try {
                                    // Ajouter un petit délai pour s'assurer que le système est prêt
                                    coroutineScope.launch {
                                        delay(200)
                                        if (!isPlayerReleased) {
                                            androidVideoLogger.d { "Resuming playback after screen unlock" }
                                            exoPlayer?.play()
                                        }
                                    }
                                } catch (e: Exception) {
                                    androidVideoLogger.e { "Error resuming after screen unlock: ${e.message}" }
                                }
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(screenLockReceiver, filter)
        androidVideoLogger.d { "Screen lock receiver registered" }
    }

    private fun unregisterScreenLockReceiver() {
        screenLockReceiver?.let {
            try {
                context.unregisterReceiver(it)
                androidVideoLogger.d { "Screen lock receiver unregistered" }
            } catch (e: Exception) {
                androidVideoLogger.e { "Error unregistering screen lock receiver: ${e.message}" }
            }
            screenLockReceiver = null
        }
    }

    private fun initializePlayer() {
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return

            val audioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(audioProcessor))
                .build()

            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink = audioSink
            }.apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                // Activer le fallback du décodeur pour une meilleure stabilité
                setEnableDecoderFallback(true)

                // Sur les appareils problématiques, utiliser des paramètres plus conservateurs
                if (shouldUseConservativeCodecHandling()) {
                    // On ne peut pas désactiver l'async queueing car la méthode n'existe pas
                    // Mais on peut utiliser le MediaCodecSelector par défaut
                    setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                }
            }

            exoPlayer = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setPauseAtEndOfMediaItems(false)
                .setReleaseTimeoutMs(2000) // Augmenter le timeout de libération
                .build()
                .apply {
                    playerListener = createPlayerListener()
                    addListener(playerListener!!)
                    volume = _volume
                }
        }
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Ajouter une vérification de sécurité
            if (isPlayerReleased) return

            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _isLoading = true
                }

                Player.STATE_READY -> {
                    _isLoading = false
                    exoPlayer?.let { player ->
                        if (!isPlayerReleased) {
                            _duration = player.duration.toDouble() / 1000.0
                            _isPlaying = player.isPlaying
                            if (player.isPlaying) startPositionUpdates()
                            extractFormatMetadata(player)
                        }
                    }
                }

                Player.STATE_ENDED -> {
                    _isLoading = false
                    stopPositionUpdates()
                    _isPlaying = false
                }

                Player.STATE_IDLE -> {
                    _isLoading = false
                }
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            if (!isPlayerReleased) {
                _isPlaying = playing
                if (playing) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                _metadata.width = videoSize.width
                _metadata.height = videoSize.height
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            androidVideoLogger.e { "Player error occurred: ${error.errorCode} - ${error.message}" }

            // Créer un rapport d'erreur détaillé
            val errorDetails = mapOf(
                "error_code" to error.errorCode.toString(),
                "error_message" to (error.message ?: "Unknown"),
                "device" to android.os.Build.DEVICE,
                "model" to android.os.Build.MODEL,
                "manufacturer" to android.os.Build.MANUFACTURER,
                "android_version" to android.os.Build.VERSION.SDK_INT.toString(),
                "codec_info" to error.cause?.message
            )

            // Log the error details (you can send this to your crash reporting service)
            androidVideoLogger.e { "Detailed error info: $errorDetails" }

            // Gestion des erreurs spécifiques au codec
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                    _error = VideoPlayerError.CodecError("Decoder error: ${error.message}")
                    // Tenter une récupération pour les erreurs de codec
                    attemptPlayerRecovery()
                }
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    _error = VideoPlayerError.NetworkError("Network error: ${error.message}")
                }
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                    _error = VideoPlayerError.SourceError("Invalid media source: ${error.message}")
                }
                else -> {
                    _error = VideoPlayerError.UnknownError("Playback error: ${error.message}")
                }
            }
            _isPlaying = false
            _isLoading = false
        }
    }

    private fun attemptPlayerRecovery() {
        coroutineScope.launch {
            delay(100) // Petit délai pour laisser le système nettoyer

            synchronized(playerInitializationLock) {
                if (!isPlayerReleased) {
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val currentMediaItem = player.currentMediaItem
                        val wasPlaying = player.isPlaying

                        try {
                            // Retirer le listener avant de libérer
                            playerListener?.let { player.removeListener(it) }

                            // Libérer le lecteur actuel
                            player.release()

                            // Réinitialiser
                            initializePlayer()

                            // Restaurer l'élément média et la position
                            currentMediaItem?.let {
                                exoPlayer?.apply {
                                    setMediaItem(it)
                                    prepare()
                                    seekTo(currentPosition)
                                    // Restaurer l'état de lecture si nécessaire
                                    if (wasPlaying) {
                                        play()
                                    } else {
                                        pause()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            androidVideoLogger.e { "Error during player recovery: ${e.message}" }
                            _error = VideoPlayerError.UnknownError("Recovery failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        updateJob = coroutineScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_READY && !isPlayerReleased) {
                        _currentTime = player.currentPosition.toDouble() / 1000.0
                        if (!userDragging && _duration > 0) {
                            _sliderPos = (_currentTime / _duration * 1000).toFloat()
                        }
                    }
                }
                delay(16) // ~60fps update rate
            }
        }
    }

    private fun stopPositionUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    actual fun openUri(uri: String, initializeplayerState: InitialPlayerState) {
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem, initializeplayerState)
    }

    actual fun openFile(file: PlatformFile, initializeplayerState: InitialPlayerState) {
        val mediaItemBuilder = MediaItem.Builder()
        val videoUri: Uri = when (val androidFile = file.androidFile) {
            is AndroidFile.UriWrapper -> androidFile.uri
            is AndroidFile.FileWrapper -> Uri.fromFile(androidFile.file)
        }
        mediaItemBuilder.setUri(videoUri)
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem, initializeplayerState)
    }

    private fun openFromMediaItem(mediaItem: MediaItem, initializeplayerState: InitialPlayerState) {
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return

            exoPlayer?.let { player ->
                player.stop()
                player.clearMediaItems()
                try {
                    _error = null
                    resetStates(keepMedia = true)

                    // Extraire les métadonnées avant de préparer le lecteur
                    extractMediaItemMetadata(mediaItem)

                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.volume = volume
                    player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

                    // Contrôler l'état de lecture initial
                    if (initializeplayerState == InitialPlayerState.PLAY) {
                        player.play()
                        _hasMedia = true
                    } else {
                        player.pause()
                        _isPlaying = false
                        _hasMedia = true
                    }
                } catch (e: Exception) {
                    androidVideoLogger.d { "Error opening media: ${e.message}" }
                    _isPlaying = false
                    _hasMedia = false
                    _error = VideoPlayerError.SourceError("Failed to load media: ${e.message}")
                }
            }
        }
    }

    actual fun play() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.play()
                }
                _hasMedia = true
            }
        }
    }

    actual fun pause() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.pause()
            }
        }
    }

    actual fun stop() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.let { player ->
                    player.stop()
                    player.seekTo(0)
                }
                _hasMedia = false
                resetStates(keepMedia = true)
            }
        }
    }

    actual fun seekTo(value: Float) {
        if (_duration > 0 && !isPlayerReleased) {
            val targetTime = (value / 1000.0) * _duration
            exoPlayer?.seekTo((targetTime * 1000).toLong())
        }
    }

    actual fun clearError() {
        _error = null
    }

    actual fun toggleFullscreen() {
        _isFullscreen = !_isFullscreen
    }

    private fun extractFormatMetadata(player: Player) {
        try {
            if (player.duration > 0 && player.duration != C.TIME_UNSET) {
                _metadata.duration = player.duration
            }

            player.currentTracks.groups.forEach { group ->
                for (i in 0 until group.length) {
                    val trackFormat = group.getTrackFormat(i)

                    when (group.type) {
                        C.TRACK_TYPE_VIDEO -> {
                            if (trackFormat.frameRate > 0) {
                                _metadata.frameRate = trackFormat.frameRate
                            }

                            if (trackFormat.bitrate > 0) {
                                _metadata.bitrate = trackFormat.bitrate.toLong()
                            }

                            trackFormat.sampleMimeType?.let {
                                _metadata.mimeType = it
                            }
                        }

                        C.TRACK_TYPE_AUDIO -> {
                            if (trackFormat.channelCount > 0) {
                                _metadata.audioChannels = trackFormat.channelCount
                            }

                            if (trackFormat.sampleRate > 0) {
                                _metadata.audioSampleRate = trackFormat.sampleRate
                            }
                        }
                    }
                }
            }

            extractMediaItemMetadata(player.currentMediaItem)

            androidVideoLogger.d { "Metadata extracted: $_metadata" }
        } catch (e: Exception) {
            androidVideoLogger.e { "Error extracting format metadata: ${e.message}" }
        }
    }

    private fun extractMediaItemMetadata(mediaItem: MediaItem?) {
        try {
            mediaItem?.mediaMetadata?.let { metadata ->
                metadata.title?.toString()?.let { _metadata.title = it }
            }
        } catch (e: Exception) {
            androidVideoLogger.e { "Error extracting media item metadata: ${e.message}" }
        }
    }

    private fun resetStates(keepMedia: Boolean = false) {
        _currentTime = 0.0
        _duration = 0.0
        _sliderPos = 0f
        _leftLevel = 0f
        _rightLevel = 0f
        _isPlaying = false
        _isLoading = false
        _error = null
        _aspectRatio = 16f / 9f
        _playbackSpeed = 1.0f
        _metadata = VideoMetadata()
        exoPlayer?.playbackParameters = PlaybackParameters(_playbackSpeed)
        if (!keepMedia) {
            _hasMedia = false
        }
    }

    actual fun dispose() {
        synchronized(playerInitializationLock) {
            isPlayerReleased = true
            stopPositionUpdates()
            coroutineScope.cancel()
            playerView?.player = null
            playerView = null

            try {
                exoPlayer?.let { player ->
                    // Retirer le listener spécifiquement
                    playerListener?.let { listener ->
                        player.removeListener(listener)
                    }
                    player.stop()
                    player.clearMediaItems()
                    player.release()
                }
            } catch (e: Exception) {
                androidVideoLogger.e { "Error during player disposal: ${e.message}" }
            }

            playerListener = null
            exoPlayer = null
            unregisterScreenLockReceiver()
            resetStates()
        }
    }
}