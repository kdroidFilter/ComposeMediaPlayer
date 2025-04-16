package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
internal val androidVideoLogger = Logger.withTag("WasmVideoPlayerSurface")
    .apply { Logger.setMinSeverity(Severity.Warn) }

@UnstableApi
@Stable
actual open class VideoPlayerState {
    private val context: Context = ContextProvider.getContext()
    internal var exoPlayer: ExoPlayer? = null
    private var updateJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioProcessor = AudioLevelProcessor()

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

    private var playerView: PlayerView? = null

    // Select an external subtitle track before media preparation
    actual fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null) {
            disableSubtitles()
            return
        }
        // Update current track and enable flag.
        currentSubtitleTrack = track
        subtitlesEnabled = true

        exoPlayer?.let { player ->
            // Get current playback position and playing state
            val currentPos = player.currentPosition
            val wasPlaying = player.isPlaying

            // If media is already loaded, we need to reload it with the new subtitle configuration
            if (player.currentMediaItem != null) {
                val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (currentUri != null) {
                    // Create new MediaItem with subtitle configuration
                    val mediaItemBuilder = MediaItem.Builder().setUri(currentUri)
                    val subtitleUri = Uri.parse(track.src)
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(track.language)
                        .setLabel(track.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))

                    // Replace current media item
                    player.setMediaItem(mediaItemBuilder.build())
                    player.prepare()

                    // Restore playback state
                    player.seekTo(currentPos)
                    if (wasPlaying) player.play()
                }
            }

            // Update track selection parameters
            val trackParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(track.language)
                .build()
            player.trackSelectionParameters = trackParameters
        }
    }

    actual fun disableSubtitles() {
        exoPlayer?.let { player ->
            val parameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            player.trackSelectionParameters = parameters
        }
        currentSubtitleTrack = null
        subtitlesEnabled = false
    }

    internal fun attachPlayerView(view: PlayerView) {
        playerView = view
        exoPlayer?.let { player ->
            view.player = player
            // Set default subtitle style
            view.subtitleView?.setStyle(CaptionStyleCompat.DEFAULT)
        }
    }

    // Volume control
    private var _volume by mutableStateOf(1f)
    actual var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
            exoPlayer?.volume = _volume
        }

    // Slider position
    private var _sliderPos by mutableStateOf(0f)
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

    // Audio levels
    private var _leftLevel by mutableStateOf(0f)
    private var _rightLevel by mutableStateOf(0f)
    actual val leftLevel: Float get() = _leftLevel
    actual val rightLevel: Float get() = _rightLevel

    // Aspect ratio
    private var _aspectRatio by mutableStateOf(16f / 9f)
    val aspectRatio: Float get() = _aspectRatio

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    actual var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            _isFullscreen = value
        }

    // Time tracking
    private var _currentTime by mutableStateOf(0.0)
    private var _duration by mutableStateOf(0.0)
    actual val positionText: String get() = formatTime(_currentTime)
    actual val durationText: String get() = formatTime(_duration)


    init {
        audioProcessor.setOnAudioLevelUpdateListener { left, right ->
            _leftLevel = left
            _rightLevel = right
        }
        initializePlayer()
    }

    private fun initializePlayer() {
        val audioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(audioProcessor))
            .build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = audioSink
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                addListener(createPlayerListener())
                volume = _volume
            }
    }

    private fun createPlayerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _isLoading = true
                }

                Player.STATE_READY -> {
                    _isLoading = false
                    exoPlayer?.let { player ->
                        _duration = player.duration.toDouble() / 1000.0
                        _isPlaying = player.isPlaying
                        if (player.isPlaying) startPositionUpdates()
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
            _isPlaying = playing
            if (playing) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // Update aspect ratio when video size changes
            if (videoSize.width > 0 && videoSize.height > 0) {
                _aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                // Update metadata
                _metadata.width = videoSize.width
                _metadata.height = videoSize.height
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _error = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    VideoPlayerError.CodecError("Decoder initialization failed: ${error.message}")

                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    VideoPlayerError.NetworkError("Network error: ${error.message}")

                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    VideoPlayerError.SourceError("Invalid media source: ${error.message}")

                else -> VideoPlayerError.UnknownError("Playback error: ${error.message}")
            }
            _isPlaying = false
            _isLoading = false
        }
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        updateJob = coroutineScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_READY) {
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

    /**
     * Open a video URI.
     * If a subtitle track is selected, add it as an external subtitle source.
     */
    actual fun openUri(uri: String) {
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        currentSubtitleTrack?.let { subtitle ->
            // Build subtitle configuration for external subtitle
            val subtitleUri = Uri.parse(subtitle.src)
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(MimeTypes.TEXT_VTT) // Adjust MIME type as needed
                .setLanguage(subtitle.language)
                .setLabel(subtitle.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
        }
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem)
    }

    /**
     * Open a video file.
     * Converts the file into a URI and adds external subtitle configuration if selected.
     */
    actual fun openFile(file: PlatformFile) {
        val mediaItemBuilder = MediaItem.Builder()
        val androidFile = file.androidFile
        val videoUri: Uri = when (androidFile) {
            is AndroidFile.UriWrapper -> androidFile.uri
            is AndroidFile.FileWrapper -> Uri.fromFile(androidFile.file)
        }
        mediaItemBuilder.setUri(videoUri)
        currentSubtitleTrack?.let { subtitle ->
            val subtitleUri = Uri.parse(subtitle.src)
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(subtitle.language)
                .setLabel(subtitle.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
        }
        val mediaItem = mediaItemBuilder.build()
        openFromMediaItem(mediaItem)
    }

    private fun openFromMediaItem(mediaItem: MediaItem) {
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            try {
                _error = null
                player.setMediaItem(mediaItem)
                player.prepare()
                player.volume = volume
                player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                player.play()
                _hasMedia = true // Set to true when media is loaded
            } catch (e: Exception) {
                androidVideoLogger.d { "Error opening media: ${e.message}" }
                _isPlaying = false
                _hasMedia = false // Set to false on error
                _error = VideoPlayerError.SourceError("Failed to load media: ${e.message}")
            }
        }
    }

    actual fun play() {
        exoPlayer?.let { player ->
            if (player.playbackState == Player.STATE_IDLE) {
                // If the player is in IDLE state (after stop), prepare it again
                player.prepare()
            }
            player.play()
        }
        _hasMedia = true
    }

    actual fun pause() {
        exoPlayer?.pause()
    }

    actual fun stop() {
        exoPlayer?.let { player ->
            player.stop()
            player.seekTo(0) // Reset position to beginning
        }
        _hasMedia = false
        resetStates(keepMedia = true)
    }

    actual fun seekTo(value: Float) {
        if (_duration > 0) {
            val targetTime = (value / 1000.0) * _duration
            exoPlayer?.seekTo((targetTime * 1000).toLong())
        }
    }

    actual fun clearError() {
        _error = null
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    actual fun toggleFullscreen() {
        _isFullscreen = !_isFullscreen
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
        _aspectRatio = 16f / 9f  // Reset aspect ratio to default
        if (!keepMedia) {
            _hasMedia = false
        }
    }

    actual fun dispose() {
        stopPositionUpdates()
        coroutineScope.cancel()
        playerView?.player = null
        playerView = null
        exoPlayer?.let { player ->
            player.stop()
            player.release()
        }
        exoPlayer = null
        resetStates()
    }
}
