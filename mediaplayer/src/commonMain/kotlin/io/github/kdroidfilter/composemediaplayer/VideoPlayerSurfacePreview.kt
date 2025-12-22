package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

@Composable
internal fun VideoPlayerSurfacePreview(
    modifier: Modifier,
    message: String = "VideoPlayerSurface (preview)",
    overlay: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = message,
            style = TextStyle(color = Color.Gray)
        )
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }
}
