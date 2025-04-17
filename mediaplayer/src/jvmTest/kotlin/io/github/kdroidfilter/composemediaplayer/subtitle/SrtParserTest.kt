package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the SrtParser class
 */
class SrtParserTest {

    /**
     * Test parsing a simple SRT subtitle file
     */
    @Test
    fun testParseSrtContent() {
        // Sample SRT content
        val srtContent = """
            1
            00:00:01,000 --> 00:00:04,000
            This is the first subtitle

            2
            00:00:05,500 --> 00:00:07,500
            This is the second subtitle

            3
            00:01:00,000 --> 00:01:30,000
            This is the third subtitle
            with multiple lines
        """.trimIndent()

        val subtitles = SrtParser.parse(srtContent)
        
        // Verify the parsed subtitles
        assertNotNull(subtitles)
        assertEquals(3, subtitles.cues.size, "Should parse 3 subtitle cues")
        
        // Check first subtitle
        assertEquals(1000, subtitles.cues[0].startTime, "First subtitle should start at 1000ms")
        assertEquals(4000, subtitles.cues[0].endTime, "First subtitle should end at 4000ms")
        assertEquals("This is the first subtitle", subtitles.cues[0].text)
        
        // Check second subtitle
        assertEquals(5500, subtitles.cues[1].startTime, "Second subtitle should start at 5500ms")
        assertEquals(7500, subtitles.cues[1].endTime, "Second subtitle should end at 7500ms")
        assertEquals("This is the second subtitle", subtitles.cues[1].text)
        
        // Check third subtitle (with multiple lines)
        assertEquals(60000, subtitles.cues[2].startTime, "Third subtitle should start at 60000ms")
        assertEquals(90000, subtitles.cues[2].endTime, "Third subtitle should end at 90000ms")
        assertEquals("This is the third subtitle\nwith multiple lines", subtitles.cues[2].text)
    }

    /**
     * Test parsing an SRT file with some invalid entries
     */
    @Test
    fun testParseInvalidSrtContent() {
        // Sample SRT content with some invalid entries
        val srtContent = """
            Invalid line
            
            1
            00:00:01,000 --> 00:00:04,000
            Valid subtitle
            
            Not a sequence number
            00:00:05,500 --> 00:00:07,500
            This should be skipped
            
            2
            Invalid timing line
            This should be skipped
            
            3
            00:01:00,000 --> 00:01:30,000
            Valid subtitle again
        """.trimIndent()

        val subtitles = SrtParser.parse(srtContent)
        
        // Verify the parsed subtitles
        assertNotNull(subtitles)
        assertEquals(2, subtitles.cues.size, "Should parse only 2 valid subtitle cues")
        
        // Check first valid subtitle
        assertEquals("Valid subtitle", subtitles.cues[0].text)
        
        // Check second valid subtitle
        assertEquals("Valid subtitle again", subtitles.cues[1].text)
    }

    /**
     * Test the active cues functionality
     */
    @Test
    fun testActiveCues() {
        // Sample SRT content
        val srtContent = """
            1
            00:00:01,000 --> 00:00:04,000
            First subtitle
            
            2
            00:00:05,000 --> 00:00:08,000
            Second subtitle
        """.trimIndent()

        val subtitles = SrtParser.parse(srtContent)
        
        // Test active cues at different times
        val activeCuesAt500ms = subtitles.getActiveCues(500)
        assertEquals(0, activeCuesAt500ms.size, "No subtitles should be active at 500ms")
        
        val activeCuesAt2000ms = subtitles.getActiveCues(2000)
        assertEquals(1, activeCuesAt2000ms.size, "One subtitle should be active at 2000ms")
        assertEquals("First subtitle", activeCuesAt2000ms[0].text)
        
        val activeCuesAt4500ms = subtitles.getActiveCues(4500)
        assertEquals(0, activeCuesAt4500ms.size, "No subtitles should be active at 4500ms")
        
        val activeCuesAt6000ms = subtitles.getActiveCues(6000)
        assertEquals(1, activeCuesAt6000ms.size, "One subtitle should be active at 6000ms")
        assertEquals("Second subtitle", activeCuesAt6000ms[0].text)
    }
}