package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Composable surface to render the video frame.
 * Uses a more efficient approach to update frames without relying on the frame counter.
 */
@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier
) {
    // Calculate aspect ratio based on video dimensions
    val aspectRatio = remember(playerState.videoWidth, playerState.videoHeight) {
        if (playerState.videoWidth > 0 && playerState.videoHeight > 0)
            playerState.videoWidth.toFloat() / playerState.videoHeight.toFloat()
        else 16f / 9f
    }

    // Use derived state to directly observe the _currentFrame property from playerState
    // This is more efficient than using the frame counter as a trigger
    val currentFrame by remember {
        derivedStateOf {
            playerState.getLockedComposeImageBitmap()
        }
    }

    // Observe hasMedia and isPlaying to ensure recomposition on these state changes
    val hasMedia by remember { derivedStateOf { playerState.hasMedia } }
    val isPlaying by remember { derivedStateOf { playerState.isPlaying } }

    Box(
        modifier = modifier.onSizeChanged {
            // Notify player state that a resize event has occurred
            playerState.onResized()
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(aspectRatio)
        ) {
            try {
                // Only attempt to draw if we have a valid frame and dimensions
                if (size.width > 0 && size.height > 0 && hasMedia) {
                    currentFrame?.let { bitmap ->
                        drawImage(
                            bitmap,
                            dstSize = IntSize(
                                width = size.width.toInt(),
                                height = size.height.toInt()
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                // Ignore rendering exceptions during resizing
                // In production, you might want to log this for debugging
            }
        }
    }

    // Use LaunchedEffect to handle frame updates more efficiently
    // This triggers recompositions based on playback state changes
    LaunchedEffect(hasMedia, isPlaying) {
        if (hasMedia && isPlaying) {
            // This empty block is sufficient to trigger recomposition
            // when the playback state changes
        }
    }
}