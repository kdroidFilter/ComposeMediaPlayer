package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.sun.jna.Platform
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.Version

/**
 * Tests for the JVM implementation of VideoPlayerState
 */
class VideoPlayerStateTest {

    /**
     * Checks if GStreamer is available and the playbin element can be created.
     * This is used to skip tests when GStreamer is not properly installed or configured.
     * GStreamer is only checked on Linux platforms.
     */
    private fun isGStreamerAvailable(): Boolean {
        // Only check for GStreamer on Linux platforms
        if (!Platform.isLinux()) {
            println("Skipping GStreamer check: Not on Linux platform")
            return false
        }

        try {
            // Try to initialize GStreamer if it's not already initialized
            if (!Gst.isInitialized()) {
                Gst.init(Version.BASELINE, "ComposeGStreamerPlayerTest")
            }

            // Try to create a playbin element
            val element = ElementFactory.make("playbin", "testPlaybin")
            val isAvailable = element != null
            element?.dispose()
            return isAvailable
        } catch (e: Exception) {
            println("GStreamer is not available: ${e.message}")
            return false
        }
    }

    /**
     * Test the creation of VideoPlayerState
     */
    @Test
    fun testCreateVideoPlayerState() {
        // Skip test if GStreamer is not available
        if (!isGStreamerAvailable()) {
            println("Skipping test: GStreamer is not available")
            return
        }

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
        // Skip test if GStreamer is not available
        if (!isGStreamerAvailable()) {
            println("Skipping test: GStreamer is not available")
            return
        }

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
        // Skip test if GStreamer is not available
        if (!isGStreamerAvailable()) {
            println("Skipping test: GStreamer is not available")
            return
        }

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
        // Skip test if GStreamer is not available
        if (!isGStreamerAvailable()) {
            println("Skipping test: GStreamer is not available")
            return
        }

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
        // Skip test if GStreamer is not available
        if (!isGStreamerAvailable()) {
            println("Skipping test: GStreamer is not available")
            return
        }

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
