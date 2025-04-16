package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Surface de rendu pour afficher la frame vidéo.
 * Utilise la même logique que MacVideoPlayerSurface et LinuxPlayerSurface.
 */
@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.onSizeChanged {
            // Notification de redimensionnement
            playerState.onResized()
        },
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
