package io.github.kdroidfilter.composemediaplayer.mac

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA interface to the native library.
 * Includes new methods to retrieve frame rate information.
 */
internal interface SharedVideoPlayer : Library {
    fun createVideoPlayer(): Pointer?
    fun openUri(context: Pointer?, uri: String?)
    fun playVideo(context: Pointer?)
    fun pauseVideo(context: Pointer?)
    fun setVolume(context: Pointer?, volume: Float)
    fun getVolume(context: Pointer?): Float
    fun getLatestFrame(context: Pointer?): Pointer?
    fun getFrameWidth(context: Pointer?): Int
    fun getFrameHeight(context: Pointer?): Int
    fun getVideoFrameRate(context: Pointer?): Float
    fun getScreenRefreshRate(context: Pointer?): Float
    fun getCaptureFrameRate(context: Pointer?): Float
    fun getVideoDuration(context: Pointer?): Double
    fun getCurrentTime(context: Pointer?): Double
    fun seekTo(context: Pointer?, time: Double)
    fun disposeVideoPlayer(context: Pointer?)
    fun getLeftAudioLevel(context: Pointer?): Float
    fun getRightAudioLevel(context: Pointer?): Float
    fun setPlaybackSpeed(context: Pointer?, speed: Float)
    fun getPlaybackSpeed(context: Pointer?): Float

    companion object {
        val INSTANCE: SharedVideoPlayer =
            Native.load("NativeVideoPlayer", SharedVideoPlayer::class.java)
    }
}
