package io.github.kdroidfilter.composemediaplayer.util

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import java.lang.ref.WeakReference

/**
 * Registry for sharing WindowsVideoPlayerState instances between windows.
 * This is used to pass the player state to the fullscreen window.
 */
@Stable
object VideoPlayerStateRegistry {
    private var registeredState: WeakReference<VideoPlayerState>? = null

    /**
     * Register a WindowsVideoPlayerState instance to be shared between windows.
     * Uses a WeakReference to avoid memory leaks.
     *
     * @param state The WindowsVideoPlayerState to register
     */
    fun registerState(state: VideoPlayerState) {
        registeredState = WeakReference(state)
    }

    /**
     * Get the registered WindowsVideoPlayerState instance.
     *
     * @return The registered WindowsVideoPlayerState or null if none is registered
     */
    fun getRegisteredState(): VideoPlayerState? {
        return registeredState?.get()
    }

    /**
     * Clear the registered WindowsVideoPlayerState instance.
     */
    fun clearRegisteredState() {
        registeredState = null
    }
}