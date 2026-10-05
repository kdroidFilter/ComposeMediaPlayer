package io.github.kdroidfilter.composemediaplayer.mac

import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AVFoundation integration against a synthetic MP4 served by a delayed local HTTP server. */
class MacVideoDimensionsTimeoutNativeTest {
    @Test
    fun defaultWindowStillRejectsDelayedDimensions() = verifySlowStartup("video.mp4", null)

    @Test
    fun configuredWindowAllowsDelayedMp4Playback() = verifySlowStartup("video.mp4", 30_000L)

    @Test
    fun configuredWindowAllowsDelayedOpaqueUriPlayback() = verifySlowStartup("media", 30_000L)

    private fun verifySlowStartup(
        path: String,
        timeoutMillis: Long?,
    ) = runBlocking {
        assumeTrue(System.getenv("COMPOSE_MEDIA_PLAYER_RUN_NATIVE_TIMEOUT_TEST") == "1")
        assumeTrue(System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
        val media = checkNotNull(javaClass.getResourceAsStream("/startup/metadata-startup.mp4")).use { it.readBytes() }
        val firstRequestAt = AtomicLong(0L)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/$path") { exchange ->
            try {
                firstRequestAt.compareAndSet(0L, System.nanoTime())
                // Every request waits for the same deadline, including AVFoundation range requests.
                while (System.nanoTime() - firstRequestAt.get() < METADATA_DELAY_NANOS) Thread.sleep(50)
                val range = exchange.requestHeaders.getFirst("Range")
                val bounds = range?.removePrefix("bytes=")?.split("-")
                val start = bounds?.getOrNull(0)?.toIntOrNull() ?: 0
                val end = (bounds?.getOrNull(1)?.toIntOrNull() ?: media.lastIndex).coerceAtMost(media.lastIndex)
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${media.size}")
                exchange.sendResponseHeaders(if (range == null) 200 else 206, (end - start + 1).toLong())
                exchange.responseBody.use { it.write(media, start, end - start + 1) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                exchange.close()
            }
        }
        server.start()
        val player = if (timeoutMillis == null) DefaultVideoPlayerState() else DefaultVideoPlayerState(timeoutMillis)
        try {
            assertIs<MacVideoPlayerState>(player.delegate)
            player.openUri("http://127.0.0.1:${server.address.port}/$path")
            if (timeoutMillis == null) {
                withTimeout(12_000L) {
                    while (player.error == null) delay(50)
                }
                assertEquals(
                    "Failed to retrieve video size",
                    assertIs<VideoPlayerError.UnknownError>(player.error).message,
                )
                assertFalse(player.hasMedia)
                assertTrue(System.nanoTime() - firstRequestAt.get() < METADATA_DELAY_NANOS)
            } else {
                withTimeout(40_000L) {
                    while (!player.hasMedia || player.currentTime < 0.5) {
                        assertNull(player.error)
                        delay(50)
                    }
                }
                assertTrue(player.hasMedia)
                assertTrue(player.isPlaying)
                assertTrue(player.duration > 0)
                assertTrue(System.nanoTime() - firstRequestAt.get() >= METADATA_DELAY_NANOS)
            }
        } finally {
            player.dispose()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

private const val METADATA_DELAY_NANOS = 16_000_000_000L
