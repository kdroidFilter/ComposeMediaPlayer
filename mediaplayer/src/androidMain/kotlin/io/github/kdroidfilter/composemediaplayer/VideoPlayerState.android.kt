package io.github.kdroidfilter.composemediaplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

@OptIn(UnstableApi::class)
actual fun createVideoPlayerState(audioMode: AudioMode): VideoPlayerState =
    try {
        DefaultVideoPlayerState(audioMode)
    } catch (e: IllegalStateException) {
        PreviewableVideoPlayerState(
            hasMedia = false,
            isPlaying = false,
            isLoading = false,
            volume = 1f,
            sliderPos = 0f,
            userDragging = false,
            loop = false,
            playbackSpeed = 1f,
            positionText = "00:00",
            durationText = "00:00",
            currentTime = 0.0,
            isFullscreen = false,
            aspectRatio = 16f / 9f,
            error =
                VideoPlayerError.UnknownError(
                    "Android context is not available (preview or missing ContextProvider initialization).",
                ),
            metadata = VideoMetadata(),
            subtitlesEnabled = false,
            currentSubtitleTrack = null,
            availableSubtitleTracks = mutableListOf(),
            subtitleTextStyle = TextStyle.Default,
            subtitleBackgroundColor = Color.Transparent,
        )
    }

internal val androidVideoLogger = TaggedLogger("AndroidVideoPlayerSurface")

