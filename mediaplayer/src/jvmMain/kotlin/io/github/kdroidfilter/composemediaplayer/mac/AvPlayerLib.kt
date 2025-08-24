package io.github.kdroidfilter.composemediaplayer.mac

import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA direct mapping to the native library.
 * Includes methods to retrieve frame rate and metadata information.
 */
internal object SharedVideoPlayer {
    init {
        // Register the native library for direct mapping
        Native.register("NativeVideoPlayer")
    }

    @JvmStatic external fun createVideoPlayer(): Pointer?
    @JvmStatic external fun openUri(context: Pointer?, uri: String?)
    @JvmStatic external fun playVideo(context: Pointer?)
    @JvmStatic external fun pauseVideo(context: Pointer?)
    @JvmStatic external fun setVolume(context: Pointer?, volume: Float)
    @JvmStatic external fun getVolume(context: Pointer?): Float
    @JvmStatic external fun getLatestFrame(context: Pointer?): Pointer?
    @JvmStatic external fun getFrameWidth(context: Pointer?): Int
    @JvmStatic external fun getFrameHeight(context: Pointer?): Int
    @JvmStatic external fun getVideoFrameRate(context: Pointer?): Float
    @JvmStatic external fun getScreenRefreshRate(context: Pointer?): Float
    @JvmStatic external fun getCaptureFrameRate(context: Pointer?): Float
    @JvmStatic external fun getVideoDuration(context: Pointer?): Double
    @JvmStatic external fun getCurrentTime(context: Pointer?): Double
    @JvmStatic external fun seekTo(context: Pointer?, time: Double)
    @JvmStatic external fun disposeVideoPlayer(context: Pointer?)
    @JvmStatic external fun getLeftAudioLevel(context: Pointer?): Float
    @JvmStatic external fun getRightAudioLevel(context: Pointer?): Float
    @JvmStatic external fun setPlaybackSpeed(context: Pointer?, speed: Float)
    @JvmStatic external fun getPlaybackSpeed(context: Pointer?): Float

    // Metadata retrieval functions
    @JvmStatic external fun getVideoTitle(context: Pointer?): String?
    @JvmStatic external fun getVideoBitrate(context: Pointer?): Long
    @JvmStatic external fun getVideoMimeType(context: Pointer?): String?
    @JvmStatic external fun getAudioChannels(context: Pointer?): Int
    @JvmStatic external fun getAudioSampleRate(context: Pointer?): Int
}
