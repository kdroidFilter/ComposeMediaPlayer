package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/**
 * Tests for the JVM implementation of VideoPlayerState
 */
class VideoPlayerStateTest {
    
    /**
     * Test the creation of VideoPlayerState
     */
    @Test
    fun testCreateVideoPlayerState() {
        val playerState = VideoPlayerState()
        
        // Verify the player state is initialized correctly
        assertNotNull(playerState)
        assertNotNull(playerState.delegate)
        assertFalse(playerState.hasMedia)
        assertFalse(playerState.isPlaying)
        assertEquals(0f, playerState.sliderPos)
        assertEquals(1f, playerState.volume)
        assertFalse(playerState.loop)
        assertEquals("00:00", playerState.positionText)
        assertEquals("00:00", playerState.durationText)
        assertEquals(0f, playerState.leftLevel)
        assertEquals(0f, playerState.rightLevel)
        assertFalse(playerState.isFullscreen)
        
        // Clean up
        playerState.dispose()
    }
    
    /**
     * Test volume control
     */
    @Test
    fun testVolumeControl() {
        val playerState = VideoPlayerState()
        
        // Test initial volume
        assertEquals(1f, playerState.volume)
        
        // Test setting volume
        playerState.volume = 0.5f
        assertEquals(0.5f, playerState.volume)
        
        // Test volume bounds
        playerState.volume = -0.1f
        assertEquals(0f, playerState.volume, "Volume should be clamped to 0")
        
        playerState.volume = 1.5f
        assertEquals(1f, playerState.volume, "Volume should be clamped to 1")
        
        // Clean up
        playerState.dispose()
    }
    
    /**
     * Test loop setting
     */
    @Test
    fun testLoopSetting() {
        val playerState = VideoPlayerState()
        
        // Test initial loop setting
        assertFalse(playerState.loop)
        
        // Test setting loop
        playerState.loop = true
        assertTrue(playerState.loop)
        
        playerState.loop = false
        assertFalse(playerState.loop)
        
        // Clean up
        playerState.dispose()
    }
    
    /**
     * Test fullscreen toggle
     */
    @Test
    fun testFullscreenToggle() {
        val playerState = VideoPlayerState()
        
        // Test initial fullscreen state
        assertFalse(playerState.isFullscreen)
        
        // Test toggling fullscreen
        playerState.toggleFullscreen()
        assertTrue(playerState.isFullscreen)
        
        playerState.toggleFullscreen()
        assertFalse(playerState.isFullscreen)
        
        // Clean up
        playerState.dispose()
    }
    
    /**
     * Test error handling
     */
    @Test
    fun testErrorHandling() {
        val playerState = VideoPlayerState()
        
        // Initially there should be no error
        assertEquals(null, playerState.error)
        
        // Test opening a non-existent file (should cause an error)
        runBlocking {
            playerState.openUri("non_existent_file.mp4")
            delay(500) // Give some time for the error to be set
        }
        
        // There should be an error now
        assertNotNull(playerState.error)
        
        // Test clearing the error
        playerState.clearError()
        assertEquals(null, playerState.error)
        
        // Clean up
        playerState.dispose()
    }
}