package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for the iOS implementation of VideoPlayerState
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
     * Note: Error handling in iOS implementation is minimal
     */
    @Test
    fun testErrorHandling() {
        val playerState = VideoPlayerState()

        // Test opening a non-existent file
        playerState.openUri("non_existent_file.mp4")

        // Test clearing the error
        playerState.clearError()

        // Clean up
        playerState.dispose()
    }

    /**
     * Test subtitle functionality
     * Note: Some subtitle functionality is marked as TODO in the iOS implementation
     */
    @Test
    fun testSubtitleFunctionality() {
        val playerState = VideoPlayerState()

        // Create a test subtitle track
        val testTrack = SubtitleTrack(
            label = "English",
            language = "en",
            src = "test.vtt"
        )

        // Select the subtitle track
        playerState.selectSubtitleTrack(testTrack)

        // Disable subtitles
        playerState.disableSubtitles()

        // Clean up
        playerState.dispose()
    }
}
