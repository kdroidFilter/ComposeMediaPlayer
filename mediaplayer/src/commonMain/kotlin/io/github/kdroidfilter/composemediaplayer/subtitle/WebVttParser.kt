package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parser for WebVTT subtitle files.
 */
object WebVttParser {
    private const val WEBVTT_HEADER = "WEBVTT"
    // Support both formats: "00:00:00.000" (with hours) and "00:00.000" (without hours)
    private val TIME_PATTERN_WITH_HOURS = Regex("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})")
    private val TIME_PATTERN_WITHOUT_HOURS = Regex("(\\d{2}):(\\d{2})\\.(\\d{3})")
    // Support both formats in the timing line
    private val CUE_TIMING_PATTERN = Regex("(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\d{2}:\\d{2}\\.\\d{3})")

    /**
     * Parses a WebVTT file content into a SubtitleCueList.
     *
     * @param content The WebVTT file content as a string
     * @return A SubtitleCueList containing the parsed subtitle cues
     */
    fun parse(content: String): SubtitleCueList {
        if (!content.trim().startsWith(WEBVTT_HEADER)) {
            return SubtitleCueList() // Not a valid WebVTT file
        }

        val lines = content.lines()
        val cues = mutableListOf<SubtitleCue>()
        var i = 0

        // Skip header and empty lines
        while (i < lines.size && !CUE_TIMING_PATTERN.matches(lines[i])) {
            i++
        }

        while (i < lines.size) {
            val timingLine = lines[i]
            val timingMatch = CUE_TIMING_PATTERN.find(timingLine)

            if (timingMatch != null) {
                val startTimeStr = timingMatch.groupValues[1]
                val endTimeStr = timingMatch.groupValues[2]

                val startTime = parseTimeToMillis(startTimeStr)
                val endTime = parseTimeToMillis(endTimeStr)

                i++
                val textBuilder = StringBuilder()

                // Collect all lines until an empty line or end of file
                while (i < lines.size && lines[i].isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n")
                    }
                    textBuilder.append(lines[i])
                    i++
                }

                val text = textBuilder.toString().trim()
                if (text.isNotEmpty()) {
                    cues.add(SubtitleCue(startTime, endTime, text))
                }
            } else {
                i++
            }
        }

        return SubtitleCueList(cues)
    }

    /**
     * Parses a time string in the format "00:00:00.000" or "00:00.000" to milliseconds.
     *
     * @param timeStr The time string to parse
     * @return The time in milliseconds
     */
    private fun parseTimeToMillis(timeStr: String): Long {
        // Try to match the format with hours first
        val matchWithHours = TIME_PATTERN_WITH_HOURS.find(timeStr)
        if (matchWithHours != null) {
            val hours = matchWithHours.groupValues[1].toLong()
            val minutes = matchWithHours.groupValues[2].toLong()
            val seconds = matchWithHours.groupValues[3].toLong()
            val millis = matchWithHours.groupValues[4].toLong()

            return (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
        }

        // If that fails, try to match the format without hours
        val matchWithoutHours = TIME_PATTERN_WITHOUT_HOURS.find(timeStr)
        if (matchWithoutHours != null) {
            val minutes = matchWithoutHours.groupValues[1].toLong()
            val seconds = matchWithoutHours.groupValues[2].toLong()
            val millis = matchWithoutHours.groupValues[3].toLong()

            return (minutes * 60 + seconds) * 1000 + millis
        }

        return 0
    }

    /**
     * Loads and parses a WebVTT file from a URL.
     *
     * @param url The URL of the WebVTT file
     * @return A SubtitleCueList containing the parsed subtitle cues
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun loadFromUrl(url: String): SubtitleCueList {
        return withContext(Dispatchers.Default) {
            try {
                // Use the platform-specific loadSubtitleContent function to fetch the content
                val content = loadSubtitleContent(url)
                parse(content)
            } catch (e: Exception) {
                SubtitleCueList() // Return empty list on error
            }
        }
    }
}
