package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.logger
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Stable
actual open class VideoPlayerState {
    private val context = ContextProvider.getContext()
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

    actual fun openUri(uri: String) {
        val mediaItem = MediaItem.fromUri(uri)
        openFromMediaItem(mediaItem)
    }

    actual fun openFile(file: PlatformFile) {
        val mediaItem = when (val androidFile = file.androidFile) {
            is AndroidFile.UriWrapper -> MediaItem.fromUri(androidFile.uri)
            is AndroidFile.FileWrapper -> MediaItem.fromUri(androidFile.file.path)
        }
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
                _hasMedia = true  // Set to true when media is loaded
            } catch (e: Exception) {
                logger.debug{"Error opening media: ${e.message}"}
                _isPlaying = false
                _hasMedia = false  // Set to false on error
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
            player.seekTo(0) // Ensure position is reset to beginning
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

    private fun resetStates(keepMedia: Boolean = false) {
        _currentTime = 0.0
        _duration = 0.0
        _sliderPos = 0f
        _leftLevel = 0f
        _rightLevel = 0f
        _isPlaying = false
        _isLoading = false
        _error = null
        if (!keepMedia) {
            _hasMedia = false
        }
    }

    actual fun dispose() {
        stopPositionUpdates()
        coroutineScope.cancel()
        exoPlayer?.let { player ->
            player.stop()
            player.release()
        }
        exoPlayer = null
        resetStates()
    }

}