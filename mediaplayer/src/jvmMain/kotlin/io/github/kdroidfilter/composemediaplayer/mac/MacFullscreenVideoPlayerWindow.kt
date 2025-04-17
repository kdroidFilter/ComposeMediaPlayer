package io.github.kdroidfilter.composemediaplayer.mac

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
fun openFullscreenWindow(playerState: MacVideoPlayerState) {
    openFullscreenWindow(
        playerState = playerState,
        renderSurface = { state, modifier, isInFullscreenWindow ->
            MacVideoPlayerSurface(
                playerState = state as MacVideoPlayerState,
                modifier = modifier,
                isInFullscreenWindow = isInFullscreenWindow
            )
        }
    )
}
