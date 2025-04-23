package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
expect open class VideoPlayerState() {

    // Properties related to media state
    val hasMedia: Boolean
    val isPlaying: Boolean
    val isLoading: Boolean
    var volume: Float
    var sliderPos: Float
    var userDragging: Boolean
    var loop: Boolean
    var playbackSpeed: Float
    val leftLevel: Float
    val rightLevel: Float
    val positionText: String
    val durationText: String
    var isFullscreen: Boolean
    val aspectRatio: Float

    // Functions to control playback
    fun play()
    fun pause()
    fun stop()
    fun seekTo(value: Float)
    fun toggleFullscreen()

    // Functions to manage media sources
    fun openUri(uri: String)
    fun openFile(file: PlatformFile)

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
    fun dispose()
}

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
    val playerState = remember { VideoPlayerState() }
    DisposableEffect(Unit) {
        onDispose {
            playerState.dispose()
        }
    }
    return playerState
}
