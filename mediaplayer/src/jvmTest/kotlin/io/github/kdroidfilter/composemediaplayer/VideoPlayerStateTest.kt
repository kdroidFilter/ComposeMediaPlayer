package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the JVM implementation of VideoPlayerState
 */
class VideoPlayerStateTest {
    /**
     * Checks if the native video player library is available.
     * On Linux, this requires the native GStreamer JNI library.
     * On macOS, this requires the AVFoundation JNI library.
     * On Windows, this requires the Media Foundation JNI library.
     */
    private fun isNativePlayerAvailable(): Boolean =
        try {
            val state = createVideoPlayerState()
            state.dispose()
            true
        } catch (e: Exception) {
            println("Native player not available: ${e.message}")
            false
        }

    @Test
    fun testCreateVideoPlayerState() {
        if (!isNativePlayerAvailable()) {
            println("Skipping test: Native player not available")
            return
        }

        val playerState = createVideoPlayerState()

        assertNotNull(playerState)
        assertFalse(playerState.hasMedia)
        assertFalse(playerState.isPlaying)
        assertEquals(0f, playerState.sliderPos)
        assertEquals(1f, playerState.volume)
        assertFalse(playerState.loop)
        assertEquals("00:00", playerState.positionText)
        assertEquals("00:00", playerState.durationText)
        assertFalse(playerState.isFullscreen)

        playerState.dispose()
    }

    @Test
    fun testVolumeControl() {
        if (!isNativePlayerAvailable()) {
            println("Skipping test: Native player not available")
            return
        }

        val playerState = createVideoPlayerState()

        assertEquals(1f, playerState.volume)

        playerState.volume = 0.5f
        assertEquals(0.5f, playerState.volume)

        playerState.volume = -0.1f
        assertEquals(0f, playerState.volume, "Volume should be clamped to 0")

        playerState.volume = 1.5f
        assertEquals(1f, playerState.volume, "Volume should be clamped to 1")

        playerState.dispose()
    }

    @Test
    fun testLoopSetting() {
        if (!isNativePlayerAvailable()) {
            println("Skipping test: Native player not available")
            return
        }

        val playerState = createVideoPlayerState()

        assertFalse(playerState.loop)

        playerState.loop = true
        assertTrue(playerState.loop)

        playerState.loop = false
        assertFalse(playerState.loop)

        playerState.dispose()
    }

    @Test
    fun testFullscreenToggle() {
        if (!isNativePlayerAvailable()) {
            println("Skipping test: Native player not available")
            return
        }

        val playerState = createVideoPlayerState()

        assertFalse(playerState.isFullscreen)

        playerState.toggleFullscreen()
        assertTrue(playerState.isFullscreen)

        playerState.toggleFullscreen()
        assertFalse(playerState.isFullscreen)

        playerState.dispose()
    }

    @Test
    fun testErrorHandling() {
        if (!isNativePlayerAvailable()) {
            println("Skipping test: Native player not available")
            return
        }

        val playerState = createVideoPlayerState()

        assertEquals(null, playerState.error)

        runBlocking {
            playerState.openUri("non_existent_file.mp4")
            delay(500)
        }

        assertNotNull(playerState.error)

        playerState.clearError()
        assertEquals(null, playerState.error)

        playerState.dispose()
    }
}
