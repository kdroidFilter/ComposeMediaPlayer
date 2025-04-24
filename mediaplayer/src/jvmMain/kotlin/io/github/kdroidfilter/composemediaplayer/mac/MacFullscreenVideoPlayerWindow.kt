package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
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
    playerState: MacVideoPlayerState,
    overlay: @Composable () -> Unit = {},
    contentScale: ContentScale = ContentScale.Fit
) {
    openFullscreenWindow(
        playerState = playerState,
        renderSurface = { state, modifier, isInFullscreenWindow ->
            MacVideoPlayerSurface(
                playerState = state as MacVideoPlayerState,
                modifier = modifier,
                contentScale = contentScale,
                overlay = overlay,
                isInFullscreenWindow = isInFullscreenWindow
            )
        }
    )
}
