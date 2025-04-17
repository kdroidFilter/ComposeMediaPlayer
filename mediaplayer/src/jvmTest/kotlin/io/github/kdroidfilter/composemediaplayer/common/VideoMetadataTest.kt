package io.github.kdroidfilter.composemediaplayer.common

import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the VideoMetadata class
 */
class VideoMetadataTest {

    /**
     * Test the creation of VideoMetadata with default values
     */
    @Test
    fun testCreateVideoMetadataWithDefaults() {
        val metadata = VideoMetadata()

        // Verify the metadata is initialized with null values
        assertNotNull(metadata)
        assertNull(metadata.title)
        assertNull(metadata.artist)
        assertNull(metadata.duration)
        assertNull(metadata.width)
        assertNull(metadata.height)
        assertNull(metadata.bitrate)
        assertNull(metadata.frameRate)
        assertNull(metadata.mimeType)
        assertNull(metadata.audioChannels)
        assertNull(metadata.audioSampleRate)
    }

    /**
     * Test the creation of VideoMetadata with specific values
     */
    @Test
    fun testCreateVideoMetadataWithValues() {
        val metadata = VideoMetadata(
            title = "Test Title",
            artist = "Test Artist",
            duration = 120000L,
            width = 1920,
            height = 1080,
            bitrate = 5000000L,
            frameRate = 30.0f,
            mimeType = "video/mp4",
            audioChannels = 2,
            audioSampleRate = 44100
        )

        // Verify the metadata properties
        assertEquals("Test Title", metadata.title)
        assertEquals("Test Artist", metadata.artist)
        assertEquals(120000L, metadata.duration)
        assertEquals(1920, metadata.width)
        assertEquals(1080, metadata.height)
        assertEquals(5000000L, metadata.bitrate)
        assertEquals(30.0f, metadata.frameRate)
        assertEquals("video/mp4", metadata.mimeType)
        assertEquals(2, metadata.audioChannels)
        assertEquals(44100, metadata.audioSampleRate)
    }

    /**
     * Test setting and getting metadata properties
     */
    @Test
    fun testMetadataProperties() {
        val metadata = VideoMetadata()

        // Set metadata properties
        metadata.title = "Test Title"
        metadata.artist = "Test Artist"
        metadata.duration = 120000L
        metadata.width = 1920
        metadata.height = 1080
        metadata.bitrate = 5000000L
        metadata.frameRate = 30.0f
        metadata.mimeType = "video/mp4"
        metadata.audioChannels = 2
        metadata.audioSampleRate = 44100

        // Verify the metadata properties
        assertEquals("Test Title", metadata.title)
        assertEquals("Test Artist", metadata.artist)
        assertEquals(120000L, metadata.duration)
        assertEquals(1920, metadata.width)
        assertEquals(1080, metadata.height)
        assertEquals(5000000L, metadata.bitrate)
        assertEquals(30.0f, metadata.frameRate)
        assertEquals("video/mp4", metadata.mimeType)
        assertEquals(2, metadata.audioChannels)
        assertEquals(44100, metadata.audioSampleRate)
    }

    /**
     * Test data class copy functionality
     */
    @Test
    fun testMetadataCopy() {
        val metadata = VideoMetadata(
            title = "Original Title",
            artist = "Original Artist",
            duration = 60000L
        )

        // Create a copy with some modified properties
        val copy = metadata.copy(
            title = "New Title",
            duration = 90000L
        )

        // Verify the original metadata is unchanged
        assertEquals("Original Title", metadata.title)
        assertEquals("Original Artist", metadata.artist)
        assertEquals(60000L, metadata.duration)

        // Verify the copy has the expected properties
        assertEquals("New Title", copy.title)
        assertEquals("Original Artist", copy.artist)
        assertEquals(90000L, copy.duration)
    }
}
