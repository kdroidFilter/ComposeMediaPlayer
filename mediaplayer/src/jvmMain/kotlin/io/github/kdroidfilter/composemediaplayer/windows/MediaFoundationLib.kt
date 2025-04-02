package io.github.kdroidfilter.composemediaplayer.windows

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * JNA Interface for the OffscreenPlayer DLL.
 */
internal interface MediaFoundationLib : StdCallLibrary {
    companion object {
        val INSTANCE: MediaFoundationLib by lazy {
            Native.load("OffscreenPlayer", MediaFoundationLib::class.java)
        }
    }

    // 1) Initialize Media Foundation
    fun InitMediaFoundation(): Int

    // 2) Open media from URL or file path
    fun OpenMedia(url: WString): Int

    // 3) Read a video frame (RGB32)
    fun ReadVideoFrame(pData: PointerByReference, pDataSize: IntByReference): Int

    // 4) Unlock the video frame buffer
    fun UnlockVideoFrame(): Int

    // 5) Close media and free resources
    fun CloseMedia()

    // 6) Check if end-of-stream has been reached, and control audio playback
    fun IsEOF(): Boolean

    // 7) Get video dimensions
    fun GetVideoSize(pWidth: IntByReference, pHeight: IntByReference)

    // 8) Get video frame rate
    fun GetVideoFrameRate(pNum: IntByReference, pDenom: IntByReference): Int

    // 9) Seek to a specific position (in 100-nanosecond units)
    fun SeekMedia(llPosition: Long): Int

    // 10) Get total media duration (in 100-nanosecond units)
    fun GetMediaDuration(pDuration: LongByReference): Int

    // 11) Get current playback position (in 100-nanosecond units)
    fun GetMediaPosition(pPosition: LongByReference): Int


    fun SetPlaybackState(isPlaying: Boolean): Int

}
