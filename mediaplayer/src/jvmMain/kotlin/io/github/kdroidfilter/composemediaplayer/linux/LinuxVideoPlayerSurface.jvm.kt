package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

/**
 * A composable function that renders a video player surface using GStreamer with offscreen rendering.
 *
 * This function creates a video rendering area using a Compose Canvas to draw video frames
 * that are rendered offscreen by GStreamer. This approach avoids the rendering issues
 * that can occur when using SwingPanel, especially with overlapping UI elements.
 *
 * @param playerState The state object that encapsulates the GStreamer player logic,
 *                    including playback control, timeline management, and video frames.
 * @param modifier An optional `Modifier` for customizing the layout and appearance of the
 *                 composable container. Defaults to an empty `Modifier`.
 */

@Composable
fun LinuxVideoPlayerSurface(
    playerState: LinuxVideoPlayerState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (playerState.hasMedia) {
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(playerState.aspectRatio)
            ) {
                // Draw the current frame if available
                playerState.currentFrame?.let { frame ->
                    drawImage(
                        image = frame,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )
                }
            }
        }
    }
}
