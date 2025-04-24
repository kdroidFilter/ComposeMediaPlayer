package io.github.kdroidfilter.composemediaplayer.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Platform
import io.github.kdroidfilter.composemediaplayer.PlatformVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.VideoPlayerStateRegistry

/**
 * Opens a fullscreen window for the video player.
 * This function is called when the user toggles fullscreen mode.
 *
 * @param playerState The player state to use in the fullscreen window
 * @param renderSurface A composable function that renders the video player surface
 */
@Composable
fun openFullscreenWindow(
    playerState: PlatformVideoPlayerState,
    renderSurface: @Composable (PlatformVideoPlayerState, Modifier, Boolean) -> Unit
) {
    // Register the player state to be accessible from the fullscreen window
    VideoPlayerStateRegistry.registerState(playerState)
    FullscreenVideoPlayerWindow(renderSurface)
}

/**
 * A composable function that creates a fullscreen window for the video player.
 *
 * @param renderSurface A composable function that renders the video player surface
 */
@Composable
private fun FullscreenVideoPlayerWindow(
    renderSurface: @Composable (PlatformVideoPlayerState, Modifier, Boolean) -> Unit
) {
    // Get the player state from the registry
    val playerState = remember {
        VideoPlayerStateRegistry.getRegisteredState()
    }

    // Create a window state for fullscreen
    val windowState = rememberWindowState(placement = WindowPlacement.Maximized)

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
        }) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            playerState?.let { state ->
                renderSurface(state, Modifier.fillMaxSize(), true)
            }
        }
    }
}