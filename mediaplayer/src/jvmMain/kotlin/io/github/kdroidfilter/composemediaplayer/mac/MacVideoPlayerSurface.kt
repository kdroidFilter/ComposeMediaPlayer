package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs


/**
 * A Composable function that renders a video player surface for MacOS.
 * Fills the entire canvas area with the video frame while maintaining aspect ratio.
 *
 * @param playerState The state object that encapsulates the AVPlayer logic for MacOS.
 * @param modifier An optional Modifier for customizing the layout.
 * @param isInFullscreenWindow Whether this surface is already being displayed in a fullscreen window.
 */
@Composable
fun MacVideoPlayerSurface(
    playerState: MacVideoPlayerState,
    modifier: Modifier = Modifier,
    isInFullscreenWindow: Boolean = false,
) {
    Box(
        modifier = modifier.onSizeChanged {
            // If MacVideoPlayerState had an onResized method, we would call it here
        },
        contentAlignment = Alignment.Center
    ) {
        if (playerState.hasMedia) {
            // Force recomposition when currentFrameState changes
            val currentFrame by remember(playerState) { playerState.currentFrameState }

            currentFrame?.let { frame ->
                Canvas(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(playerState.aspectRatio),
                ) {
                    drawImage(
                        image = frame,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )
                }
            }

            // Add Compose-based subtitle layer
            if (playerState.subtitlesEnabled && playerState.currentSubtitleTrack != null) {
                // Calculate current time in milliseconds
                val currentTimeMs = (playerState.sliderPos / 1000f * 
                    playerState.durationText.toTimeMs()).toLong()

                // Calculate duration in milliseconds
                val durationMs = playerState.durationText.toTimeMs()

                ComposeSubtitleLayer(
                    currentTimeMs = currentTimeMs,
                    durationMs = durationMs,
                    isPlaying = playerState.isPlaying,
                    subtitleTrack = playerState.currentSubtitleTrack,
                    subtitlesEnabled = playerState.subtitlesEnabled,
                    textStyle = playerState.subtitleTextStyle,
                    backgroundColor = playerState.subtitleBackgroundColor
                )
            }
        }
    }

    if (playerState.isFullscreen && !isInFullscreenWindow) {
        openFullscreenWindow(playerState)
    }
}
