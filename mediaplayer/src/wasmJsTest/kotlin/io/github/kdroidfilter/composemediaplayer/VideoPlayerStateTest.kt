package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for the WebAssembly/JavaScript implementation of VideoPlayerState
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
     */
    @Test
    fun testErrorHandling() {
        val playerState = VideoPlayerState()

        // Initially there should be no error
        assertEquals(null, playerState.error)

        // Test setting an error manually (since we can't easily trigger a real error in tests)
        playerState.setError(VideoPlayerError.NetworkError("Test error"))

        // There should be an error now
        assertNotNull(playerState.error)

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
     * Test position updates
     */
    @Test
    fun testPositionUpdates() {
        val playerState = VideoPlayerState()

        // Test initial position
        assertEquals(0f, playerState.sliderPos)
        assertEquals("00:00", playerState.positionText)
        assertEquals("00:00", playerState.durationText)

        // Test updating position manually with forceUpdate to bypass rate limiting
        playerState.updatePosition(30f, 120f, forceUpdate = true)

        // Verify position was updated
        assertEquals("00:30", playerState.positionText)
        assertEquals("02:00", playerState.durationText)

        // Clean up
        playerState.dispose()
    }
}
