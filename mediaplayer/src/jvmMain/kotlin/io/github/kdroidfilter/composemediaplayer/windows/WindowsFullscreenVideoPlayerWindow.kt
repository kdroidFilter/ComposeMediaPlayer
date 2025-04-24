package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.Composable
import io.github.kdroidfilter.composemediaplayer.common.openFullscreenWindow

/**
 * Opens a fullscreen window for the video player.
 * This function is called when the user toggles fullscreen mode.
 *
 * @param playerState The player state to use in the fullscreen window
 * @param contentScale Controls how the video content should be scaled inside the surface.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 */
@Composable
fun openFullscreenWindow(
    playerState: WindowsVideoPlayerState,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Fit,
    overlay: @Composable () -> Unit = {}
) {
    openFullscreenWindow(
        playerState = playerState,
        renderSurface = { state, modifier, isInFullscreenWindow ->
            WindowsVideoPlayerSurface(
                playerState = state as WindowsVideoPlayerState,
                modifier = modifier,
                contentScale = contentScale,
                overlay = overlay,
                isInFullscreenWindow = isInFullscreenWindow
            )
        }
    )
}
