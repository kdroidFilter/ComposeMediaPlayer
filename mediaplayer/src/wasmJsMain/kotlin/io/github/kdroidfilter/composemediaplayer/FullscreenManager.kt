package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages fullscreen functionality for the video player
 */
object FullscreenManager {
    /**
     * Exit fullscreen if document is in fullscreen mode
     */
    fun exitFullscreen() {
        if (document.fullscreenElement != null) {
            document.exitFullscreen()
        }
    }

    /**
     * Request fullscreen mode
     */
    fun requestFullScreen() {
        val document = document.documentElement
        document?.requestFullscreen()
    }

    /**
     * Toggle fullscreen mode
     * @param isCurrentlyFullscreen Whether the player is currently in fullscreen mode
     * @param onFullscreenChange Callback to update the fullscreen state
     */
    fun toggleFullscreen(isCurrentlyFullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
        if (!isCurrentlyFullscreen) {
            requestFullScreen()
            CoroutineScope(Dispatchers.Default).launch {
                delay(500)
            }
        } else {
            exitFullscreen()
        }
        onFullscreenChange(!isCurrentlyFullscreen)
    }
}