package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
actual open class VideoPlayerState {
    //TODO

    actual val isPlaying: Boolean = false
    actual var volume: Float = 1.0f
    actual var sliderPos: Float = 0.0f
    actual var userDragging: Boolean = false
    actual var loop: Boolean = false
    actual val leftLevel: Float = 0.0f
    actual val rightLevel: Float = 0.0f
    actual val positionText: String = "00:00"
    actual val durationText: String = "00:00"

    actual fun openUri(uri: String) {
    }

    actual fun play() {
    }

    actual fun pause() {
    }

    actual fun stop() {
    }

    actual fun seekTo(value: Float) {
    }

    actual fun dispose() {
    }
}
