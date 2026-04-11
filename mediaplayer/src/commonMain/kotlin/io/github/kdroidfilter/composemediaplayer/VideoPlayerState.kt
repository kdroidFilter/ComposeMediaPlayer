package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.util.PipResult
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
     * Represents the current playback position as a value between 0.0 and 1000.0.
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
     * Callback invoked when playback reaches the end of the media.
     * Only called when [loop] is false. May be invoked from a background thread.
     */
    var onPlaybackEnded: (() -> Unit)?

    companion object {
        const val MIN_PLAYBACK_SPEED = 0.25f
        const val MAX_PLAYBACK_SPEED = 2.0f
    }

    /**
     * Returns the current playback position as a formatted string.
     */
    val positionText: String

    /**
     * Returns the total duration of the video as a formatted string.
     */
    val durationText: String
    val currentTime: Double

    /**
     * Returns the total duration of the media in seconds.
     */
    val duration: Double
    var isFullscreen: Boolean
    val aspectRatio: Float

    val isPipSupported: Boolean get() = false
    var isPipActive: Boolean get() = false
        set(value) {}
    var isPipEnabled: Boolean get() = false
        set(value) {}

    suspend fun enterPip(): PipResult = PipResult.NotSupported

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
     * Seeks to a specific playback position. The [value] should be between 0.0 and 1000.0.
     */
    fun seekTo(value: Float)

    fun toggleFullscreen()

    // Functions to manage media sources

    /**
     * Opens a video file or URL for playback.
     */
    fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState = InitialPlayerState.PLAY,
    )

    fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState = InitialPlayerState.PLAY,
    )

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
     * Releases all resources used by the video player (native players, coroutines, observers, etc.).
     *
     * **You do not need to call this manually** when using [rememberVideoPlayerState], which is the
     * recommended way to create a player state in a composable. It automatically calls [dispose]
     * via a [DisposableEffect] when the composable leaves the composition.
     *
     * Only call this directly if you create the state manually via [createVideoPlayerState] outside
     * of a composable lifecycle:
     * ```
     * val state = createVideoPlayerState()
     * try {
     *     // use the player...
     * } finally {
     *     state.dispose()
     * }
     * ```
     *
     * After calling [dispose], the state should not be reused.
     */
    fun dispose()
}

/**
 *  Create platform-specific video player state. Supported platforms include Windows,
 *  macOS, and Linux.
 */
expect fun createVideoPlayerState(audioMode: AudioMode = AudioMode()): VideoPlayerState

/**
 * Creates and remembers a [VideoPlayerState], automatically releasing all player resources
 * when the composable leaves the composition.
 *
 * This is the **recommended** way to obtain a [VideoPlayerState]. You do not need to call
 * [VideoPlayerState.dispose] yourself — cleanup is handled via [DisposableEffect].
 *
 * ```
 * @Composable
 * fun MyPlayer() {
 *     val playerState = rememberVideoPlayerState()
 *     // use playerState — resources are freed automatically on removal
 * }
 * ```
 *
 * @param audioMode The audio mode configuration for the player.
 * @return The remembered instance of [VideoPlayerState].
 */
@Composable
fun rememberVideoPlayerState(audioMode: AudioMode = AudioMode()): VideoPlayerState {
    val playerState = remember(audioMode) { createVideoPlayerState(audioMode) }
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
    override val positionText: String = "00:05",
    override val durationText: String = "00:10",
    override val currentTime: Double = 5000.0,
    override val duration: Double = 10.0,
    override var isFullscreen: Boolean = false,
    override val aspectRatio: Float = 1.7f,
    override val error: VideoPlayerError? = null,
    override val metadata: VideoMetadata = VideoMetadata(),
    override var subtitlesEnabled: Boolean = false,
    override var currentSubtitleTrack: SubtitleTrack? = null,
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = emptyList<SubtitleTrack>().toMutableList(),
    override var subtitleTextStyle: TextStyle = TextStyle.Default,
    override var subtitleBackgroundColor: Color = Color.Transparent,
    override val isPipSupported: Boolean = false,
    override var isPipActive: Boolean = false,
    override var isPipEnabled: Boolean = false,
    override var onPlaybackEnded: (() -> Unit)? = null,
) : VideoPlayerState {
    override fun play() {}

    override fun pause() {}

    override fun stop() {}

    override fun seekTo(value: Float) {}

    override fun toggleFullscreen() {}

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {}

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {}

    override fun clearError() {}

    override fun selectSubtitleTrack(track: SubtitleTrack?) {}

    override fun disableSubtitles() {}

    override fun dispose() {}
}
