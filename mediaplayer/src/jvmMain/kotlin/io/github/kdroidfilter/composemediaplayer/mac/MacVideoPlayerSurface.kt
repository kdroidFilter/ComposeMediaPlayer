package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

/**
 * A Composable function that renders a video player surface for MacOS.
 * Fills the entire canvas area with the video frame while maintaining aspect ratio.
 *
 * @param playerState The state object that encapsulates the AVPlayer logic for MacOS.
 * @param modifier An optional Modifier for customizing the layout.
 */
@Composable
fun MacVideoPlayerSurface(
    playerState: MacVideoPlayerState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (playerState.hasMedia) {
            val currentFrame by playerState.currentFrameState
            val aspectRatio = playerState.aspectRatio

            currentFrame?.let { frame ->
                    // Draw the video frame to fill the entire canvas area
                    Canvas(
                        modifier = Modifier.fillMaxHeight()
                            .aspectRatio(playerState.aspectRatio),
                    ) {
                        drawImage(
                            image = frame,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }
                }
            }

    }
}
