package io.github.kdroidfilter.composemediaplayer.windows

import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader
import java.nio.ByteBuffer

internal object WindowsNativeBridge {
    /** Expected native API version — must match NATIVE_VIDEO_PLAYER_VERSION in the DLL. */
    private const val EXPECTED_NATIVE_VERSION = 2

    init {
        NativeLibraryLoader.load("NativeVideoPlayer", WindowsNativeBridge::class.java)
        val nativeVersion = nGetNativeVersion()
        require(nativeVersion == EXPECTED_NATIVE_VERSION) {
            "NativeVideoPlayer DLL version mismatch: expected $EXPECTED_NATIVE_VERSION but got $nativeVersion. " +
                "Please rebuild the native DLL or update the Kotlin bindings."
        }
    }

    // ----- Helpers -----

    fun createInstance(): Long {
        val handle = nCreateInstance()
        return if (handle != 0L) handle else 0L
    }

    fun destroyInstance(handle: Long) = nDestroyInstance(handle)

    fun getVideoMetadata(handle: Long): VideoMetadata? {
        val title = CharArray(256)
        val mimeType = CharArray(64)
        val longVals = LongArray(2)
        val intVals = IntArray(4)
        val floatVals = FloatArray(1)
        val hasFlags = BooleanArray(9)

        val hr = nGetVideoMetadata(handle, title, mimeType, longVals, intVals, floatVals, hasFlags)
        if (hr < 0) return null

        return VideoMetadata(
            title = if (hasFlags[0]) String(title).trim { it <= ' ' || it == '\u0000' } else null,
            duration = if (hasFlags[1]) longVals[0] / 10000 else null,
            width = if (hasFlags[2]) intVals[0] else null,
            height = if (hasFlags[3]) intVals[1] else null,
            bitrate = if (hasFlags[4]) longVals[1] else null,
            frameRate = if (hasFlags[5]) floatVals[0] else null,
            mimeType = if (hasFlags[6]) String(mimeType).trim { it <= ' ' || it == '\u0000' } else null,
            audioChannels = if (hasFlags[7]) intVals[2] else null,
            audioSampleRate = if (hasFlags[8]) intVals[3] else null,
        )
    }

    // ----- JNI native methods (registered via JNI_OnLoad / RegisterNatives) -----

    @JvmStatic external fun nGetNativeVersion(): Int

    @JvmStatic external fun nInitMediaFoundation(): Int

    @JvmStatic external fun nCreateInstance(): Long

    @JvmStatic external fun nDestroyInstance(handle: Long)

    @JvmStatic external fun nOpenMedia(
        handle: Long,
        url: String,
        startPlayback: Boolean,
    ): Int

    @JvmStatic external fun nReadVideoFrame(
        handle: Long,
        outResult: IntArray,
    ): ByteBuffer?

    @JvmStatic external fun nUnlockVideoFrame(handle: Long): Int

    @JvmStatic external fun nCloseMedia(handle: Long)

    @JvmStatic external fun nIsEOF(handle: Long): Boolean

    @JvmStatic external fun nGetVideoSize(
        handle: Long,
        outSize: IntArray,
    )

    @JvmStatic external fun nGetVideoFrameRate(
        handle: Long,
        outRate: IntArray,
    ): Int

    @JvmStatic external fun nSeekMedia(
        handle: Long,
        position: Long,
    ): Int

    @JvmStatic external fun nGetMediaDuration(
        handle: Long,
        outDuration: LongArray,
    ): Int

    @JvmStatic external fun nGetMediaPosition(
        handle: Long,
        outPosition: LongArray,
    ): Int

    @JvmStatic external fun nSetPlaybackState(
        handle: Long,
        isPlaying: Boolean,
        stop: Boolean,
    ): Int

    @JvmStatic external fun nShutdownMediaFoundation(): Int

    @JvmStatic external fun nSetAudioVolume(
        handle: Long,
        volume: Float,
    ): Int

    @JvmStatic external fun nGetAudioVolume(
        handle: Long,
        outVolume: FloatArray,
    ): Int

    @JvmStatic external fun nGetAudioLevels(
        handle: Long,
        outLevels: FloatArray,
    ): Int

    @JvmStatic external fun nSetPlaybackSpeed(
        handle: Long,
        speed: Float,
    ): Int

    @JvmStatic external fun nGetPlaybackSpeed(
        handle: Long,
        outSpeed: FloatArray,
    ): Int

    @JvmStatic external fun nWrapPointer(
        address: Long,
        size: Long,
    ): ByteBuffer?

    @JvmStatic external fun nSetOutputSize(
        handle: Long,
        width: Int,
        height: Int,
    ): Int

    @JvmStatic private external fun nGetVideoMetadata(
        handle: Long,
        title: CharArray,
        mimeType: CharArray,
        longVals: LongArray,
        intVals: IntArray,
        floatVals: FloatArray,
        hasFlags: BooleanArray,
    ): Int

    // ----- Convenience wrappers (keep old API names for minimal caller changes) -----

    fun InitMediaFoundation(): Int = nInitMediaFoundation()

    fun ShutdownMediaFoundation(): Int = nShutdownMediaFoundation()

    fun OpenMedia(
        handle: Long,
        url: String,
        startPlayback: Boolean,
    ): Int = nOpenMedia(handle, url, startPlayback)

    fun CloseMedia(handle: Long) = nCloseMedia(handle)

    fun IsEOF(handle: Long): Boolean = nIsEOF(handle)

    fun UnlockVideoFrame(handle: Long): Int = nUnlockVideoFrame(handle)

    fun SeekMedia(
        handle: Long,
        position: Long,
    ): Int = nSeekMedia(handle, position)

    fun SetPlaybackState(
        handle: Long,
        isPlaying: Boolean,
        stop: Boolean,
    ): Int = nSetPlaybackState(handle, isPlaying, stop)

    fun SetAudioVolume(
        handle: Long,
        volume: Float,
    ): Int = nSetAudioVolume(handle, volume)

    fun SetPlaybackSpeed(
        handle: Long,
        speed: Float,
    ): Int = nSetPlaybackSpeed(handle, speed)

    fun ReadVideoFrame(
        handle: Long,
        outResult: IntArray,
    ): ByteBuffer? = nReadVideoFrame(handle, outResult)

    fun GetVideoSize(
        handle: Long,
        outSize: IntArray,
    ) = nGetVideoSize(handle, outSize)

    fun GetMediaDuration(
        handle: Long,
        outDuration: LongArray,
    ): Int = nGetMediaDuration(handle, outDuration)

    fun GetMediaPosition(
        handle: Long,
        outPosition: LongArray,
    ): Int = nGetMediaPosition(handle, outPosition)

    fun GetAudioVolume(
        handle: Long,
        outVolume: FloatArray,
    ): Int = nGetAudioVolume(handle, outVolume)

    fun GetAudioLevels(
        handle: Long,
        outLevels: FloatArray,
    ): Int = nGetAudioLevels(handle, outLevels)

    fun GetPlaybackSpeed(
        handle: Long,
        outSpeed: FloatArray,
    ): Int = nGetPlaybackSpeed(handle, outSpeed)

    fun SetOutputSize(
        handle: Long,
        width: Int,
        height: Int,
    ): Int = nSetOutputSize(handle, width, height)
}
