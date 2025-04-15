package io.github.kdroidfilter.composemediaplayer.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

internal interface MediaFoundationLib : StdCallLibrary {
    companion object {
        val INSTANCE: MediaFoundationLib by lazy {
            Native.load("NativeVideoPlayer", MediaFoundationLib::class.java)
        }

        /**
         * Creates a new instance of the native video player
         * @return A pointer to the native instance or null if creation failed
         */
        fun createInstance(): Pointer? {
            val ptrRef = PointerByReference()
            val hr = INSTANCE.CreateVideoPlayerInstance(ptrRef)
            return if (hr >= 0 && ptrRef.value != null) ptrRef.value else null
        }

        /**
         * Destroys a native video player instance
         * @param instance The pointer to the native instance to destroy
         */
        fun destroyInstance(instance: Pointer) {
            INSTANCE.DestroyVideoPlayerInstance(instance)
        }
    }

    fun InitMediaFoundation(): Int
    fun CreateVideoPlayerInstance(ppInstance: PointerByReference): Int
    fun DestroyVideoPlayerInstance(pInstance: Pointer)
    fun OpenMedia(pInstance: Pointer, url: WString): Int
    fun ReadVideoFrame(pInstance: Pointer, pData: PointerByReference, pDataSize: IntByReference): Int
    fun UnlockVideoFrame(pInstance: Pointer): Int
    fun CloseMedia(pInstance: Pointer)
    fun IsEOF(pInstance: Pointer): Boolean
    fun GetVideoSize(pInstance: Pointer, pWidth: IntByReference, pHeight: IntByReference)
    fun GetVideoFrameRate(pInstance: Pointer, pNum: IntByReference, pDenom: IntByReference): Int
    fun SeekMedia(pInstance: Pointer, lPosition: Long): Int
    fun GetMediaDuration(pInstance: Pointer, pDuration: LongByReference): Int
    fun GetMediaPosition(pInstance: Pointer, pPosition: LongByReference): Int
    fun SetPlaybackState(pInstance: Pointer, isPlaying: Boolean): Int
    fun ShutdownMediaFoundation(): Int
    fun SetAudioVolume(pInstance: Pointer, volume: Float): Int
    fun GetAudioVolume(pInstance: Pointer, volume: FloatByReference): Int
    fun GetAudioLevels(pInstance: Pointer, pLeftLevel: FloatByReference, pRightLevel: FloatByReference): Int
}
