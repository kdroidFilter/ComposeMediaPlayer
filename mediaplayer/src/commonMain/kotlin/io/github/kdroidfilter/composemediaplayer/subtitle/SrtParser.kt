package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parser for SRT (SubRip) subtitle files.
 */
object SrtParser {
    // SRT time format uses comma instead of period for milliseconds: "00:00:00,000"
    private val TIME_PATTERN = Regex("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})")
    // SRT timing line format: "00:00:00,000 --> 00:00:00,000"
    private val CUE_TIMING_PATTERN = Regex("(\\d{2}:\\d{2}:\\d{2},\\d{3}) --> (\\d{2}:\\d{2}:\\d{2},\\d{3})")

    /**
     * Parses an SRT file content into a SubtitleCueList.
     *
     * @param content The SRT file content as a string
     * @return A SubtitleCueList containing the parsed subtitle cues
     */
    fun parse(content: String): SubtitleCueList {
        val lines = content.lines()
        val cues = mutableListOf<SubtitleCue>()
        var i = 0

        // SRT format: each entry consists of:
        // 1. A sequence number
        // 2. The timing line (start --> end)
        // 3. The subtitle text (one or more lines)
        // 4. A blank line separating entries

        while (i < lines.size) {
            // Skip empty lines
            if (lines[i].isBlank()) {
                i++
                continue
            }

            // Try to parse as a sequence number (should be a positive integer)
            val sequenceNumber = lines[i].trim().toIntOrNull()
            if (sequenceNumber != null) {
                i++ // Move to timing line

                // Check if we're still within bounds and the next line is a timing line
                if (i < lines.size) {
                    val timingLine = lines[i]
                    val timingMatch = CUE_TIMING_PATTERN.find(timingLine)

                    if (timingMatch != null) {
                        val startTimeStr = timingMatch.groupValues[1]
                        val endTimeStr = timingMatch.groupValues[2]

                        val startTime = parseTimeToMillis(startTimeStr)
                        val endTime = parseTimeToMillis(endTimeStr)

                        i++ // Move to subtitle text
                        val textBuilder = StringBuilder()

                        // Collect all lines until an empty line or end of file
                        while (i < lines.size && lines[i].isNotBlank()) {
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
                        // Not a valid timing line, skip
                        i++
                    }
                } else {
                    // End of file
                    break
                }
            } else {
                // Not a sequence number, skip this line
                i++
            }
        }

        return SubtitleCueList(cues)
    }

    /**
     * Parses a time string in the format "00:00:00,000" to milliseconds.
     *
     * @param timeStr The time string to parse
     * @return The time in milliseconds
     */
    private fun parseTimeToMillis(timeStr: String): Long {
        val match = TIME_PATTERN.find(timeStr)
        if (match != null) {
            val hours = match.groupValues[1].toLong()
            val minutes = match.groupValues[2].toLong()
            val seconds = match.groupValues[3].toLong()
            val millis = match.groupValues[4].toLong()

            return (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
        }

        return 0
    }

    /**
     * Loads and parses an SRT file from a URL.
     *
     * @param url The URL of the SRT file
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