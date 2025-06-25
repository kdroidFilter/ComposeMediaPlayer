package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.PlatformVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.sun.jna.Platform
import org.junit.Before
import org.junit.Assume
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.Version

/**
 * Tests for the Linux implementation of PlatformVideoPlayerState
 * 
 * Note: These tests will only run on Linux platforms. On other platforms,
 * the tests will be skipped.
 * 
 * Additionally, these tests require GStreamer to be properly installed and
 * the "playbin" element to be available. If GStreamer is not available,
 * the tests will be skipped.
 */
class LinuxVideoPlayerStateTest {

    /**
     * Setup method to check if GStreamer is available before running tests.
     * If GStreamer is not available or the "playbin" element is not found,
     * the tests will be skipped.
     */
    @Before
    fun setup() {
        // Skip test if not running on Linux
        Assume.assumeTrue("Skipping Linux-specific test on non-Linux platform", Platform.isLinux())

        // Try to initialize GStreamer
        try {
            // Initialize GStreamer
            if (!Gst.isInitialized()) {
                Gst.init(Version.BASELINE, "TestGStreamerPlayer")
            }

            // Try to create a "playbin" element to check if it's available
            try {
                val playbin = org.freedesktop.gstreamer.ElementFactory.make("playbin", "testplaybin")
                Assume.assumeNotNull("GStreamer 'playbin' element not available", playbin)
                playbin.dispose() // Clean up the test element
            } catch (e: Exception) {
                // If playbin creation fails, skip the test
                Assume.assumeNoException("GStreamer 'playbin' element not available", e)
            }
        } catch (e: Exception) {
            // If GStreamer initialization fails, skip the test
            Assume.assumeNoException("GStreamer initialization failed", e)
        }
    }

    /**
     * Test the creation of LinuxVideoPlayerState
     */
    @Test
    fun testCreateLinuxVideoPlayerState() {
        val playerState = LinuxVideoPlayerState()

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
        val playerState = LinuxVideoPlayerState()

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
        val playerState = LinuxVideoPlayerState()

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
        val playerState = LinuxVideoPlayerState()

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
        val playerState = LinuxVideoPlayerState()

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
}
