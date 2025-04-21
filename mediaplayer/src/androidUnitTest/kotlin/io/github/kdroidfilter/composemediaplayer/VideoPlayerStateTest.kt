package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import com.kdroid.androidcontextprovider.ContextProvider

/**
 * Tests for the Android implementation of VideoPlayerState
 */
class VideoPlayerStateTest {

    /**
     * Helper function to check if ContextProvider is available and initialized
     * If not, the test will be skipped
     */
    private fun skipIfContextProviderNotAvailable(): Boolean {
        try {
            // Try to actually call getContext() to see if ContextProvider is initialized
            ContextProvider.getContext()
            return false // Don't skip, ContextProvider is available and initialized
        } catch (e: IllegalStateException) {
            // This is the expected exception when ContextProvider is not initialized
            println("Skipping test: ContextProvider has not been initialized")
            return true // Skip the test
        } catch (e: Exception) {
            // Any other exception means something else is wrong
            println("Skipping test: Error accessing ContextProvider: ${e.message}")
            return true // Skip the test
        }
    }

    /**
     * Test the creation of VideoPlayerState
     */
    @Test
    fun testCreateVideoPlayerState() {
        if (skipIfContextProviderNotAvailable()) return

        val playerState = VideoPlayerState()

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

        // Clean up
        playerState.dispose()
    }

    /**
     * Test volume control
     */
    @Test
    fun testVolumeControl() {
        if (skipIfContextProviderNotAvailable()) return

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
        if (skipIfContextProviderNotAvailable()) return

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
        if (skipIfContextProviderNotAvailable()) return

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
        if (skipIfContextProviderNotAvailable()) return

        val playerState = VideoPlayerState()

        // Initially there should be no error
        assertEquals(null, playerState.error)

        // Test opening a non-existent file (should cause an error)
        playerState.openUri("non_existent_file.mp4")

        // Test clearing the error
        playerState.clearError()
        assertEquals(null, playerState.error)

        // Clean up
        playerState.dispose()
    }

    /**
     * Test subtitle functionality
     */
    @Test
    fun testSubtitleFunctionality() {
        if (skipIfContextProviderNotAvailable()) return

        val playerState = VideoPlayerState()

        // Initially subtitles should be disabled
        assertFalse(playerState.subtitlesEnabled)
        assertEquals(null, playerState.currentSubtitleTrack)
        assertTrue(playerState.availableSubtitleTracks.isEmpty())

        // Create a test subtitle track
        val testTrack = SubtitleTrack(
            label = "English",
            language = "en",
            src = "test.vtt"
        )

        // Select the subtitle track
        playerState.selectSubtitleTrack(testTrack)

        // Verify subtitle state
        assertTrue(playerState.subtitlesEnabled)
        assertEquals(testTrack, playerState.currentSubtitleTrack)

        // Disable subtitles
        playerState.disableSubtitles()

        // Verify subtitle state after disabling
        assertFalse(playerState.subtitlesEnabled)
        assertEquals(null, playerState.currentSubtitleTrack)

        // Clean up
        playerState.dispose()
    }

    /**
     * Test metadata functionality
     */
    @Test
    fun testMetadataFunctionality() {
        if (skipIfContextProviderNotAvailable()) return

        val playerState = VideoPlayerState()

        // Verify metadata object is initialized
        assertNotNull(playerState.metadata)

        // Initially metadata fields should be null
        assertEquals(null, playerState.metadata.title)
        assertEquals(null, playerState.metadata.artist)
        assertEquals(null, playerState.metadata.duration)
        assertEquals(null, playerState.metadata.width)
        assertEquals(null, playerState.metadata.height)
        assertEquals(null, playerState.metadata.bitrate)
        assertEquals(null, playerState.metadata.frameRate)
        assertEquals(null, playerState.metadata.mimeType)
        assertEquals(null, playerState.metadata.audioChannels)
        assertEquals(null, playerState.metadata.audioSampleRate)

        // Clean up
        playerState.dispose()
    }
}
