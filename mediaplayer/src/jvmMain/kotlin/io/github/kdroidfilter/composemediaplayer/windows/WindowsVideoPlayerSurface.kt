package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.toIntSize

@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val bitmap = playerState.currentFrame
        if (bitmap != null) {
            val ratio = if (playerState.videoWidth != 0 && playerState.videoHeight != 0) {
                playerState.videoWidth.toFloat() / playerState.videoHeight.toFloat()
            } else 16f / 9f

            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(ratio)
            ) {
                drawImage(
                    bitmap.asComposeImageBitmap(),
                    dstSize = size.toIntSize()
                )
            }
        } else {
            // Pas de frame => rien
        }
    }
}
