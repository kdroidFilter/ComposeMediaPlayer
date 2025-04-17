package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.compose.runtime.Immutable

/**
 * Represents a single subtitle cue with timing information and text content.
 *
 * @property startTime The start time of the subtitle in milliseconds
 * @property endTime The end time of the subtitle in milliseconds
 * @property text The text content of the subtitle
 */
@Immutable
data class SubtitleCue(
    val startTime: Long,
    val endTime: Long,
    val text: String
) {
    /**
     * Checks if this subtitle cue should be displayed at the given time.
     *
     * @param currentTimeMs The current playback time in milliseconds
     * @return True if the cue should be displayed, false otherwise
     */
    fun isActive(currentTimeMs: Long): Boolean {
        return currentTimeMs in startTime..endTime
    }
}

/**
 * Represents a collection of subtitle cues for a specific track.
 *
 * @property cues The list of subtitle cues
 */
@Immutable
data class SubtitleCueList(
    val cues: List<SubtitleCue> = emptyList()
) {
    /**
     * Gets the active subtitle cues at the given time.
     *
     * @param currentTimeMs The current playback time in milliseconds
     * @return The list of active subtitle cues
     */
    fun getActiveCues(currentTimeMs: Long): List<SubtitleCue> {
        return cues.filter { it.isActive(currentTimeMs) }
    }
}