package io.github.kdroidfilter.composemediaplayer.common

import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for the SubtitleTrack class
 */
class SubtitleTrackTest {
    
    /**
     * Test the creation of SubtitleTrack
     */
    @Test
    fun testCreateSubtitleTrack() {
        val track = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/english.vtt"
        )
        
        // Verify the track is initialized correctly
        assertNotNull(track)
        assertEquals("English", track.label)
        assertEquals("en", track.language)
        assertEquals("subtitles/english.vtt", track.src)
    }
    
    /**
     * Test data class copy functionality
     */
    @Test
    fun testSubtitleTrackCopy() {
        val track = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/english.vtt"
        )
        
        // Create a copy with some modified properties
        val copy = track.copy(
            label = "English (US)",
            src = "subtitles/english_us.vtt"
        )
        
        // Verify the original track is unchanged
        assertEquals("English", track.label)
        assertEquals("en", track.language)
        assertEquals("subtitles/english.vtt", track.src)
        
        // Verify the copy has the expected properties
        assertEquals("English (US)", copy.label)
        assertEquals("en", copy.language)
        assertEquals("subtitles/english_us.vtt", copy.src)
    }
    
    /**
     * Test data class equality
     */
    @Test
    fun testSubtitleTrackEquality() {
        val track1 = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/english.vtt"
        )
        
        val track2 = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/english.vtt"
        )
        
        val track3 = SubtitleTrack(
            label = "French",
            language = "fr",
            src = "subtitles/french.vtt"
        )
        
        // Verify equality works as expected
        assertEquals(track1, track2)
        assertEquals(track1.hashCode(), track2.hashCode())
        
        // Verify inequality works as expected
        assert(track1 != track3)
        assert(track1.hashCode() != track3.hashCode())
    }
}