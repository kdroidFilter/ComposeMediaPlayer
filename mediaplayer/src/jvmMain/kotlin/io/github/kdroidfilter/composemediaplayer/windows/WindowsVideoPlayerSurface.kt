package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize

@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val bitmap = playerState.currentFrame
        // Observe frameCounter to trigger recomposition when it changes
        val frameCounter = playerState.frameCounter

        if (bitmap != null) {
            val ratio = if (playerState.videoWidth != 0 && playerState.videoHeight != 0) {
                playerState.videoWidth.toFloat() / playerState.videoHeight.toFloat()
            } else 16f / 9f

            // Wrap the entire Canvas with key to force recomposition when frameCounter changes
            key(frameCounter) {
                Canvas(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(ratio)
                        .onSizeChanged { canvasSize = it.toSize() }
                ) {
                    if (size.width > 0 && size.height > 0) {
                        drawImage(
                            bitmap.asComposeImageBitmap(),
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
}
