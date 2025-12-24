package io.github.kdroidfilter.composemediaplayer.mac

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MacFrameUtilsTest {
    @Test
    fun calculateFrameHash_returnsZeroWhenEmpty() {
        assertEquals(0, calculateFrameHash(ByteBuffer.allocate(0), 0))
        assertEquals(0, calculateFrameHash(ByteBuffer.allocate(0), -1))
    }

    @Test
    fun calculateFrameHash_changesWhenSampledPixelChanges() {
        val pixelCount = 1000
        val buf = ByteBuffer.allocate(pixelCount * 4)
        for (i in 0 until pixelCount) {
            buf.putInt(i * 4, i)
        }

        val hash1 = calculateFrameHash(buf, pixelCount)

        // With pixelCount=1000, step=5 => index 5 is sampled.
        buf.putInt(5 * 4, 123456)
        val hash2 = calculateFrameHash(buf, pixelCount)

        assertNotEquals(hash1, hash2)
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

        copyBgraFrame(src, dst, width, height, rowBytes)

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

        copyBgraFrame(src, dst, width, height, dstRowBytes)

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
            copyBgraFrame(src, dst, width, height, dstRowBytes)
        }
    }
}

