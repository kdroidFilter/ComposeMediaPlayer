package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BufferedRangeTest {
    @Test
    fun testDurationIsNeverNegative() {
        assertEquals(5.0, BufferedRange(10.0, 15.0).duration)
        assertEquals(0.0, BufferedRange(15.0, 10.0).duration)
    }

    @Test
    fun testContains() {
        val range = BufferedRange(10.0, 20.0)
        assertTrue(10.0 in range)
        assertTrue(15.0 in range)
        assertTrue(20.0 in range)
        assertFalse(9.9 in range)
        assertFalse(20.1 in range)
    }

    @Test
    fun testBufferedEndAt() {
        val ranges = listOf(BufferedRange(0.0, 30.0), BufferedRange(60.0, 90.0))

        assertEquals(30.0, ranges.bufferedEndAt(10.0))
        assertEquals(90.0, ranges.bufferedEndAt(70.0))
        // Nothing buffered ahead of a position that falls in the gap
        assertEquals(45.0, ranges.bufferedEndAt(45.0))
        assertEquals(5.0, emptyList<BufferedRange>().bufferedEndAt(5.0))
    }

    @Test
    fun testBufferedPercentageAt() {
        val ranges = listOf(BufferedRange(0.0, 30.0))

        assertEquals(30f, ranges.bufferedPercentageAt(10.0, 100.0))
        // Position outside any range -> only the played part counts
        assertEquals(50f, ranges.bufferedPercentageAt(50.0, 100.0))
        // Unknown duration
        assertEquals(0f, ranges.bufferedPercentageAt(10.0, 0.0))
        assertEquals(0f, ranges.bufferedPercentageAt(10.0, Double.NaN))
    }

    @Test
    fun testBufferedPercentageIsClamped() {
        // A player may report a range slightly past the announced duration
        val ranges = listOf(BufferedRange(0.0, 105.0))
        assertEquals(100f, ranges.bufferedPercentageAt(10.0, 100.0))
    }

    @Test
    fun testNormalizeSortsAndMergesRanges() {
        val ranges =
            normalizeBufferedRanges(
                listOf(
                    BufferedRange(60.0, 90.0),
                    BufferedRange(0.0, 30.0),
                    BufferedRange(25.0, 40.0),
                ),
            )

        assertEquals(listOf(BufferedRange(0.0, 40.0), BufferedRange(60.0, 90.0)), ranges)
    }

    @Test
    fun testNormalizeMergesTouchingRanges() {
        val ranges = normalizeBufferedRanges(listOf(BufferedRange(0.0, 30.0), BufferedRange(30.0, 45.0)))
        assertEquals(listOf(BufferedRange(0.0, 45.0)), ranges)
    }

    @Test
    fun testNormalizeDropsEmptyAndNonFiniteRanges() {
        val ranges =
            normalizeBufferedRanges(
                listOf(
                    BufferedRange(10.0, 10.0),
                    BufferedRange(20.0, 15.0),
                    BufferedRange(Double.NaN, 5.0),
                    BufferedRange(0.0, Double.POSITIVE_INFINITY),
                    BufferedRange(5.0, 8.0),
                ),
            )

        assertEquals(listOf(BufferedRange(5.0, 8.0)), ranges)
    }

    @Test
    fun testNormalizeClampsToDuration() {
        val ranges = normalizeBufferedRanges(listOf(BufferedRange(-5.0, 120.0)), durationSeconds = 100.0)
        assertEquals(listOf(BufferedRange(0.0, 100.0)), ranges)
    }

    @Test
    fun testNormalizeWithoutDurationKeepsEnds() {
        val ranges = normalizeBufferedRanges(listOf(BufferedRange(0.0, 120.0)))
        assertEquals(listOf(BufferedRange(0.0, 120.0)), ranges)
    }

    @Test
    fun testDefaultStateReportsNoBuffering() {
        val state = PreviewableVideoPlayerState()
        assertFalse(state.isBufferedRangeSupported)
        assertEquals(emptyList(), state.bufferedRanges)
        assertEquals(0f, state.bufferedPercentage)
        assertEquals(0f, state.bufferedSliderPos)
    }

    @Test
    fun testBufferedSliderPosMatchesSliderScale() {
        val state =
            PreviewableVideoPlayerState(
                isBufferedRangeSupported = true,
                bufferedRanges = listOf(BufferedRange(0.0, 40.0)),
                bufferedPercentage = 40f,
            )
        assertEquals(400f, state.bufferedSliderPos)
    }
}
