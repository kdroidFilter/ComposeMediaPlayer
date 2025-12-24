package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import io.github.vinceglb.filekit.PlatformFile

/**
 * Represents the state and controls for a video player. This class provides properties
 * and methods to manage video playback, including play, pause, stop, seeking, and more.
 * It maintains information about the playback state, such as whether the video is
 * currently playing, volume levels, and playback position.
 *
 * Functions of this class are tied to managing and interacting with the underlying
 * video player implementation.
 *
 * @constructor Initializes an instance of the video player state.
 */
@Stable
interface VideoPlayerState {

    // Properties related to media state
    val hasMedia: Boolean

    /**
     * Indicates whether the video is currently playing.
     */
    val isPlaying: Boolean
    val isLoading: Boolean

    /**
     * Controls the playback volume. Valid values are within the range of 0.0 (muted) to 1.0 (maximum volume).
     */
    var volume: Float

    /**
     * Represents the current playback position as a normalized value between 0.0 and 1.0.
     */
    var sliderPos: Float

    /**
     * Denotes whether the user is manually adjusting the playback position.
     */
    var userDragging: Boolean

    /**
     * Specifies if the video should loop when it reaches the end.
     */
    var loop: Boolean
    var playbackSpeed: Float

    /**
     * Provides the audio level for the left channel as a percentage.
     */
    val leftLevel: Float

    /**
     * Provides the audio level for the right channel as a percentage.
     */
    val rightLevel: Float

    /**
     * Returns the current playback position as a formatted string.
     */
    val positionText: String

    /**
     * Returns the total duration of the video as a formatted string.
     */
    val durationText: String
    val currentTime: Double
    var isFullscreen: Boolean
    val aspectRatio: Float

    // Functions to control playback
    /**
     * Starts or resumes video playback.
     */
    fun play()

    /**
     * Pauses video playback.
     */
    fun pause()

    /**
     * Stops playback and resets the player state.
     */
    fun stop()

    /**
     * Seeks to a specific playback position based on the provided normalized value.
     */
    fun seekTo(value: Float)
    fun toggleFullscreen()

    // Functions to manage media sources
    /**
     * Opens a video file or URL for playback.
     */
    fun openUri(uri: String, initializeplayerState: InitialPlayerState = InitialPlayerState.PLAY)
    fun openFile(file: PlatformFile, initializeplayerState: InitialPlayerState = InitialPlayerState.PLAY)

    // Error handling
    val error: VideoPlayerError?
    fun clearError()

    // Metadata
    val metadata: VideoMetadata

    // Subtitle management
    var subtitlesEnabled: Boolean
    var currentSubtitleTrack: SubtitleTrack?
    val availableSubtitleTracks: MutableList<SubtitleTrack>
    var subtitleTextStyle: TextStyle
    var subtitleBackgroundColor: Color
    fun selectSubtitleTrack(track: SubtitleTrack?)
    fun disableSubtitles()

    // Cleanup
    /**
     * Releases resources used by the video player and disposes of the state.
     */
    fun dispose()
}

/**
 *  Create platform-specific video player state. Supported platforms include Windows,
 *  macOS, and Linux.
 */
expect fun createVideoPlayerState(): VideoPlayerState

/**
 * Creates and manages an instance of `VideoPlayerState` within a composable function, ensuring
 * proper disposal of the player state when the composable leaves the composition. This function
 * is used to remember the video player state throughout the composition lifecycle.
 *
 * @return The remembered instance of `VideoPlayerState`, which provides functionalities for
 *         controlling and managing video playback, such as play, pause, stop, and seek.
 */
@Composable
fun rememberVideoPlayerState(): VideoPlayerState {
    val playerState = remember { createVideoPlayerState() }
    DisposableEffect(Unit) {
        onDispose {
            playerState.dispose()
        }
    }
    return playerState
}

/**
 * Helper to mock the [VideoPlayerState].
 */
data class PreviewableVideoPlayerState(
    override val hasMedia: Boolean = true,
    override val isPlaying: Boolean = true,
    override val isLoading: Boolean = false,
    override var volume: Float = 1f,
    override var sliderPos: Float = 500f,
    override var userDragging: Boolean = false,
    override var loop: Boolean = true,
    override var playbackSpeed: Float = 1f,
    override val leftLevel: Float = 1f,
    override val rightLevel: Float = 1f,
    override val positionText: String = "00:05",
    override val durationText: String = "00:10",
    override val currentTime: Double = 5000.0,
    override var isFullscreen: Boolean = false,
    override val aspectRatio: Float = 1.7f,
    override val error: VideoPlayerError? = null,
    override val metadata: VideoMetadata = VideoMetadata(),
    override var subtitlesEnabled: Boolean = false,
    override var currentSubtitleTrack: SubtitleTrack? = null,
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = emptyList<SubtitleTrack>().toMutableList(),
    override var subtitleTextStyle: TextStyle = TextStyle.Default,
    override var subtitleBackgroundColor: Color = Color.Transparent,
) : VideoPlayerState {
    override fun play() {}
    override fun pause() {}
    override fun stop() {}
    override fun seekTo(value: Float) {}
    override fun toggleFullscreen() {}
    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState
    ) {}
    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState
    ) {}
    override fun clearError() {}
    override fun selectSubtitleTrack(track: SubtitleTrack?) {}
    override fun disableSubtitles() {}
    override fun dispose() {}
}