package io.github.kdroidfilter.composemediaplayer.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.sun.jna.Platform

/**
 * Tests for the Mac implementation of VideoPlayerState
 * 
 * Note: These tests will only run on Mac platforms. On other platforms,
 * the tests will be skipped.
 */
class MacVideoPlayerStateTest {
    
    /**
     * Test the creation of MacVideoPlayerState
     */
    @Test
    fun testCreateMacVideoPlayerState() {
        // Skip test if not running on Mac
        if (!Platform.isMac()) {
            println("Skipping Mac-specific test on non-Mac platform")
            return
        }
        
        val playerState = MacVideoPlayerState()
        
        // Verify the player state is initialized correctly
        assertNotNull(playerState)
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
        assertNull(playerState.error)
        
        // Clean up
        playerState.dispose()
    }
    
    /**
     * Test volume control
     */
    @Test
    fun testVolumeControl() {
        // Skip test if not running on Mac
        if (!Platform.isMac()) {
            println("Skipping Mac-specific test on non-Mac platform")
            return
        }
        
        val playerState = MacVideoPlayerState()
        
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
        // Skip test if not running on Mac
        if (!Platform.isMac()) {
            println("Skipping Mac-specific test on non-Mac platform")
            return
        }
        
        val playerState = MacVideoPlayerState()
        
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
        // Skip test if not running on Mac
        if (!Platform.isMac()) {
            println("Skipping Mac-specific test on non-Mac platform")
            return
        }
        
        val playerState = MacVideoPlayerState()
        
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
        // Skip test if not running on Mac
        if (!Platform.isMac()) {
            println("Skipping Mac-specific test on non-Mac platform")
            return
        }
        
        val playerState = MacVideoPlayerState()
        
        // Initially there should be no error
        assertNull(playerState.error)
        
        // Test opening a non-existent file (should cause an error)
        runBlocking {
            playerState.openUri("non_existent_file.mp4")
            delay(500) // Give some time for the error to be set
        }
        
        // There should be an error now
        assertNotNull(playerState.error)
        
        // Test clearing the error
        playerState.clearError()
        assertNull(playerState.error)
        
        // Clean up
        playerState.dispose()
    }

    private fun testOpenLocalFile(file: String) {
        // Skip test if not running on Mac
        if (!Platform.isMac()) {
            println("Skipping Mac-specific test on non-Mac platform")
            return
        }

        val playerState = MacVideoPlayerState()

        // Initially there should be no error
        assertNull(playerState.error)

        // Test opening a non-existent file (should cause an error)
        runBlocking {
            playerState.openUri(file)
            delay(500) // Give some time for the error to be set
        }

        // There should be no error
        assertNull(playerState.error)

        // Clean up
        playerState.dispose()
    }

    @Test
    fun testOpenLocalFile() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path
        testOpenLocalFile(path)
    }

    @Test
    fun testOpenLocalFileWithScheme() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path
        testOpenLocalFile("file:$path")
    }

    @Test
    fun testOpenLocalFileWithSchemeWithAuthority() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path
        testOpenLocalFile("file://$path")
    }

    private fun testMalformedUri(uri: String) {
        // Skip test if not running on Mac
        if (!Platform.isMac()) {
            println("Skipping Mac-specific test on non-Mac platform")
            return
        }

        val playerState = MacVideoPlayerState()

        // Initially there should be no error
        assertNull(playerState.error)

        // Test opening a non-existent file (should cause an error)
        runBlocking {
            playerState.openUri(uri)
            delay(500) // Give some time for the error to be set
        }

        // There should be an error now
        assertNotNull(playerState.error)

        // Test clearing the error
        playerState.clearError()
        assertNull(playerState.error)

        // Clean up
        playerState.dispose()
    }

    @Test
    fun testMalformedUri() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path
        testMalformedUri("file:${path.removePrefix("/")}")
    }
}