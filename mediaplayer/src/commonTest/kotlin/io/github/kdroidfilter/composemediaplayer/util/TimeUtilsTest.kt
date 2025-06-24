package io.github.kdroidfilter.composemediaplayer.util

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeUtilsTest {
    
    @Test
    fun testFormatTimeWithSeconds() {
        // Test with seconds (as Double)
        assertEquals("00:00", formatTime(0.0))
        assertEquals("00:01", formatTime(1.0))
        assertEquals("00:59", formatTime(59.0))
        assertEquals("01:00", formatTime(60.0))
        assertEquals("01:01", formatTime(61.0))
        assertEquals("59:59", formatTime(3599.0))
        assertEquals("01:00:00", formatTime(3600.0))
        assertEquals("01:00:01", formatTime(3601.0))
        assertEquals("01:01:01", formatTime(3661.0))
        assertEquals("99:59:59", formatTime(359999.0))
    }
    
    @Test
    fun testFormatTimeWithNanoseconds() {
        // Test with nanoseconds (as Long)
        assertEquals("00:00", formatTime(0L, true))
        assertEquals("00:01", formatTime(1_000_000_000L, true))
        assertEquals("00:59", formatTime(59_000_000_000L, true))
        assertEquals("01:00", formatTime(60_000_000_000L, true))
        assertEquals("01:01", formatTime(61_000_000_000L, true))
        assertEquals("59:59", formatTime(3599_000_000_000L, true))
        assertEquals("01:00:00", formatTime(3600_000_000_000L, true))
        assertEquals("01:00:01", formatTime(3601_000_000_000L, true))
        assertEquals("01:01:01", formatTime(3661_000_000_000L, true))
    }
    
    @Test
    fun testToTimeMs() {
        // Test conversion from time string to milliseconds
        assertEquals(0, "00:00".toTimeMs())
        assertEquals(1000, "00:01".toTimeMs())
        assertEquals(59000, "00:59".toTimeMs())
        assertEquals(60000, "01:00".toTimeMs())
        assertEquals(61000, "01:01".toTimeMs())
        assertEquals(3599000, "59:59".toTimeMs())
        assertEquals(3600000, "01:00:00".toTimeMs())
        assertEquals(3601000, "01:00:01".toTimeMs())
        assertEquals(3661000, "01:01:01".toTimeMs())
        
        // Test invalid formats
        assertEquals(0, "invalid".toTimeMs())
        assertEquals(0, "".toTimeMs())
        assertEquals(0, ":".toTimeMs())
        assertEquals(0, "::".toTimeMs())
    }
    
    @Test
    fun testRoundTrip() {
        // Test that converting from seconds to string and back to milliseconds works correctly
        val testSeconds = listOf(0.0, 1.0, 59.0, 60.0, 61.0, 3599.0, 3600.0, 3601.0, 3661.0)
        
        for (seconds in testSeconds) {
            val formatted = formatTime(seconds)
            val milliseconds = formatted.toTimeMs()
            
            // Allow for small rounding differences due to floating point
            val expectedMs = (seconds * 1000).toLong()
            val tolerance = 1000L // 1 second tolerance due to rounding to whole seconds in formatTime
            
            assertEquals(
                true, 
                kotlin.math.abs(expectedMs - milliseconds) <= tolerance,
                "Round trip conversion failed for $seconds seconds. " +
                "Expected ~$expectedMs ms, got $milliseconds ms (formatted as $formatted)"
            )
        }
    }
}