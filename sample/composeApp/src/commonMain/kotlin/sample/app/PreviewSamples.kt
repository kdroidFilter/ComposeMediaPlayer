package sample.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import org.jetbrains.compose.ui.tooling.preview.Preview
import sample.app.singleplayer.ControlsCard
import sample.app.singleplayer.PlayerHeader
import sample.app.singleplayer.PrimaryControls
import sample.app.singleplayer.TimelineControls

@Preview
@Composable
private fun SinglePlayerUiPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val playerState = rememberVideoPlayerState()
            playerState.volume = 0.7f
            playerState.loop = true

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                PlayerHeader(title = "Compose Media Player Sample")

                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.height(160.dp))
                TimelineControls(playerState)

                Spacer(modifier = Modifier.height(16.dp))
                PrimaryControls(
                    playerState = playerState,
                    videoFileLauncher = {},
                    onSubtitleDialogRequest = {},
                    onMetadataDialogRequest = {},
                    onContentScaleDialogRequest = {}
                )

                Spacer(modifier = Modifier.height(16.dp))
                ControlsCard(
                    playerState = playerState,
                    videoUrl = "https://example.com/video.mp4",
                    onVideoUrlChange = {},
                    onOpenUrl = {},
                    initialPlayerState = InitialPlayerState.PLAY,
                    onInitialPlayerStateChange = {}
                )
            }
        }
    }
}
