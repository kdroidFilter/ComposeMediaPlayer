package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * A composable function that displays subtitles.
 *
 * @param subtitles The subtitle cue list to display
 * @param currentTimeMs The current playback time in milliseconds
 * @param modifier The modifier to be applied to the layout
 * @param textStyle The text style to be applied to the subtitle text
 * @param backgroundColor The background color of the subtitle box
 */
@Composable
fun SubtitleDisplay(
    subtitles: SubtitleCueList,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center
    ),
    backgroundColor: Color = Color.Black.copy(alpha = 0.5f)
) {
    // Get active cues at the current time
    val activeCues = subtitles.getActiveCues(currentTimeMs)

    if (activeCues.isNotEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Join all active cue texts with line breaks
            val subtitleText = activeCues.joinToString("\n") { it.text }

            BasicText(
                text = subtitleText,
                style = textStyle,
                modifier = Modifier
                    .background(backgroundColor, shape = RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * A composable function that displays subtitles with automatic time tracking.
 * This version automatically updates the display based on the current playback time.
 *
 * @param subtitles The subtitle cue list to display
 * @param currentTimeMs The current playback time in milliseconds
 * @param isPlaying Whether the video is currently playing
 * @param modifier The modifier to be applied to the layout
 * @param textStyle The text style to be applied to the subtitle text
 * @param backgroundColor The background color of the subtitle box
 */
@Composable
fun AutoUpdatingSubtitleDisplay(
    subtitles: SubtitleCueList,
    currentTimeMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center
    ),
    backgroundColor: Color = Color.Black.copy(alpha = 0.5f)
) {
    var displayTimeMs by remember { mutableStateOf(currentTimeMs) }

    // Update display time when currentTimeMs changes
    LaunchedEffect(currentTimeMs) {
        displayTimeMs = currentTimeMs
    }

    // Periodically update display time when playing
    LaunchedEffect(isPlaying, currentTimeMs) {
        if (isPlaying) {
            var mark = TimeSource.Monotonic.markNow()
            while (true) {
                delay(16) // ~60fps
                val elapsed = mark.elapsedNow().inWholeMilliseconds
                mark = TimeSource.Monotonic.markNow()
                displayTimeMs += elapsed
            }
        }
    }

    SubtitleDisplay(
        subtitles = subtitles,
        currentTimeMs = displayTimeMs,
        modifier = modifier,
        textStyle = textStyle,
        backgroundColor = backgroundColor
    )
}
