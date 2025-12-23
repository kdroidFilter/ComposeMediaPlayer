package io.github.kdroidfilter.composemediaplayer.windows

import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.CharPointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.LongPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacpp.annotation.Cast
import org.bytedeco.javacpp.annotation.MemberGetter
import org.bytedeco.javacpp.annotation.Name
import org.bytedeco.javacpp.annotation.Opaque
import org.bytedeco.javacpp.annotation.Platform
import org.bytedeco.javacpp.annotation.Properties
import org.bytedeco.javacpp.presets.javacpp

@Properties(
    inherit = [javacpp::class],
    value = [
        Platform(
            value = ["windows"],
            library = "NativeVideoPlayer_jni",
            include = ["NativeVideoPlayer.h"],
            link = ["NativeVideoPlayer"],
            preload = ["NativeVideoPlayer"],
            resource = [
                "windows-x86_64/NativeVideoPlayer.dll",
                "windows-x86_64/NativeVideoPlayer_jni.dll",
            ],
        ),
    ],
)
internal object MediaFoundationLib {
    /**
     * Load the JavaCPP JNI wrapper and preload the native Media Foundation library.
     */
    init {
        Loader.load(MediaFoundationLib::class.java)
    }

    /**
     * Opaque handle for native player instances.
     */
    @Name("VideoPlayerInstance")
    @Opaque
    class VideoPlayerInstance : Pointer {
        constructor() : super()
        constructor(p: Pointer) : super(p)
    }

    /**
     * JavaCPP mapping of the native VideoMetadata structure.
     */
    @Name("VideoMetadata")
    class NativeVideoMetadata : Pointer {
        constructor() : super() { allocate() }
        constructor(p: Pointer) : super(p)

        private external fun allocate()

        @MemberGetter @Cast("wchar_t*") external fun title(): CharPointer
        @MemberGetter @Cast("LONGLONG") external fun duration(): Long
        @MemberGetter @Cast("UINT32") external fun width(): Int
        @MemberGetter @Cast("UINT32") external fun height(): Int
        @MemberGetter @Cast("LONGLONG") external fun bitrate(): Long
        @MemberGetter external fun frameRate(): Float
        @MemberGetter @Cast("wchar_t*") external fun mimeType(): CharPointer
        @MemberGetter @Cast("UINT32") external fun audioChannels(): Int
        @MemberGetter @Cast("UINT32") external fun audioSampleRate(): Int
        @MemberGetter @Cast("BOOL") external fun hasTitle(): Int
        @MemberGetter @Cast("BOOL") external fun hasDuration(): Int
        @MemberGetter @Cast("BOOL") external fun hasWidth(): Int
        @MemberGetter @Cast("BOOL") external fun hasHeight(): Int
        @MemberGetter @Cast("BOOL") external fun hasBitrate(): Int
        @MemberGetter @Cast("BOOL") external fun hasFrameRate(): Int
        @MemberGetter @Cast("BOOL") external fun hasMimeType(): Int
        @MemberGetter @Cast("BOOL") external fun hasAudioChannels(): Int
        @MemberGetter @Cast("BOOL") external fun hasAudioSampleRate(): Int

        fun toVideoMetadata(): VideoMetadata {
            return VideoMetadata(
                title = if (hasTitle() != 0) title().getString().trim { it <= ' ' || it == '\u0000' } else null,
                duration = if (hasDuration() != 0) duration() / 10000 else null, // 100ns to ms
                width = if (hasWidth() != 0) width() else null,
                height = if (hasHeight() != 0) height() else null,
                bitrate = if (hasBitrate() != 0) bitrate() else null,
                frameRate = if (hasFrameRate() != 0) frameRate() else null,
                mimeType = if (hasMimeType() != 0) mimeType().getString().trim { it <= ' ' || it == '\u0000' } else null,
                audioChannels = if (hasAudioChannels() != 0) audioChannels() else null,
                audioSampleRate = if (hasAudioSampleRate() != 0) audioSampleRate() else null,
            )
        }
    }

