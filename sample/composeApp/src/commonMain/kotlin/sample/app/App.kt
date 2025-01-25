package sample.app

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialog.PickerType
import io.github.vinceglb.filekit.dialog.compose.rememberFilePickerLauncher

@Composable
fun App() {
    MaterialTheme {
        var url by remember {
            mutableStateOf("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
        }
        val playerState = rememberVideoPlayerState()

        val fileKitLauncher = rememberFilePickerLauncher(
            type = PickerType.Video,
            title = "Select a Video File",
            onResult = { file ->
                file?.let { url = it.getUri() }
                // file?.let { playerState.openFile(it) }
                // Or: file?.let { playerState.openUri(it.getUri()) }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // En-tête
                Text(
                    "Compose Media Player",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Zone vidéo
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (playerState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        VideoPlayerSurface(
                            playerState = playerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp)),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Contrôles principaux
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = { fileKitLauncher.launch() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.UploadFile, "Pick a file")
                    }

                    FilledIconButton(
                        onClick = {
                            if (playerState.isPlaying) playerState.pause()
                            else playerState.play()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                        )
                    }

                    FilledIconButton(
                        onClick = { playerState.stop() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Icon(Icons.Default.Stop, "Stop")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Timeline
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = playerState.sliderPos,
                        onValueChange = {
                            playerState.sliderPos = it
                            playerState.userDragging = true
                        },
                        onValueChangeFinished = {
                            playerState.userDragging = false
                            playerState.seekTo(playerState.sliderPos)
                        },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            playerState.positionText,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            playerState.durationText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Carte des contrôles secondaires
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.width(200.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (playerState.volume > 0f) {
                                            playerState.volume = 0f
                                        } else {
                                            playerState.volume = 1f
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (playerState.volume > 0f)
                                            Icons.AutoMirrored.Filled.VolumeUp
                                        else
                                            Icons.AutoMirrored.Filled.VolumeOff,
                                        contentDescription = "Volume",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Slider(
                                    value = playerState.volume,
                                    onValueChange = { playerState.volume = it },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.width(100.dp)
                                )

                                Text(
                                    "${(playerState.volume * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(40.dp)
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    "Loop",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Switch(
                                    checked = playerState.loop,
                                    onCheckedChange = { playerState.loop = it }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Video URL") },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (url.isNotEmpty()) {
                                            playerState.openUri(url)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.PlayCircle, "Open URL")
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("L: ${playerState.leftLevel.toInt()}%")
                        Text("R: ${playerState.rightLevel.toInt()}%")
                    }
                }
            }

            // Snackbar d'erreur repositionné
            playerState.error?.let { error ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Snackbar(
                        action = {
                            TextButton(
                                onClick = { playerState.clearError() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.inversePrimary
                                )
                            ) {
                                Text("Dismiss")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        Text(
                            when (error) {
                                is VideoPlayerError.CodecError -> "Codec Error: ${error.message}"
                                is VideoPlayerError.NetworkError -> "Network Error: ${error.message}"
                                is VideoPlayerError.SourceError -> "Source Error: ${error.message}"
                                is VideoPlayerError.UnknownError -> "Unknown Error: ${error.message}"
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

expect fun PlatformFile.getUri(): String
