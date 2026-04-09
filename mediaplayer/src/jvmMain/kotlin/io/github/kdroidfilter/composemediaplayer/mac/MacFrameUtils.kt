package io.github.kdroidfilter.composemediaplayer.mac

import java.nio.ByteBuffer

internal fun calculateFrameHash(buffer: ByteBuffer, pixelCount: Int): Int {
    if (pixelCount <= 0) return 0

    var hash = 1
    val step = if (pixelCount <= 200) 1 else pixelCount / 200
    for (i in 0 until pixelCount step step) {
        hash = 31 * hash + buffer.getInt(i * 4)
    }
    return hash
}

internal fun copyBgraFrame(
    src: ByteBuffer,
    dst: ByteBuffer,
    width: Int,
    height: Int,
    srcBytesPerRow: Int,
    dstRowBytes: Int,
) {
    require(width > 0) { "width must be > 0 (was $width)" }
    require(height > 0) { "height must be > 0 (was $height)" }
    val pixelRowBytes = width * 4
    require(srcBytesPerRow >= pixelRowBytes) {
        "srcBytesPerRow ($srcBytesPerRow) must be >= pixelRowBytes ($pixelRowBytes)"
    }
    require(dstRowBytes >= pixelRowBytes) {
        "dstRowBytes ($dstRowBytes) must be >= pixelRowBytes ($pixelRowBytes)"
    }

    val requiredSrcBytes = srcBytesPerRow.toLong() * height.toLong()
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

    // Fast path: both buffers have the same layout — single bulk copy
    if (srcBytesPerRow == pixelRowBytes && dstRowBytes == pixelRowBytes) {
        val totalBytes = pixelRowBytes.toLong() * height.toLong()
        srcBuf.limit(totalBytes.toInt())
        dstBuf.limit(totalBytes.toInt())
        dstBuf.put(srcBuf)
        return
    }

    // Slow path: different strides — copy row by row
    val srcCapacity = srcBuf.capacity()
    val dstCapacity = dstBuf.capacity()
    for (row in 0 until height) {
        val srcPos = row * srcBytesPerRow
        srcBuf.limit(srcCapacity)
        srcBuf.position(srcPos)
        srcBuf.limit(srcPos + pixelRowBytes)

        val dstPos = row * dstRowBytes
        dstBuf.limit(dstCapacity)
        dstBuf.position(dstPos)
        dstBuf.limit(dstPos + pixelRowBytes)

        dstBuf.put(srcBuf)
    }
}
