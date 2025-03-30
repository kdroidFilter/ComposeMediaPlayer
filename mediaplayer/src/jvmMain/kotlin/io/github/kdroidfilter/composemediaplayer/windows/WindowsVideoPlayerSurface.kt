package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize

@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier
) {
    // Calculate aspect ratio once and remember it
    val aspectRatio = remember(playerState.videoWidth, playerState.videoHeight) {
        if (playerState.videoWidth != 0 && playerState.videoHeight != 0) {
            playerState.videoWidth.toFloat() / playerState.videoHeight.toFloat()
        } else {
            16f / 9f
        }
    }

    // Convert bitmap to ComposeImageBitmap once and remember it
    var currentImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Only update the ImageBitmap when bitmap or frameCounter changes
    LaunchedEffect(playerState.frameCounter) {
        playerState.currentFrame?.let {
            currentImageBitmap = it.asComposeImageBitmap()
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        currentImageBitmap?.let { imageBitmap ->
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(aspectRatio)
            ) {
                if (size.width > 0 && size.height > 0) {
                    drawImage(
                        imageBitmap,
                        dstSize = IntSize(
                            width = size.width.toInt(),
                            height = size.height.toInt()
                        )
                    )
                }
            }
        }
    }
}