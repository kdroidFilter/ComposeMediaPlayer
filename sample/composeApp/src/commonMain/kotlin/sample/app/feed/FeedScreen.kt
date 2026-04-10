package sample.app.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

private data class Post(
    val author: String,
    val handle: String,
    val text: String,
    val videoUrl: String,
    val likes: String,
    val comments: String,
    val timeAgo: String,
)

private val posts = listOf(
    Post(
        author = "Blender Foundation",
        handle = "@blender",
        text = "Big Buck Bunny \u2014 a short animated film showcasing open-source 3D creation.",
        videoUrl = "https://media.w3.org/2010/05/bunny/trailer.mp4",
        likes = "2.4K",
        comments = "128",
        timeAgo = "2h",
    ),
    Post(
        author = "Blender Foundation",
        handle = "@blender",
        text = "Sintel \u2014 an independently produced short film made with Blender.",
        videoUrl = "https://media.w3.org/2010/05/sintel/trailer.mp4",
        likes = "1.8K",
        comments = "94",
        timeAgo = "5h",
    ),
    Post(
        author = "W3C",
        handle = "@w3c",
        text = "A short test video for web media playback compatibility.",
        videoUrl = "https://media.w3.org/2010/05/video/movie_300.mp4",
        likes = "956",
        comments = "42",
        timeAgo = "1d",
    ),
)

@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    var muted by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val columns = when {
            maxWidth >= 1200.dp -> 3
            maxWidth >= 700.dp -> 2
            else -> 1
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header (full width)
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Feed",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    FilledTonalIconButton(onClick = { muted = !muted }) {
                        Icon(
                            imageVector = if (muted)
                                Icons.AutoMirrored.Outlined.VolumeOff
                            else
                                Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "Toggle mute",
                        )
                    }
                }
            }

            items(posts) { post ->
                FeedPostCard(post = post, isMuted = muted, onMuteToggle = { muted = it })
            }
        }
    }
}

@Composable
private fun FeedPostCard(post: Post, isMuted: Boolean, onMuteToggle: (Boolean) -> Unit) {
    val playerState = rememberVideoPlayerState()

    LaunchedEffect(post.videoUrl) {
        playerState.openUri(post.videoUrl)
        playerState.loop = true
        playerState.play()
    }

    LaunchedEffect(isMuted) {
        playerState.volume = if (isMuted) 0f else 1f
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            // Author
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = post.author.first().toString(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(post.author, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        post.handle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    post.timeAgo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Caption
            Text(
                text = post.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )

            // Video
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (playerState.isPlaying) playerState.pause() else playerState.play()
                    },
            ) {
                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )

                // Play indicator
                if (!playerState.isPlaying && playerState.hasMedia) {
                    FilledIconButton(
                        onClick = { playerState.play() },
                        modifier = Modifier.align(Alignment.Center).size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, "Play", modifier = Modifier.size(28.dp))
                    }
                }

                // Volume toggle
                if (playerState.metadata.audioChannels != null) {
                    FilledTonalIconButton(
                        onClick = { onMuteToggle(!isMuted) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (isMuted)
                                Icons.AutoMirrored.Outlined.VolumeOff
                            else
                                Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "Volume",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Progress
            LinearProgressIndicator(
                progress = { playerState.sliderPos / 1000f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            // Engagement
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                EngagementChip(Icons.Default.FavoriteBorder, post.likes)
                EngagementChip(Icons.Default.ChatBubble, post.comments)
                EngagementChip(Icons.Default.Share, "Share")
            }
        }
    }
}

@Composable
private fun EngagementChip(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
