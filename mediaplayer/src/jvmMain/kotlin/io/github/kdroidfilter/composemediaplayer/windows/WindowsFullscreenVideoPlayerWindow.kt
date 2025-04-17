package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.composemediaplayer.util.VideoPlayerStateRegistry

/**
 * Opens a fullscreen window for the video player.
 * This function is called when the user toggles fullscreen mode.
 *
 * @param playerState The player state to use in the fullscreen window
 */
@Composable
fun openFullscreenWindow(playerState: WindowsVideoPlayerState) {
    // Register the player state to be accessible from the fullscreen window
    VideoPlayerStateRegistry.registerState(playerState)
    WindowsFullscreenVideoPlayerWindow()

}

/**
 * A composable function that creates a fullscreen window for the video player.
 *
 */
@Composable
private fun WindowsFullscreenVideoPlayerWindow() {
    // Get the player state from the registry
    val playerState = remember {
        VideoPlayerStateRegistry.getRegisteredState()
    }

    // Create a window state for fullscreen
    val windowState = rememberWindowState(placement = WindowPlacement.Fullscreen)

    var isVisible by mutableStateOf(true)

    // Handle window close to exit fullscreen
    DisposableEffect(Unit) {
        onDispose {
            playerState?.isFullscreen = false
        }
    }

    fun exitFullScreen() {
        isVisible = false
        playerState?.isFullscreen = false
        VideoPlayerStateRegistry.clearRegisteredState()
    }

    Window(
        onCloseRequest = { exitFullScreen() },
        visible = isVisible,
        undecorated = true,
        state = windowState,
        title = "Fullscreen Player",
        onKeyEvent = { keyEvent ->
            if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
               exitFullScreen()
                true
            } else {
                false
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            playerState?.let { state ->
                WindowsVideoPlayerSurface(
                    playerState = state as WindowsVideoPlayerState,
                    modifier = Modifier.fillMaxSize(),
                    isInFullscreenWindow = true
                )
            }
        }
    }
}
