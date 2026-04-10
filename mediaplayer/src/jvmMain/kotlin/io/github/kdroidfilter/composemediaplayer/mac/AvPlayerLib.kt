package io.github.kdroidfilter.composemediaplayer.mac

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * JNI direct mapping to the native macOS video player library.
 * Handles are opaque Long values (native pointer cast to jlong, 0 = null).
 */
internal object SharedVideoPlayer {
    init {
        loadNativeLibrary()
    }

    private fun loadNativeLibrary() {
        val osArch = System.getProperty("os.arch", "").lowercase()
        val resourceDir =
            if (osArch == "aarch64" || osArch == "arm64") "darwin-aarch64" else "darwin-x86-64"
        val libName = "libNativeVideoPlayer.dylib"

        val stream = SharedVideoPlayer::class.java.getResourceAsStream("/$resourceDir/$libName")
            ?: throw UnsatisfiedLinkError(
                "Native library not found in resources: /$resourceDir/$libName"
            )

        val tempDir = Files.createTempDirectory("nativevideoplayer").toFile()
        val tempFile = File(tempDir, libName)
        stream.use { input -> tempFile.outputStream().use { input.copyTo(it) } }
        System.load(tempFile.absolutePath)
        tempFile.deleteOnExit()
        tempDir.deleteOnExit()
    }

    // Playback control
    @JvmStatic external fun nCreatePlayer(): Long
    @JvmStatic external fun nOpenUri(handle: Long, uri: String)
    @JvmStatic external fun nPlay(handle: Long)
    @JvmStatic external fun nPause(handle: Long)
    @JvmStatic external fun nSetVolume(handle: Long, volume: Float)
    @JvmStatic external fun nGetVolume(handle: Long): Float
    @JvmStatic external fun nSeekTo(handle: Long, time: Double)
    @JvmStatic external fun nDisposePlayer(handle: Long)
    @JvmStatic external fun nSetPlaybackSpeed(handle: Long, speed: Float)
    @JvmStatic external fun nGetPlaybackSpeed(handle: Long): Float

    // Frame access — lock/unlock CVPixelBuffer directly (zero intermediate copy)
    // outInfo must be IntArray(3); filled with [width, height, bytesPerRow] on success.
    // Returns the native base address of the locked buffer, or 0 on failure.
    // MUST call nUnlockFrame after reading.
    @JvmStatic external fun nLockFrame(handle: Long, outInfo: IntArray): Long
    @JvmStatic external fun nUnlockFrame(handle: Long)
    @JvmStatic external fun nWrapPointer(address: Long, size: Long): ByteBuffer?
    @JvmStatic external fun nGetFrameWidth(handle: Long): Int
    @JvmStatic external fun nGetFrameHeight(handle: Long): Int
    @JvmStatic external fun nSetOutputSize(handle: Long, width: Int, height: Int): Int

    // Timing / rate info
    @JvmStatic external fun nGetVideoFrameRate(handle: Long): Float
    @JvmStatic external fun nGetScreenRefreshRate(handle: Long): Float
    @JvmStatic external fun nGetCaptureFrameRate(handle: Long): Float
    @JvmStatic external fun nGetVideoDuration(handle: Long): Double
    @JvmStatic external fun nGetCurrentTime(handle: Long): Double

    // Audio levels
    @JvmStatic external fun nGetLeftAudioLevel(handle: Long): Float
    @JvmStatic external fun nGetRightAudioLevel(handle: Long): Float

    // Metadata
    @JvmStatic external fun nGetVideoTitle(handle: Long): String?
    @JvmStatic external fun nGetVideoBitrate(handle: Long): Long
    @JvmStatic external fun nGetVideoMimeType(handle: Long): String?
    @JvmStatic external fun nGetAudioChannels(handle: Long): Int
    @JvmStatic external fun nGetAudioSampleRate(handle: Long): Int

    // Playback completion
    @JvmStatic external fun nConsumeDidPlayToEnd(handle: Long): Boolean
}
