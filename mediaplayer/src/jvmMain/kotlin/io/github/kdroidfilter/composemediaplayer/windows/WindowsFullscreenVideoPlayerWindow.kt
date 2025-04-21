package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.Composable
import io.github.kdroidfilter.composemediaplayer.common.openFullscreenWindow

/**
 * Opens a fullscreen window for the video player.
 * This function is called when the user toggles fullscreen mode.
 *
 * @param playerState The player state to use in the fullscreen window
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 */
@Composable
fun openFullscreenWindow(
    playerState: WindowsVideoPlayerState,
    overlay: @Composable () -> Unit = {}
) {
    openFullscreenWindow(
        playerState = playerState,
        renderSurface = { state, modifier, isInFullscreenWindow ->
            WindowsVideoPlayerSurface(
                playerState = state as WindowsVideoPlayerState,
                modifier = modifier,
                overlay = overlay,
                isInFullscreenWindow = isInFullscreenWindow
            )
        }
    )
}
