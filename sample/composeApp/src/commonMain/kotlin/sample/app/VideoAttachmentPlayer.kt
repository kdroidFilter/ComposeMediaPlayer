package sample.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.*

// Data class to represent a media attachment
data class MediaAttachment(
    val url: String? = null,
    val meta: Meta? = null
) {
    data class Meta(
        val original: Original? = null
    ) {
        data class Original(
            val aspect: Double? = null
        )
    }
}

// Simple ViewModel for the post containing the video
class PostViewModel {
    var volume by mutableStateOf(true)
    var isAutoplayVideos by mutableStateOf(true)
    
    fun toggleVolume(value: Boolean) {
        volume = value
    }
}

@Composable
fun VideoAttachment(
    attachment: MediaAttachment,
    viewModel: PostViewModel,
    onReady: () -> Unit
) {
    val player = rememberVideoPlayerState().apply {
        loop = true
        userDragging = false
    }
    
    // Open the video URL when attachment changes
    LaunchedEffect(attachment) {
        player.openUri(attachment.url.orEmpty())
    }

    // Main video player layout
    Column {
        Box(Modifier.clickable {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }) {
            VideoPlayerSurface(
                playerState = player,
                modifier = Modifier
                    .fillMaxWidth()
                    .run {
                        val aspect = attachment.meta?.original?.aspect?.toFloat()
                        if (aspect != null) aspectRatio(aspect) else this
                    }
            )
            
            // Volume control button
            if (player.metadata.audioChannels != null) {
                IconButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    onClick = {
                        viewModel.toggleVolume(!viewModel.volume)
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    if (viewModel.volume) {
                        Icon(
                            Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "Volume on",
                            Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.VolumeOff,
                            contentDescription = "Volume off",
                            Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        
        // Progress indicator
        LinearProgressIndicator(
            progress = { player.sliderPos / 1000 },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.background
        )
    }

    // Call onReady when the player starts playing
    LaunchedEffect(player.isPlaying) {
        if (player.isPlaying) onReady()
    }

    // Update player volume when viewModel.volume changes
    LaunchedEffect(viewModel.volume) {
        player.volume = if (viewModel.volume) 1f else 0f
    }

    // Auto-play based on viewModel setting
    LaunchedEffect(Unit) {
        if (viewModel.isAutoplayVideos) {
            player.play()
        }
    }
}

@Composable
fun VideoAttachmentPlayerScreen() {
    val viewModel = remember { PostViewModel() }
    var customUrl by remember { mutableStateOf("https://pixey.org/storage/m/_v2/515736985118386604/549719332-a3f277/PFmm1pLteJcX/ifPy1vg6Co8YS0Ah4IlQel5lOgvVv4wrnyCtE0Dx.mp4") }
    var attachment by remember { 
        mutableStateOf(
            MediaAttachment(
                url = customUrl,
                meta = MediaAttachment.Meta(
                    original = MediaAttachment.Meta.Original(
                        aspect = 16.0/9.0
                    )
                )
            )
        ) 
    }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        item {
            Text(
                text = "Video Attachment Player",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        item {

            // Video player
            VideoAttachment(
                attachment = attachment,
                viewModel = viewModel,
                onReady = { /* Do something when video is ready */ }
            )
        }
        item {


            Spacer(modifier = Modifier.height(16.dp))

            // URL input
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("Enter video URL") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        attachment = attachment.copy(url = customUrl)
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Load URL")
                    }
                }
            )
        }
        item {

            Spacer(modifier = Modifier.height(16.dp))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Autoplay switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Autoplay")
                    Switch(
                        checked = viewModel.isAutoplayVideos,
                        onCheckedChange = { viewModel.isAutoplayVideos = it }
                    )
                }

                // Volume switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume")
                    Switch(
                        checked = viewModel.volume,
                        onCheckedChange = { viewModel.toggleVolume(it) }
                    )
                }
            }
        }
    }
}