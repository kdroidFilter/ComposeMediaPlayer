package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import java.lang.ref.WeakReference

/**
 * Registry for sharing VideoPlayerState instances between activities.
 * This is used to pass the player state to the fullscreen activity.
 */
@Stable
object VideoPlayerStateRegistry {
    private var registeredState: WeakReference<VideoPlayerState>? = null

    /**
     * Register a VideoPlayerState instance to be shared between activities.
     * Uses a WeakReference to avoid memory leaks.
     *
     * @param state The VideoPlayerState to register
     */
    fun registerState(state: VideoPlayerState) {
        registeredState = WeakReference(state)
    }

    /**
     * Get the registered VideoPlayerState instance.
     *
     * @return The registered VideoPlayerState or null if none is registered
     */
    fun getRegisteredState(): VideoPlayerState? {
        return registeredState?.get()
    }

    /**
     * Clear the registered VideoPlayerState instance.
     */
    fun clearRegisteredState() {
        registeredState = null
    }
}