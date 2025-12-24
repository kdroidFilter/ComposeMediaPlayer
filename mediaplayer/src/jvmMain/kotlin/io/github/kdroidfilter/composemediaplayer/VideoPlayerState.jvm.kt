package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.sun.jna.Platform
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import io.github.vinceglb.filekit.PlatformFile

actual fun createVideoPlayerState(): VideoPlayerState = DefaultVideoPlayerState()

/**
 * Represents the state and behavior of a video player. This class provides properties
 * and methods to control video playback, manage the playback state, and interact with
 * platform-specific implementations.
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
open class DefaultVideoPlayerState: VideoPlayerState {
    val delegate: VideoPlayerState = when {
        Platform.isWindows() -> WindowsVideoPlayerState()
        Platform.isMac() -> MacVideoPlayerState()
        Platform.isLinux() -> LinuxVideoPlayerState()
        else -> throw UnsupportedOperationException("Unsupported platform")
    }

    override val hasMedia: Boolean get() = delegate.hasMedia
    override val isPlaying: Boolean get() = delegate.isPlaying
    override val isLoading: Boolean get() = delegate.isLoading
    override val error: VideoPlayerError? get() = delegate.error
    override var volume: Float
        get() = delegate.volume
        set(value) {
            delegate.volume = value
        }
    override var sliderPos: Float
        get() = delegate.sliderPos
        set(value) {
            delegate.sliderPos = value
        }
    override var userDragging: Boolean
        get() = delegate.userDragging
        set(value) {
            delegate.userDragging = value
        }
    override var loop: Boolean
        get() = delegate.loop
        set(value) {
            delegate.loop = value
        }

    override var playbackSpeed: Float
        get() = delegate.playbackSpeed
        set(value) {
            delegate.playbackSpeed = value
        }

    override var isFullscreen: Boolean
        get() = delegate.isFullscreen
        set(value) {
            delegate.isFullscreen = value
        }

    override val metadata: VideoMetadata get() = delegate.metadata
    override val aspectRatio: Float get() = delegate.aspectRatio

    override var subtitlesEnabled = delegate.subtitlesEnabled
    override var currentSubtitleTrack : SubtitleTrack? = delegate.currentSubtitleTrack
    override val availableSubtitleTracks  = delegate.availableSubtitleTracks
    override var subtitleTextStyle: TextStyle
        get() = delegate.subtitleTextStyle
        set(value) {
            delegate.subtitleTextStyle = value
        }
    override var subtitleBackgroundColor: Color
        get() = delegate.subtitleBackgroundColor
        set(value) {
            delegate.subtitleBackgroundColor = value
        }
    override fun selectSubtitleTrack(track: SubtitleTrack?) = delegate.selectSubtitleTrack(track)
    override fun disableSubtitles() = delegate.disableSubtitles()

    override val leftLevel: Float get() = delegate.leftLevel
    override val rightLevel: Float get() = delegate.rightLevel
    override val positionText: String get() = delegate.positionText
    override val durationText: String get() = delegate.durationText
    override val currentTime: Double get() = delegate.currentTime

    override fun openUri(uri: String, initializeplayerState: InitialPlayerState) = delegate.openUri(uri, initializeplayerState)
    override fun openFile(file: PlatformFile, initializeplayerState: InitialPlayerState) = delegate.openUri(file.file.path, initializeplayerState)
    override fun play() = delegate.play()
    override fun pause() = delegate.pause()
    override fun stop() = delegate.stop()
    override fun seekTo(value: Float) = delegate.seekTo(value)
    override fun toggleFullscreen() = delegate.toggleFullscreen()
    override fun dispose() = delegate.dispose()
    override fun clearError() = delegate.clearError()
}
