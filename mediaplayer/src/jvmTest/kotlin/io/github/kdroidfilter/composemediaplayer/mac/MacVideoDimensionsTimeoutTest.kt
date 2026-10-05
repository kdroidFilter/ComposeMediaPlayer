package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MacVideoDimensionsTimeoutTest {
    @Test
    fun noArgumentJvmConstructorsRemainAvailable() {
        assertNotNull(MacVideoPlayerState::class.java.getConstructor())
        assertNotNull(DefaultVideoPlayerState::class.java.getConstructor())
    }

    @Test
    fun defaultRetainsFiveSecondPollingWindow() =
        runTest {
            var reads = 0
            assertFalse(
                awaitMacVideoDimensions(DEFAULT_MAC_VIDEO_DIMENSIONS_TIMEOUT_MILLIS, { true }) {
                    reads++
                    0 to 0
                },
            )
            assertEquals(5_000L, currentTime)
            assertEquals(20, reads)
        }

    @Test
    fun readyDimensionsReturnImmediately() =
        runTest {
            assertTrue(awaitMacVideoDimensions(30_000L, { true }) { 1920 to 1080 })
            assertEquals(0L, currentTime)
        }

    @Test
    fun longerWindowAllowsDelayedDimensions() =
        runTest {
            assertTrue(
                awaitMacVideoDimensions(30_000L, { true }) {
                    if (currentTime >= 16_000L) 1920 to 1080 else 0 to 0
                },
            )
            assertEquals(16_000L, currentTime)
        }

    @Test
    fun customWindowStillHasABoundedDeadline() =
        runTest {
            assertFalse(awaitMacVideoDimensions(30_000L, { true }) { 0 to 0 })
            assertEquals(30_000L, currentTime)
        }

    @Test
    fun shorterWindowUsesConfiguredDeadline() =
        runTest {
            assertFalse(awaitMacVideoDimensions(600L, { true }) { 0 to 0 })
            assertEquals(600L, currentTime)
        }

    @Test
    fun bothDimensionsMustBePositive() =
        runTest {
            assertFalse(awaitMacVideoDimensions(1_000L, { true }) { 1920 to 0 })
        }

    @Test
    fun supersededOpenStopsPolling() =
        runTest {
            var current = true
            var reads = 0
            val wait =
                async {
                    awaitMacVideoDimensions(30_000L, { current }) {
                        reads++
                        0 to 0
                    }
                }
            advanceTimeBy(1_000L)
            current = false
            assertFalse(wait.await())
            assertEquals(4, reads)
            assertEquals(1_000L, currentTime)
        }

    @Test
    fun cancellationStopsPolling() =
        runTest {
            var reads = 0
            val wait =
                async {
                    awaitMacVideoDimensions(30_000L, { true }) {
                        reads++
                        0 to 0
                    }
                }
            advanceTimeBy(1_000L)
            wait.cancel()
            wait.join()
            advanceTimeBy(30_000L)
            assertTrue(wait.isCancelled)
            assertEquals(4, reads)
        }

    @Test
    fun nonPositiveTimeoutIsRejectedBeforeNativeInitialization() =
        runTest {
            for (timeout in listOf(0L, -1L)) {
                assertFailsWith<IllegalArgumentException> { awaitMacVideoDimensions(timeout, { true }) { 0 to 0 } }
                assertFailsWith<IllegalArgumentException> { MacVideoPlayerState(timeout) }
                assertFailsWith<IllegalArgumentException> { DefaultVideoPlayerState(timeout) }
            }
        }
}
