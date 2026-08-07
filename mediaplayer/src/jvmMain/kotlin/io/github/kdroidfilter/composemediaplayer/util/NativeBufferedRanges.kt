package io.github.kdroidfilter.composemediaplayer.util

import io.github.kdroidfilter.composemediaplayer.BufferedRange

/**
 * Maximum number of buffered ranges the desktop backends read from their native players.
 *
 * Players usually report one or two ranges; the cap only exists to keep the exchange buffer at a
 * fixed size, and extra ranges are dropped by the native side.
 */
internal const val MAX_NATIVE_BUFFERED_RANGES = 16

/** Size of the `DoubleArray` a native `nGetBufferedRanges` call expects. */
internal const val NATIVE_BUFFERED_RANGES_ARRAY_SIZE = MAX_NATIVE_BUFFERED_RANGES * 2

/**
 * Decodes the flat `[start0, end0, start1, end1, ...]` layout the native players fill in.
 *
 * @param buffer the array handed to the native call
 * @param count the number of ranges the native call reported writing
 */
internal fun decodeNativeBufferedRanges(
    buffer: DoubleArray,
    count: Int,
): List<BufferedRange> {
    if (count <= 0) return emptyList()
    val usable = minOf(count, buffer.size / 2)
    return (0 until usable).map { index ->
        BufferedRange(buffer[index * 2], buffer[index * 2 + 1])
    }
}
