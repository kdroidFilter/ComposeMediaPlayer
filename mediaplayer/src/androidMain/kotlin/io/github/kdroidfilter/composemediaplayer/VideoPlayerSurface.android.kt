package io.github.kdroidfilter.composemediaplayer

import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView

@UnstableApi
@Composable
actual fun VideoPlayerSurface(playerState: VideoPlayerState, modifier: Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (playerState.hasMedia) {
            AndroidView(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(playerState.aspectRatio),
                factory = { context ->
                    // Create PlayerView with subtitles support
                    PlayerView(context).apply {
                        // Attach the player from the state
                        player = playerState.exoPlayer
                        useController = false
                        defaultArtwork = null
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        // Set resize mode to fit (maintains aspect ratio)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

                        // Configure subtitle view
                        subtitleView?.apply {
                            setStyle(CaptionStyleCompat.DEFAULT)
                            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) //todo let user change subtitle size
                        }

                        // Attach this view to the player state
                        playerState.attachPlayerView(this)
                    }
                },
                update = { playerView ->
                    // No need to update visibility as the Box is only shown when hasMedia is true
                }
            )
        }
    }
}