    fun createInstance(): VideoPlayerInstance? {
        val ptrRef = PointerPointer<VideoPlayerInstance>(1)
        val hr = CreateVideoPlayerInstance(ptrRef)
        val instance = ptrRef.get(VideoPlayerInstance::class.java, 0)
        return if (hr >= 0 && instance != null && !instance.isNull) instance else null
    }

    fun destroyInstance(instance: VideoPlayerInstance) {
        DestroyVideoPlayerInstance(instance)
    }

    fun getVideoMetadata(instance: VideoPlayerInstance): VideoMetadata? {
        val metadata = NativeVideoMetadata()
        val hr = GetVideoMetadata(instance, metadata)
        return if (hr >= 0) metadata.toVideoMetadata() else null
    }

    @JvmStatic external fun InitMediaFoundation(): Int
    @JvmStatic external fun CreateVideoPlayerInstance(
        @Cast("VideoPlayerInstance**") ppInstance: PointerPointer<VideoPlayerInstance>,
    ): Int
    @JvmStatic external fun DestroyVideoPlayerInstance(pInstance: VideoPlayerInstance)
    @JvmStatic external fun OpenMedia(
        pInstance: VideoPlayerInstance,
        @Cast("const wchar_t*") url: CharPointer,
        @Cast("BOOL") startPlayback: Int,
    ): Int

    @JvmStatic external fun ReadVideoFrame(
        pInstance: VideoPlayerInstance,
        @Cast("BYTE**") pData: PointerPointer<BytePointer>,
        @Cast("DWORD*") pDataSize: IntPointer,
    ): Int

    @JvmStatic external fun UnlockVideoFrame(pInstance: VideoPlayerInstance): Int
    @JvmStatic external fun CloseMedia(pInstance: VideoPlayerInstance)
    @JvmStatic @Cast("BOOL") external fun IsEOF(pInstance: VideoPlayerInstance): Int
    @JvmStatic external fun GetVideoSize(
        pInstance: VideoPlayerInstance,
        @Cast("UINT32*") pWidth: IntPointer,
        @Cast("UINT32*") pHeight: IntPointer,
    )
    @JvmStatic external fun GetVideoFrameRate(
        pInstance: VideoPlayerInstance,
        @Cast("UINT*") pNum: IntPointer,
        @Cast("UINT*") pDenom: IntPointer,
    ): Int
    @JvmStatic external fun SeekMedia(pInstance: VideoPlayerInstance, lPosition: Long): Int
    @JvmStatic external fun GetMediaDuration(pInstance: VideoPlayerInstance, pDuration: LongPointer): Int
    @JvmStatic external fun GetMediaPosition(pInstance: VideoPlayerInstance, pPosition: LongPointer): Int
    @JvmStatic external fun SetPlaybackState(
        pInstance: VideoPlayerInstance,
        @Cast("BOOL") isPlaying: Int,
        @Cast("BOOL") bStop: Int,
    ): Int

    @JvmStatic external fun ShutdownMediaFoundation(): Int
    @JvmStatic external fun SetAudioVolume(pInstance: VideoPlayerInstance, volume: Float): Int
    @JvmStatic external fun GetAudioVolume(pInstance: VideoPlayerInstance, volume: FloatPointer): Int
    @JvmStatic external fun GetAudioLevels(
        pInstance: VideoPlayerInstance,
        pLeftLevel: FloatPointer,
        pRightLevel: FloatPointer,
    ): Int

    @JvmStatic external fun SetPlaybackSpeed(pInstance: VideoPlayerInstance, speed: Float): Int
    @JvmStatic external fun GetPlaybackSpeed(pInstance: VideoPlayerInstance, pSpeed: FloatPointer): Int
    @JvmStatic external fun GetVideoMetadata(pInstance: VideoPlayerInstance, pMetadata: NativeVideoMetadata): Int
}
