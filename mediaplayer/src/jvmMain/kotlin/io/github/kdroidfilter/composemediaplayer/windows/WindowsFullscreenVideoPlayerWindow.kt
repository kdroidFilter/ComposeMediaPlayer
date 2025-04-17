package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.common.openFullscreenWindow

/**
 * Opens a fullscreen window for the video player.
 * This function is called when the user toggles fullscreen mode.
 *
 * @param playerState The player state to use in the fullscreen window
 */
@Composable
fun openFullscreenWindow(playerState: WindowsVideoPlayerState) {
    openFullscreenWindow(
        playerState = playerState,
        renderSurface = { state, modifier, isInFullscreenWindow ->
            WindowsVideoPlayerSurface(
                playerState = state as WindowsVideoPlayerState,
                modifier = modifier,
                isInFullscreenWindow = isInFullscreenWindow
            )
        }
    )
}
