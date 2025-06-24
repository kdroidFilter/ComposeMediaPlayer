package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoMetadataTest {
    
    @Test
    fun testEmptyMetadata() {
        val metadata = VideoMetadata()
        assertTrue(metadata.isAllNull(), "A newly created metadata object should have all null properties")
    }
    
    @Test
    fun testPartialMetadata() {
        val metadata = VideoMetadata(
            title = "Test Video",
            duration = 60000L, // 1 minute
            width = 1920,
            height = 1080
        )
        
        assertFalse(metadata.isAllNull(), "Metadata with some properties set should not be all null")
        assertEquals("Test Video", metadata.title)
        assertEquals(60000L, metadata.duration)
        assertEquals(1920, metadata.width)
        assertEquals(1080, metadata.height)
        assertEquals(null, metadata.bitrate)
        assertEquals(null, metadata.frameRate)
        assertEquals(null, metadata.mimeType)
        assertEquals(null, metadata.audioChannels)
        assertEquals(null, metadata.audioSampleRate)
    }
    
    @Test
    fun testFullMetadata() {
        val metadata = VideoMetadata(
            title = "Complete Test Video",
            duration = 120000L, // 2 minutes
            width = 3840,
            height = 2160,
            bitrate = 5000000L, // 5 Mbps
            frameRate = 30.0f,
            mimeType = "video/mp4",
            audioChannels = 2,
            audioSampleRate = 48000
        )
        
        assertFalse(metadata.isAllNull(), "Fully populated metadata should not be all null")
        assertEquals("Complete Test Video", metadata.title)
        assertEquals(120000L, metadata.duration)
        assertEquals(3840, metadata.width)
        assertEquals(2160, metadata.height)
        assertEquals(5000000L, metadata.bitrate)
        assertEquals(30.0f, metadata.frameRate)
        assertEquals("video/mp4", metadata.mimeType)
        assertEquals(2, metadata.audioChannels)
        assertEquals(48000, metadata.audioSampleRate)
    }
    
    @Test
    fun testDataClassEquality() {
        val metadata1 = VideoMetadata(
            title = "Equality Test",
            duration = 300000L, // 5 minutes
            width = 1280,
            height = 720
        )
        
        val metadata2 = VideoMetadata(
            title = "Equality Test",
            duration = 300000L,
            width = 1280,
            height = 720
        )
        
        val metadata3 = VideoMetadata(
            title = "Different Title",
            duration = 300000L,
            width = 1280,
            height = 720
        )
        
        assertEquals(metadata1, metadata2, "Identical metadata objects should be equal")
        assertFalse(metadata1 == metadata3, "Metadata objects with different properties should not be equal")
    }
    
    @Test
    fun testCopyFunction() {
        val original = VideoMetadata(
            title = "Original Video",
            duration = 180000L, // 3 minutes
            width = 1920,
            height = 1080
        )
        
        val copy = original.copy(title = "Modified Video", bitrate = 3000000L)
        
        assertEquals("Modified Video", copy.title)
        assertEquals(original.duration, copy.duration)
        assertEquals(original.width, copy.width)
        assertEquals(original.height, copy.height)
        assertEquals(3000000L, copy.bitrate)
        
        // Original should remain unchanged
        assertEquals("Original Video", original.title)
        assertEquals(null, original.bitrate)
    }
}