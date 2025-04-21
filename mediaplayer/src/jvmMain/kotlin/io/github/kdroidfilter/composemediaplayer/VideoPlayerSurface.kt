package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerSurface

/**
 * Composable function for rendering a video player surface.
 *
 * The function delegates the rendering logic to specific platform-specific implementations
 * based on the type of the `delegate` within the provided `VideoPlayerState`.
 *
 * @param playerState The current state of the video player, encapsulating playback state
 *                    and platform-specific implementation details.
 * @param modifier A [Modifier] for styling or adjusting the layout of the video player surface.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 */
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState, 
    modifier: Modifier,
    overlay: @Composable () -> Unit
) {
    when (val delegate = playerState.delegate) {
        is WindowsVideoPlayerState -> WindowsVideoPlayerSurface(delegate, modifier, overlay)
        is MacVideoPlayerState -> MacVideoPlayerSurface(delegate, modifier, overlay)
        is LinuxVideoPlayerState -> LinuxVideoPlayerSurface(delegate, modifier, overlay)
        else -> throw IllegalArgumentException("Unsupported player state type")
    }
}
