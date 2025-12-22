package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.sun.jna.Platform
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import io.github.vinceglb.filekit.PlatformFile


/**
 * Represents the state and behavior of a video player. This class provides properties
 * and methods to control video playback, manage the playback state, and interact with
 * platform-specific implementations.
 *
 * The actual implementation delegates its behavior to platform-specific video player
 * states based on the detected operating system. Supported platforms include Windows,
 * macOS, and Linux.
 *
 * Properties:
 * - `isPlaying`: Indicates whether the video is currently playing.
 * - `volume`: Controls the playback volume. Valid values are within the range of 0.0 (muted) to 1.0 (maximum volume).
 * - `sliderPos`: Represents the current playback position as a normalized value between 0.0 and 1.0.
 * - `userDragging`: Denotes whether the user is manually adjusting the playback position.
 * - `loop`: Specifies if the video should loop when it reaches the end.
 * - `leftLevel`: Provides the audio level for the left channel as a percentage.
 * - `rightLevel`: Provides the audio level for the right channel as a percentage.
 * - `positionText`: Returns the current playback position as a formatted string.
 * - `durationText`: Returns the total duration of the video as a formatted string.
 *
 * Methods:
 * - `openUri(uri: String)`: Opens a video file or URL for playback.
 * - `play()`: Starts or resumes video playback.
 * - `pause()`: Pauses video playback.
 * - `stop()`: Stops playback and resets the player state.
 * - `seekTo(value: Float)`: Seeks to a specific playback position based on the provided normalized value.
 * - `dispose()`: Releases resources used by the video player and disposes of the state.
 */
@Stable
actual open class VideoPlayerState internal constructor(
    val delegate: PlatformVideoPlayerState,
) {
    actual constructor() : this(createPlatformDelegate())

    actual open val hasMedia: Boolean get() = delegate.hasMedia
    actual open val isPlaying: Boolean get() = delegate.isPlaying
    actual open val isLoading: Boolean get() = delegate.isLoading
    actual open val error: VideoPlayerError? get() = delegate.error
    actual open var volume: Float
        get() = delegate.volume
        set(value) {
            delegate.volume = value
        }
    actual open var sliderPos: Float
        get() = delegate.sliderPos
        set(value) {
            delegate.sliderPos = value
        }
    actual open var userDragging: Boolean
        get() = delegate.userDragging
        set(value) {
            delegate.userDragging = value
        }
    actual open var loop: Boolean
        get() = delegate.loop
        set(value) {
            delegate.loop = value
        }

    actual open var playbackSpeed: Float
        get() = delegate.playbackSpeed
        set(value) {
            delegate.playbackSpeed = value
        }

    actual open var isFullscreen: Boolean
        get() = delegate.isFullscreen
        set(value) {
            delegate.isFullscreen = value
        }

    actual open val metadata: VideoMetadata get() = delegate.metadata
    actual open val aspectRatio: Float get() = delegate.aspectRatio

    actual var subtitlesEnabled: Boolean
        get() = delegate.subtitlesEnabled
        set(value) {
            delegate.subtitlesEnabled = value
        }

    actual var currentSubtitleTrack: SubtitleTrack?
        get() = delegate.currentSubtitleTrack
        set(value) {
            delegate.currentSubtitleTrack = value
        }

    actual val availableSubtitleTracks: MutableList<SubtitleTrack>
        get() = delegate.availableSubtitleTracks

    actual var subtitleTextStyle: TextStyle
        get() = delegate.subtitleTextStyle
        set(value) {
            delegate.subtitleTextStyle = value
        }
    actual var subtitleBackgroundColor: Color
        get() = delegate.subtitleBackgroundColor
        set(value) {
            delegate.subtitleBackgroundColor = value
        }
    actual fun selectSubtitleTrack(track: SubtitleTrack?) = delegate.selectSubtitleTrack(track)
    actual fun disableSubtitles() = delegate.disableSubtitles()

    actual open val leftLevel: Float get() = delegate.leftLevel
    actual open val rightLevel: Float get() = delegate.rightLevel
    actual open val positionText: String get() = delegate.positionText
    actual open val durationText: String get() = delegate.durationText
    actual open val currentTime: Double get() = delegate.currentTime

    actual open fun openUri(uri: String, initializeplayerState: InitialPlayerState) = delegate.openUri(uri, initializeplayerState)
    actual open fun openFile(file: PlatformFile, initializeplayerState: InitialPlayerState) = delegate.openUri(file.file.path, initializeplayerState)
    actual open fun play() = delegate.play()
    actual open fun pause() = delegate.pause()
    actual open fun stop() = delegate.stop()
    actual open fun seekTo(value: Float) = delegate.seekTo(value)
    actual open fun toggleFullscreen() = delegate.toggleFullscreen()
    actual open fun dispose() = delegate.dispose()
    actual open fun clearError() = delegate.clearError()

}

private fun createPlatformDelegate(): PlatformVideoPlayerState = when {
    Platform.isWindows() -> WindowsVideoPlayerState()
    Platform.isMac() -> MacVideoPlayerState()
    Platform.isLinux() -> LinuxVideoPlayerState()
    else -> throw UnsupportedOperationException("Unsupported platform")
}

internal actual fun createVideoPlayerState(isInPreview: Boolean): VideoPlayerState {
    return if (isInPreview) {
        VideoPlayerState(PreviewPlatformVideoPlayerState())
    } else {
        VideoPlayerState()
    }
}

@Stable
private class PreviewPlatformVideoPlayerState : PlatformVideoPlayerState {
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    override var volume: Float by mutableStateOf(1f)
    override var sliderPos: Float by mutableStateOf(0f)
    override var userDragging: Boolean by mutableStateOf(false)
    override var loop: Boolean by mutableStateOf(false)
    override var playbackSpeed: Float by mutableStateOf(1f)

    override val leftLevel: Float get() = 0f
    override val rightLevel: Float get() = 0f
    override val positionText: String get() = "00:00"
    override val durationText: String get() = "00:00"
    override val currentTime: Double get() = 0.0

    override val isLoading: Boolean get() = false

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    override val error: VideoPlayerError? get() = _error

    override var isFullscreen: Boolean by mutableStateOf(false)

    override val metadata: VideoMetadata = VideoMetadata()
    override val aspectRatio: Float get() = 16f / 9f

    override var subtitlesEnabled: Boolean by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override var subtitleTextStyle: TextStyle by mutableStateOf(TextStyle(color = Color.White))
    override var subtitleBackgroundColor: Color by mutableStateOf(Color.Black.copy(alpha = 0.5f))

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        currentSubtitleTrack = track
        subtitlesEnabled = track != null
    }

    override fun disableSubtitles() {
        currentSubtitleTrack = null
        subtitlesEnabled = false
    }

    override fun openUri(uri: String, initializeplayerState: InitialPlayerState) {
        _hasMedia = true
        _isPlaying = initializeplayerState == InitialPlayerState.PLAY
        _error = VideoPlayerError.UnknownError("Video playback is not available in preview.")
    }

    override fun play() {
        _hasMedia = true
        _isPlaying = true
    }

    override fun pause() {
        _isPlaying = false
    }

    override fun stop() {
        _isPlaying = false
        sliderPos = 0f
    }

    override fun seekTo(value: Float) {
        sliderPos = value.coerceIn(0f, 1000f)
    }

    override fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    override fun dispose() = Unit

    override fun clearError() {
        _error = null
    }
}
