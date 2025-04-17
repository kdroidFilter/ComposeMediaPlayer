package io.github.kdroidfilter.composemediaplayer.util

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * Formats a given time into either "HH:MM:SS" (if hours > 0) or "MM:SS".
 *
 * @param value The time value (if interpreting seconds, pass as Double;
 *              if interpreting nanoseconds, pass as Long).
 * @param isNanoseconds Set to true when you're passing nanoseconds (Long) for [value].
 */
internal fun formatTime(value: Number, isNanoseconds: Boolean = false): String {
    // Convert the input to seconds (Double) if it's nanoseconds
    val totalSeconds = if (isNanoseconds) {
        value.toLong() / 1_000_000_000.0
    } else {
        value.toDouble()
    }

    // Create an Instant starting from epoch 0 plus the given duration in seconds
    val instant: Instant = Instant.fromEpochSeconds(0) + totalSeconds.seconds
    val dateTime = instant.toLocalDateTime(TimeZone.UTC)

    // Extract hours, minutes, and seconds
    val hours = dateTime.hour
    val minutes = dateTime.minute
    val seconds = dateTime.second

    // Build the final string
    return if (hours > 0) {
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

/**
 * Converts a time string in the format "mm:ss" or "hh:mm:ss" to milliseconds.
 */
internal fun String.toTimeMs(): Long {
    val parts = this.split(":")
    return when (parts.size) {
        2 -> {
            // Format: "mm:ss"
            val minutes = parts[0].toLongOrNull() ?: 0
            val seconds = parts[1].toLongOrNull() ?: 0
            (minutes * 60 + seconds) * 1000
        }
        3 -> {
            // Format: "hh:mm:ss"
            val hours = parts[0].toLongOrNull() ?: 0
            val minutes = parts[1].toLongOrNull() ?: 0
            val seconds = parts[2].toLongOrNull() ?: 0
            (hours * 3600 + minutes * 60 + seconds) * 1000
        }
        else -> 0
    }
}
