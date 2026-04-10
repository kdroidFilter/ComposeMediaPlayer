package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the Linux implementation of VideoPlayerState
 *
 * Note: These tests will only run on Linux platforms. On other platforms,
 * the tests will be skipped.
 *
 * Additionally, these tests require the native GStreamer library to be available.
 * If the native library cannot be loaded, the tests will be skipped.
 */
class LinuxVideoPlayerStateTest {
    @Before
    fun setup() {
        // Skip test if not running on Linux
        Assume.assumeTrue(
            "Skipping Linux-specific test on non-Linux platform",
            CurrentPlatform.os == CurrentPlatform.OS.LINUX,
        )

        // Try to load the native library
        try {
            LinuxNativeBridge.nCreatePlayer().let { ptr ->
                if (ptr != 0L) LinuxNativeBridge.nDisposePlayer(ptr)
            }
        } catch (e: Exception) {
            Assume.assumeNoException("Native video player library not available", e)
        }
    }

    @Test
    fun testCreateLinuxVideoPlayerState() {
        val playerState = LinuxVideoPlayerState()

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

        playerState.dispose()
    }

    @Test
    fun testVolumeControl() {
        val playerState = LinuxVideoPlayerState()

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
        val playerState = LinuxVideoPlayerState()

        assertFalse(playerState.loop)

        playerState.loop = true
        assertTrue(playerState.loop)

        playerState.loop = false
        assertFalse(playerState.loop)

        playerState.dispose()
    }

    @Test
    fun testFullscreenToggle() {
        val playerState = LinuxVideoPlayerState()

        assertFalse(playerState.isFullscreen)

        playerState.toggleFullscreen()
        assertTrue(playerState.isFullscreen)

        playerState.toggleFullscreen()
        assertFalse(playerState.isFullscreen)

        playerState.dispose()
    }

    @Test
    fun testErrorHandling() {
        val playerState = LinuxVideoPlayerState()

        assertNull(playerState.error)

        runBlocking {
            playerState.openUri("non_existent_file.mp4")
            delay(500)
        }

        assertNotNull(playerState.error)

        playerState.clearError()
        assertNull(playerState.error)

        playerState.dispose()
    }
}
