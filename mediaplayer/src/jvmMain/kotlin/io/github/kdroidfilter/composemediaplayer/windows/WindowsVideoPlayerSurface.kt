package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Composable surface to render the video frame.
 * The drawImage call is encapsulated in a try/catch block to handle exceptions during resizing.
 */
@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier
) {
    // Calculate aspect ratio based on video dimensions
    val aspectRatio = remember(playerState.videoWidth, playerState.videoHeight) {
        if (playerState.videoWidth != 0 && playerState.videoHeight != 0)
            playerState.videoWidth.toFloat() / playerState.videoHeight.toFloat()
        else 16f / 9f
    }

    var currentImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Update image bitmap when a new frame is available
    LaunchedEffect(playerState.frameCounter) {
        currentImageBitmap = playerState.getLockedComposeImageBitmap()
    }

    Box(
        modifier = modifier.onSizeChanged {
            // Notify player state that a resize event has occurred
            playerState.onResized()
        },
        contentAlignment = Alignment.Center
    ) {
        currentImageBitmap?.let { imageBitmap ->
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(aspectRatio)
            ) {
                try {
                    if (size.width > 0 && size.height > 0) {
                        drawImage(
                            imageBitmap,
                            dstSize = IntSize(
                                width = size.width.toInt(),
                                height = size.height.toInt()
                            )
                        )
                    }
                } catch (e: Throwable) {
                    // Ignore rendering exceptions during resizing.
                }
            }
        }
    }
}
