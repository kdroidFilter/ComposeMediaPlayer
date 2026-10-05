package io.github.kdroidfilter.composemediaplayer.mac

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal const val DEFAULT_MAC_VIDEO_DIMENSIONS_TIMEOUT_MILLIS = 5_000L
private const val DIMENSION_POLL_INTERVAL_MILLIS = 250L

/** Wait for asynchronous AVFoundation dimensions while the current open remains active. */
internal suspend fun awaitMacVideoDimensions(
    timeoutMillis: Long,
    isCurrentOpen: () -> Boolean,
    readDimensions: () -> Pair<Int, Int>,
): Boolean {
    require(timeoutMillis > 0) { "Video dimensions timeout must be positive." }
    return withTimeoutOrNull(timeoutMillis) {
        while (isCurrentOpen()) {
            val (width, height) = readDimensions()
            if (width > 0 && height > 0) return@withTimeoutOrNull true
            delay(DIMENSION_POLL_INTERVAL_MILLIS)
        }
        false
    } ?: false
}
