package io.github.kdroidfilter.composemediaplayer.mac

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MacFrameUtilsTest {
    @Test
    fun calculateFrameHash_returnsZeroWhenEmpty() {
        assertEquals(0, calculateFrameHash(ByteBuffer.allocate(0), 0, 0, 0))
        assertEquals(0, calculateFrameHash(ByteBuffer.allocate(0), -1, 1, 0))
        assertEquals(0, calculateFrameHash(ByteBuffer.allocate(0), 1, -1, 0))
    }

    @Test
    fun calculateFrameHash_changesWhenSampledPixelChanges() {
        val width = 100
        val height = 10
        val rowBytes = width * 4
        val buf = ByteBuffer.allocate(rowBytes * height)
        for (i in 0 until width * height) {
            buf.putInt(i * 4, i)
        }

        val hash1 = calculateFrameHash(buf, width, height, rowBytes)

        // With pixelCount=1000, step=5 => linear index 5 (x=5, y=0) is sampled.
        buf.putInt(5 * 4, 123456)
        val hash2 = calculateFrameHash(buf, width, height, rowBytes)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun calculateFrameHash_ignoresRowPaddingBytes() {
        val width = 100
        val height = 10
        val rowBytes = width * 4 + 16 // padded stride

        fun frame(padding: Byte): ByteBuffer {
            val buf = ByteBuffer.allocate(rowBytes * height)
            for (i in 0 until buf.capacity()) buf.put(i, padding)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    buf.putInt(y * rowBytes + x * 4, y * width + x)
                }
            }
            return buf
        }

        // Same pixels, different garbage in the padding — hashes must match.
        assertEquals(
            calculateFrameHash(frame(0x00), width, height, rowBytes),
            calculateFrameHash(frame(0x55), width, height, rowBytes),
        )
    }

    @Test
    fun calculateFrameHash_doesNotSampleSingleColumnWhenStepIsMultipleOfWidth() {
        // pixelCount=4000 => raw step 20, a multiple of width: naive sampling would only
        // ever read column x=0 and miss every other change in the frame.
        val width = 10
        val height = 400
        val rowBytes = width * 4

        val blank = ByteBuffer.allocate(rowBytes * height)
        val changedOutsideFirstColumn = ByteBuffer.allocate(rowBytes * height)
        for (y in 0 until height) {
            for (x in 1 until width) {
                changedOutsideFirstColumn.putInt(y * rowBytes + x * 4, 0xCAFE)
            }
        }

        assertNotEquals(
            calculateFrameHash(blank, width, height, rowBytes),
            calculateFrameHash(changedOutsideFirstColumn, width, height, rowBytes),
        )
    }

    @Test
    fun copyBgraFrame_copiesContiguousRows() {
        val width = 2
        val height = 2
        val rowBytes = width * 4
        val size = rowBytes * height

        val src = ByteBuffer.allocate(size)
        for (i in 0 until size) {
            src.put(i, i.toByte())
        }
        val dst = ByteBuffer.allocate(size)

        copyBgraFrame(src, dst, width, height, rowBytes, rowBytes)

        for (i in 0 until size) {
            assertEquals(src.get(i), dst.get(i), "Mismatch at byte index $i")
        }
    }

    @Test
    fun copyBgraFrame_copiesWithRowPadding() {
        val width = 2
        val height = 2
        val srcRowBytes = width * 4
        val dstRowBytes = srcRowBytes + 4

        val srcSize = srcRowBytes * height
        val dstSize = dstRowBytes * height

        val src = ByteBuffer.allocate(srcSize)
        for (i in 0 until srcSize) {
            src.put(i, (i + 1).toByte())
        }

        val dst = ByteBuffer.allocate(dstSize)
        val paddingSentinel = 0x7F.toByte()
        for (i in 0 until dstSize) {
            dst.put(i, paddingSentinel)
        }

        copyBgraFrame(src, dst, width, height, srcRowBytes, dstRowBytes)

        for (row in 0 until height) {
            val srcBase = row * srcRowBytes
            val dstBase = row * dstRowBytes

            for (i in 0 until srcRowBytes) {
                assertEquals(
                    src.get(srcBase + i),
                    dst.get(dstBase + i),
                    "Row $row mismatch at byte index $i",
                )
            }

            for (i in srcRowBytes until dstRowBytes) {
                assertEquals(
                    paddingSentinel,
                    dst.get(dstBase + i),
                    "Row $row padding byte $i should be untouched",
                )
            }
        }
    }

    @Test
    fun copyBgraFrame_requiresValidRowBytes() {
        val width = 2
        val height = 1
        val srcRowBytes = width * 4
        val dstRowBytes = srcRowBytes - 1

        val src = ByteBuffer.allocate(srcRowBytes * height)
        val dst = ByteBuffer.allocate(dstRowBytes * height)

        assertFailsWith<IllegalArgumentException> {
            copyBgraFrame(src, dst, width, height, srcRowBytes, dstRowBytes)
        }
    }
}
