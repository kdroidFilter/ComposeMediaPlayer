package io.github.kdroidfilter.composemediaplayer.windows

import java.nio.ByteBuffer

/**
 * Calculates a fast hash of the frame buffer to detect frame changes.
 * Samples approximately 200 pixels evenly distributed across the frame.
 *
 * @param buffer The source buffer containing BGRA pixel data
 * @param pixelCount Total number of pixels in the frame
 * @return A hash value representing the frame content
 */
internal fun calculateFrameHash(buffer: ByteBuffer, pixelCount: Int): Int {
    if (pixelCount <= 0) return 0

    var hash = 1
    val step = if (pixelCount <= 200) 1 else pixelCount / 200
    for (i in 0 until pixelCount step step) {
        hash = 31 * hash + buffer.getInt(i * 4)
    }
    return hash
}

/**
 * Copies BGRA frame data from source to destination buffer with minimal overhead.
 * Handles row padding when destination stride differs from source.
 *
 * This function performs a single memory copy operation when strides match,
 * achieving zero-copy performance (beyond the necessary single copy from
 * native buffer to Skia bitmap).
 *
 * @param src Source buffer containing BGRA pixel data from Media Foundation
 * @param dst Destination buffer (Skia bitmap pixels via peekPixels)
 * @param width Frame width in pixels
 * @param height Frame height in pixels
 * @param dstRowBytes Destination row stride (may include padding)
 */
internal fun copyBgraFrame(
    src: ByteBuffer,
    dst: ByteBuffer,
    width: Int,
    height: Int,
    dstRowBytes: Int,
) {
    require(width > 0) { "width must be > 0 (was $width)" }
    require(height > 0) { "height must be > 0 (was $height)" }
    val srcRowBytes = width * 4
    require(dstRowBytes >= srcRowBytes) {
        "dstRowBytes ($dstRowBytes) must be >= srcRowBytes ($srcRowBytes)"
    }

    val requiredSrcBytes = srcRowBytes.toLong() * height.toLong()
    val requiredDstBytes = dstRowBytes.toLong() * height.toLong()
    require(src.capacity().toLong() >= requiredSrcBytes) {
        "src buffer too small: ${src.capacity()} < $requiredSrcBytes"
    }
    require(dst.capacity().toLong() >= requiredDstBytes) {
        "dst buffer too small: ${dst.capacity()} < $requiredDstBytes"
    }

    val srcBuf = src.duplicate()
    val dstBuf = dst.duplicate()
    srcBuf.rewind()
    dstBuf.rewind()

    // Fast path: when strides match, do a single bulk copy
    if (dstRowBytes == srcRowBytes) {
        srcBuf.limit(requiredSrcBytes.toInt())
        dstBuf.limit(requiredSrcBytes.toInt())
        dstBuf.put(srcBuf)
        return
    }

    // Slow path: copy row by row when there's padding
    val srcCapacity = srcBuf.capacity()
    val dstCapacity = dstBuf.capacity()
    for (row in 0 until height) {
        val srcPos = row * srcRowBytes
        srcBuf.limit(srcCapacity)
        srcBuf.position(srcPos)
        srcBuf.limit(srcPos + srcRowBytes)

        val dstPos = row * dstRowBytes
        dstBuf.limit(dstCapacity)
        dstBuf.position(dstPos)
        dstBuf.limit(dstPos + srcRowBytes)

        dstBuf.put(srcBuf)
    }
}
