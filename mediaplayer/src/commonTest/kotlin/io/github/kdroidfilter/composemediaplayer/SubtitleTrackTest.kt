package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubtitleTrackTest {

    @Test
    fun testSubtitleTrackCreation() {
        val track = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/en.vtt"
        )

        assertEquals("English", track.label)
        assertEquals("en", track.language)
        assertEquals("subtitles/en.vtt", track.src)
    }

    @Test
    fun testSubtitleTrackEquality() {
        val track1 = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/en.vtt"
        )

        val track2 = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/en.vtt"
        )

        val track3 = SubtitleTrack(
            label = "French",
            language = "fr",
            src = "subtitles/fr.vtt"
        )

        assertEquals(track1, track2, "Identical subtitle tracks should be equal")
        assertNotEquals(track1, track3, "Different subtitle tracks should not be equal")
    }

    @Test
    fun testSubtitleTrackCopy() {
        val original = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/en.vtt"
        )

        val copy = original.copy(label = "English (US)")

        assertEquals("English (US)", copy.label)
        assertEquals(original.language, copy.language)
        assertEquals(original.src, copy.src)

        // Original should remain unchanged
        assertEquals("English", original.label)
    }

    @Test
    fun testSubtitleTrackToString() {
        val track = SubtitleTrack(
            label = "English",
            language = "en",
            src = "subtitles/en.vtt"
        )

        val toString = track.toString()

        // Verify that toString contains all the properties
        assertTrue(toString.contains("English"))
        assertTrue(toString.contains("en"))
        assertTrue(toString.contains("subtitles/en.vtt"))
    }
}