@UnstableApi
@Stable
open class DefaultVideoPlayerState(
    private val audioMode: AudioMode = AudioMode(),
) : VideoPlayerState {
    companion object {
        var activity: WeakReference<Activity> = WeakReference(null)

        private var currentPlayerState: WeakReference<DefaultVideoPlayerState>? = null

        /**
         * Call this from Activity.onPictureInPictureModeChanged()
         */
        fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
            currentPlayerState?.get()?.isPipActive = isInPictureInPictureMode
        }

        internal fun register(state: DefaultVideoPlayerState) {
            currentPlayerState = WeakReference(state)
        }
    }

    private val context: Context = ContextProvider.getContext()
    internal var exoPlayer: ExoPlayer? = null
    private var updateJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Protection against race conditions
    private var isPlayerReleased = false
    private val playerInitializationLock = Object()
    private var playerListener: Player.Listener? = null

    // Screen lock detection
    private var screenLockReceiver: BroadcastReceiver? = null
    private var wasPlayingBeforeScreenLock: Boolean = false

    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    // State properties
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _isLoading by mutableStateOf(false)
    override val isLoading: Boolean get() = _isLoading

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    override val error: VideoPlayerError? get() = _error

    private var _metadata = VideoMetadata()
    override val metadata: VideoMetadata get() = _metadata

    // Subtitle state
    override var subtitlesEnabled by mutableStateOf(false)
    override var currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    override val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    override var subtitleTextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )

    override var subtitleBackgroundColor by mutableStateOf(Color.Black.copy(alpha = 0.5f))

    private var playerView: PlayerView? = null

    // Select an external subtitle track
    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null) {
            disableSubtitles()
            return
        }

        currentSubtitleTrack = track
        subtitlesEnabled = true

        exoPlayer?.let { player ->
            val trackParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            player.trackSelectionParameters = trackParameters

            playerView?.subtitleView?.visibility = android.view.View.GONE
        }
    }

    override fun disableSubtitles() {
        currentSubtitleTrack = null
        subtitlesEnabled = false

        exoPlayer?.let { player ->
            val parameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setPreferredTextLanguage(null)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            player.trackSelectionParameters = parameters

            playerView?.subtitleView?.visibility = android.view.View.GONE
        }
    }

    internal fun attachPlayerView(view: PlayerView?) {
        if (view == null) {
            // Detach the current view
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
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
            exoPlayer?.volume = _volume
        }

    // Slider position
    private var _sliderPos by mutableFloatStateOf(0f)
    override var sliderPos: Float
        get() = _sliderPos
        set(value) {
            _sliderPos = value.coerceIn(0f, 1000f)
            if (!userDragging) {
                seekTo(value)
            }
        }

    // User interaction states
    override var userDragging by mutableStateOf(false)

    override var onPlaybackEnded: (() -> Unit)? = null

    // Loop control
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
            exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        }

    // Playback speed control
    private var _playbackSpeed by mutableFloatStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            _playbackSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            exoPlayer?.let { player ->
                player.playbackParameters = PlaybackParameters(_playbackSpeed)
            }
        }

    // Aspect ratio
    private var _aspectRatio by mutableFloatStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            _isFullscreen = value
        }

    var isPipFullScreen by mutableStateOf(false)

    // Time tracking
    private var _currentTime by mutableDoubleStateOf(0.0)
    private var _duration by mutableDoubleStateOf(0.0)
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    override val currentTime: Double get() = _currentTime

    override val isPipSupported: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ctx = activity.get() ?: ContextProvider.getContext()
                return ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            }
            return false
        }

    override var isPipEnabled by mutableStateOf(false)
    override var isPipActive by mutableStateOf(false)

    init {
        register(this)
        initializePlayer()
        registerScreenLockReceiver()
    }

    private fun shouldUseConservativeCodecHandling(): Boolean {
        val device = android.os.Build.DEVICE
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL

        // List of devices known to have MediaCodec issues
        val problematicDevices =
            setOf(
                "SM-A155F", // Galaxy A15
                "SM-A156B", // Galaxy A15 5G
                // Add other problematic models here
            )

        return device in problematicDevices ||
            model in problematicDevices ||
            manufacturer.equals("mediatek", ignoreCase = true)
    }

    private fun registerScreenLockReceiver() {
        unregisterScreenLockReceiver()

        screenLockReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
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
                                        // Add a small delay to ensure the system is ready
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

        val filter =
            IntentFilter().apply {
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

            val audioSink =
                DefaultAudioSink
                    .Builder(context)
                    .build()

            val renderersFactory =
                object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = audioSink
                }.apply {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    // Enable decoder fallback for better stability
                    setEnableDecoderFallback(true)

                    // On problematic devices, use more conservative settings
                    if (shouldUseConservativeCodecHandling()) {
                        // Cannot disable async queueing as the method does not exist
                        // But we can use the default MediaCodecSelector
                        setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                    }
                }

            val manageFocus = audioMode.interruptionMode == InterruptionMode.DoNotMix
            val audioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

            exoPlayer =
                ExoPlayer
                    .Builder(context)
                    .setRenderersFactory(renderersFactory)
                    .setHandleAudioBecomingNoisy(manageFocus)
                    .setWakeMode(if (manageFocus) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NONE)
                    .setAudioAttributes(audioAttributes, manageFocus)
                    .setPauseAtEndOfMediaItems(false)
                    .setReleaseTimeoutMs(2000) // Increase the release timeout
                    .build()
                    .apply {
                        playerListener = createPlayerListener()
                        addListener(playerListener!!)
                        volume = _volume
                    }
        }
    }

    private fun createPlayerListener() =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Add a safety check
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
                        onPlaybackEnded?.invoke()
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

                // Create a detailed error report
                val errorDetails =
                    mapOf(
                        "error_code" to error.errorCode.toString(),
                        "error_message" to (error.message ?: "Unknown"),
                        "device" to android.os.Build.DEVICE,
                        "model" to android.os.Build.MODEL,
                        "manufacturer" to android.os.Build.MANUFACTURER,
                        "android_version" to
                            android.os.Build.VERSION.SDK_INT
                                .toString(),
                        "codec_info" to error.cause?.message,
                    )

                // Log the error details (you can send this to your crash reporting service)
                androidVideoLogger.e { "Detailed error info: $errorDetails" }

                // Codec-specific error handling
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    -> {
                        _error = VideoPlayerError.CodecError("Decoder error: ${error.message}")
                        // Attempt recovery for codec errors
                        attemptPlayerRecovery()
                    }
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    -> {
                        _error = VideoPlayerError.NetworkError("Network error: ${error.message}")
                    }
                    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    -> {
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
            delay(100) // Small delay to let the system clean up

            synchronized(playerInitializationLock) {
                if (!isPlayerReleased) {
                    exoPlayer?.let { player ->
                        val currentPosition = player.currentPosition
                        val currentMediaItem = player.currentMediaItem
                        val wasPlaying = player.isPlaying

                        try {
                            // Remove the listener before releasing
                            playerListener?.let { player.removeListener(it) }

                            // Release the current player
                            player.release()

                            // Reinitialize
                            initializePlayer()

                            // Restore the media item and position
                            currentMediaItem?.let {
                                exoPlayer?.apply {
                                    setMediaItem(it)
                                    prepare()
                                    seekTo(currentPosition)
                                    // Restore the playback state if needed
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
        updateJob =
            coroutineScope.launch {
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

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem, initializeplayerState)
    }

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        val mediaItemBuilder = MediaItem.Builder()
        val videoUri: Uri =
            when (val androidFile = file.androidFile) {
                is AndroidFile.UriWrapper -> androidFile.uri
                is AndroidFile.FileWrapper -> Uri.fromFile(androidFile.file)
            }
        mediaItemBuilder.setUri(videoUri)
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem, initializeplayerState)
    }

    private fun openFromMediaItem(
        mediaItem: MediaItem,
        initializeplayerState: InitialPlayerState,
    ) {
        synchronized(playerInitializationLock) {
            if (isPlayerReleased) return

            exoPlayer?.let { player ->
                player.stop()
                player.clearMediaItems()
                try {
                    _error = null
                    resetStates(keepMedia = true)

                    // Extract metadata before preparing the player
                    extractMediaItemMetadata(mediaItem)

                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.volume = volume
                    player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

                    // Control the initial playback state
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

    override fun play() {
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

    override fun pause() {
        synchronized(playerInitializationLock) {
            if (!isPlayerReleased) {
                exoPlayer?.pause()
            }
        }
    }

    override fun stop() {
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

    fun togglePipFullScreen() {
        isPipFullScreen = !isPipFullScreen
    }

    override suspend fun enterPip(): PipResult {
        if (!isPipSupported) return PipResult.NotSupported
        if (!isPipEnabled) return PipResult.NotEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return PipResult.NotPossible

        val currentActivity = activity.get() ?: return PipResult.NotPossible

        if (!isPipFullScreen) {
            togglePipFullScreen()
            // Wait for Compose to recompose with fullscreen layout
            withFrameNanos { }
            withFrameNanos { } // two frames to be safe
        }

        val params =
            PictureInPictureParams
                .Builder()
                .setAspectRatio(Rational(16, 9))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setAutoEnterEnabled(true)
                    }
                }.build()

        val result = currentActivity.enterPictureInPictureMode(params)

        return if (result) {
            isPipActive = true
            PipResult.Success
        } else {
            PipResult.NotPossible
        }
    }

    override fun seekTo(value: Float) {
        if (_duration > 0 && !isPlayerReleased) {
            val targetTime = (value / 1000.0) * _duration
            exoPlayer?.seekTo((targetTime * 1000).toLong())
        }
    }

    override fun clearError() {
        _error = null
    }

    override fun toggleFullscreen() {
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

    override fun dispose() {
        synchronized(playerInitializationLock) {
            isPlayerReleased = true
            stopPositionUpdates()
            coroutineScope.cancel()
            playerView?.player = null
            playerView = null

            try {
                exoPlayer?.let { player ->
                    // Remove the listener specifically
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
