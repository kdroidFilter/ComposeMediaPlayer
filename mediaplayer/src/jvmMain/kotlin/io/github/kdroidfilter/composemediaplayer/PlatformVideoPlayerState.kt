package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Defines a platform-specific video player state interface, providing the essential
 * properties and operations needed for video playback management.
 *
 * The interface is intended to be implemented by platform-specific classes, acting as
 * a layer to abstract the underlying behavior of video players across different operating systems.
 *
 * Properties:
 * - `isPlaying`: Read-only property indicating whether the video is currently playing.
 * - `volume`: Controls the playback volume, with values between 0.0 (mute) and 1.0 (full volume).
 * - `sliderPos`: Represents the current playback position as a normalized value between 0.0 and 1.0.
 * - `userDragging`: Tracks if the user is currently interacting with the playback position control.
 * - `loop`: Specifies whether the video playback should loop continuously.
 * - `leftLevel`: Read-only property giving the audio peak level of the left channel.
 * - `rightLevel`: Read-only property giving the audio peak level of the right channel.
 * - `positionText`: Provides a formatted text representation of the current playback position.
 * - `durationText`: Provides a formatted text representation of the total duration of the video.
 *
 * Methods:
 * - `openUri(uri: String)`: Opens a video resource (file or URL) for playback.
 * - `play()`: Begins or resumes video playback.
 * - `pause()`: Pauses the current video playback.
 * - `stop()`: Stops playback and resets the playback position to the beginning.
 * - `seekTo(value: Float)`: Seeks to a specific playback position based on the given normalized value.
 * - `dispose()`: Releases resources and performs cleanup for the video player instance.
 */
interface PlatformVideoPlayerState {
    val hasMedia : Boolean
    val isPlaying: Boolean
    var volume: Float
    var sliderPos: Float
    var userDragging: Boolean
    var loop: Boolean
    var playbackSpeed: Float
    val leftLevel: Float
    val rightLevel: Float
    val positionText: String
    val durationText: String
    val isLoading: Boolean
    val error: VideoPlayerError?
    var isFullscreen: Boolean

    val metadata: VideoMetadata
    val aspectRatio: Float

    // Subtitle management
    var subtitlesEnabled: Boolean
    var currentSubtitleTrack: SubtitleTrack?
    val availableSubtitleTracks: MutableList<SubtitleTrack>
    var subtitleTextStyle: TextStyle
    var subtitleBackgroundColor: Color
    fun selectSubtitleTrack(track: SubtitleTrack?)
    fun disableSubtitles()

    fun openUri(uri: String)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(value: Float)
    fun toggleFullscreen()
    fun dispose()
    fun clearError()
}
