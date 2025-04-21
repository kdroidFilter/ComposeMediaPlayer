package io.github.kdroidfilter.composemediaplayer.util

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

/**
 * Registry for sharing VideoPlayerState instances between views.
 * This is used to pass the player state to the fullscreen view.
 */
@Stable
object VideoPlayerStateRegistry {
    private var registeredState: VideoPlayerState? = null

    /**
     * Register a VideoPlayerState instance to be shared.
     *
     * @param state The VideoPlayerState to register
     */
    fun registerState(state: VideoPlayerState) {
        registeredState = state
    }

    /**
     * Get the registered VideoPlayerState instance.
     *
     * @return The registered VideoPlayerState or null if none is registered
     */
    fun getRegisteredState(): VideoPlayerState? {
        return registeredState
    }

    /**
     * Clear the registered VideoPlayerState instance.
     */
    fun clearRegisteredState() {
        registeredState = null
    }
}