package io.github.kdroidfilter.composemediaplayer.audio

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual class ComposeAudioPlayer actual constructor() {
    actual fun play(url: String) {
    }

    actual fun play() {
    }

    actual fun stop() {
    }

    actual fun pause() {
    }

    actual fun release() {
    }

    actual fun currentPosition(): Long? {
        TODO("Not yet implemented")
    }

    actual fun currentDuration(): Long? {
        TODO("Not yet implemented")
    }

    actual fun currentPlayerState(): ComposeAudioPlayerState? {
        TODO("Not yet implemented")
    }

    actual fun currentVolume(): Float? {
        TODO("Not yet implemented")
    }

    actual fun setVolume(volume: Float) {
    }

    actual fun setRate(rate: Float) {
    }

    actual fun seekTo(time: Long) {
    }

    actual fun setOnErrorListener(listener: ErrorListener) {
    }
}