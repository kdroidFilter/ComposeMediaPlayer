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
 * Une approche réactive est utilisée afin de n’avoir besoin que d’observer les mises à jour d’état pertinentes.
 */
@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier
) {
    // Calcul de l'aspect ratio basé sur la taille de la vidéo
    val aspectRatio = remember(playerState.videoWidth, playerState.videoHeight) {
        if (playerState.videoWidth > 0 && playerState.videoHeight > 0)
            playerState.videoWidth.toFloat() / playerState.videoHeight.toFloat()
        else 16f / 9f
    }

    // Observation réactive de la frame courante
    val currentFrame by remember {
        derivedStateOf {
            playerState.getLockedComposeImageBitmap()
        }
    }

    // Observation des états de présence de média et de lecture
    val hasMedia by remember { derivedStateOf { playerState.hasMedia } }
    val isPlaying by remember { derivedStateOf { playerState.isPlaying } }

    Box(
        modifier = modifier.onSizeChanged {
            // Notification de redimensionnement
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
                // Ignorer les exceptions de rendu lors du redimensionnement
            }
        }
    }

    LaunchedEffect(hasMedia, isPlaying) {
        if (hasMedia && isPlaying) {
            // Bloc vide pour déclencher la recomposition lors des changements d'état
        }
    }
}
