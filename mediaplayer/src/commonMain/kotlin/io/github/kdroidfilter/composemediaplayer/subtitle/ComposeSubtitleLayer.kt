package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A composable function that displays subtitles over a video player.
 * This component handles loading and parsing subtitle files, and displaying
 * the active subtitles at the current playback time.
 *
 * @param currentTimeMs The current playback time in milliseconds
 * @param durationMs The total duration of the media in milliseconds
 * @param isPlaying Whether the video is currently playing
 * @param subtitleTrack The current subtitle track, or null if no subtitle is selected
 * @param subtitlesEnabled Whether subtitles are enabled
 * @param modifier The modifier to be applied to the layout
 * @param textStyle The text style to be applied to the subtitle text
 * @param backgroundColor The background color of the subtitle box
 */
@Composable
fun ComposeSubtitleLayer(
    currentTimeMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    subtitleTrack: SubtitleTrack?,
    subtitlesEnabled: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center
    ),
    backgroundColor: Color = Color.Black.copy(alpha = 0.5f)
) {
    // State to hold the parsed subtitle cues
    var subtitles by remember { mutableStateOf<SubtitleCueList?>(null) }

    // Load subtitles when the subtitle track changes
    LaunchedEffect(subtitleTrack) {
        subtitles = if (subtitleTrack != null && subtitlesEnabled) {
            try {
                withContext(Dispatchers.Default) {
                    // Load and parse the subtitle file
                    val content = loadSubtitleContent(subtitleTrack.src)

                    // Determine the subtitle format based on file extension and content
                    val isSrtByExtension = subtitleTrack.src.endsWith(".srt", ignoreCase = true)

                    // Check content for SRT format (typically starts with a number followed by timing)
                    val isSrtByContent = content.trim().let { 
                        val lines = it.lines()
                        lines.size >= 2 && 
                        lines[0].trim().toIntOrNull() != null && 
                        lines[1].contains("-->") && 
                        lines[1].contains(",") // SRT uses comma for milliseconds
                    }

                    // Check content for WebVTT format (starts with WEBVTT)
                    val isVttByContent = content.trim().startsWith("WEBVTT")

                    // Use the appropriate parser based on format detection
                    if (isSrtByExtension || (isSrtByContent && !isVttByContent)) {
                        SrtParser.parse(content)
                    } else {
                        // Default to WebVTT parser for other formats
                        WebVttParser.parse(content)
                    }
                }
            } catch (e: Exception) {
                // If there's an error loading or parsing the subtitle file,
                // return an empty subtitle list
                SubtitleCueList()
            }
        } else {
            // If no subtitle track is selected or subtitles are disabled,
            // return null to hide the subtitle display
            null
        }
    }

    // Display the subtitles if available
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        subtitles?.let { cueList ->
            if (subtitlesEnabled) {
                AutoUpdatingSubtitleDisplay(
                    subtitles = cueList,
                    currentTimeMs = currentTimeMs,
                    isPlaying = isPlaying,
                    textStyle = textStyle,
                    backgroundColor = backgroundColor
                )
            }
        }
    }
}


/**
 * Loads the content of a subtitle file from the given source.
 * This is implemented in a platform-specific way.
 */
expect suspend fun loadSubtitleContent(src: String): String
