package io.github.kdroidfilter.composemediaplayer.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import io.github.kdroidfilter.composemediaplayer.VideoMetadata

internal interface MediaFoundationLib : StdCallLibrary {
    /**
     * JNA structure that maps to the C++ VideoMetadata structure
     */
    @Structure.FieldOrder(
        "title", "duration", "width", "height", "bitrate", "frameRate", "mimeType", 
        "audioChannels", "audioSampleRate", "hasTitle", "hasDuration", "hasWidth", 
        "hasHeight", "hasBitrate", "hasFrameRate", "hasMimeType", "hasAudioChannels", 
        "hasAudioSampleRate"
    )
    class NativeVideoMetadata : Structure() {
        @JvmField var title = CharArray(256)
        @JvmField var duration: Long = 0
        @JvmField var width: Int = 0
        @JvmField var height: Int = 0
        @JvmField var bitrate: Long = 0
        @JvmField var frameRate: Float = 0f
        @JvmField var mimeType = CharArray(64)
        @JvmField var audioChannels: Int = 0
        @JvmField var audioSampleRate: Int = 0
        @JvmField var hasTitle: Boolean = false
        @JvmField var hasDuration: Boolean = false
        @JvmField var hasWidth: Boolean = false
        @JvmField var hasHeight: Boolean = false
        @JvmField var hasBitrate: Boolean = false
        @JvmField var hasFrameRate: Boolean = false
        @JvmField var hasMimeType: Boolean = false
        @JvmField var hasAudioChannels: Boolean = false
        @JvmField var hasAudioSampleRate: Boolean = false

        /**
         * Converts this native structure to a Kotlin VideoMetadata object
         */
        fun toVideoMetadata(): VideoMetadata {
            return VideoMetadata(
                title = if (hasTitle) String(title).trim { it <= ' ' || it == '\u0000' } else null,
                duration = if (hasDuration) duration / 10000 else null, // Convert from 100ns to ms
                width = if (hasWidth) width else null,
                height = if (hasHeight) height else null,
                bitrate = if (hasBitrate) bitrate else null,
                frameRate = if (hasFrameRate) frameRate else null,
                mimeType = if (hasMimeType) String(mimeType).trim { it <= ' ' || it == '\u0000' } else null,
                audioChannels = if (hasAudioChannels) audioChannels else null,
                audioSampleRate = if (hasAudioSampleRate) audioSampleRate else null
            )
        }
    }

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

        /**
         * Retrieves metadata for the current media
         * @param instance Pointer to the native instance
         * @return VideoMetadata object containing all available metadata, or null if retrieval failed
         */
        fun getVideoMetadata(instance: Pointer): VideoMetadata? {
            val metadata = NativeVideoMetadata()
            val hr = INSTANCE.GetVideoMetadata(instance, metadata)
            return if (hr >= 0) metadata.toVideoMetadata() else null
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
    fun SetPlaybackState(pInstance: Pointer, isPlaying: Boolean, bStop: Boolean = false): Int
    fun ShutdownMediaFoundation(): Int
    fun SetAudioVolume(pInstance: Pointer, volume: Float): Int
    fun GetAudioVolume(pInstance: Pointer, volume: FloatByReference): Int
    fun GetAudioLevels(pInstance: Pointer, pLeftLevel: FloatByReference, pRightLevel: FloatByReference): Int
    fun SetPlaybackSpeed(pInstance: Pointer, speed: Float): Int
    fun GetPlaybackSpeed(pInstance: Pointer, pSpeed: FloatByReference): Int

    /**
     * Retrieves all available metadata for the current media
     * @param pInstance Pointer to the native instance
     * @param pMetadata Pointer to receive the metadata structure
     * @return S_OK on success, or an error code
     */
    fun GetVideoMetadata(pInstance: Pointer, pMetadata: NativeVideoMetadata): Int
}
