package sample.app.singleplayer

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import kotlinx.coroutines.delay

@Composable
fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun PlayerHeader(
    title: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}

@Composable
fun VideoDisplay(
    playerState: VideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.BottomCenter
    ) {
        VideoPlayerSurface(
            playerState = playerState,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp)),
            contentScale = contentScale
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (playerState.isFullscreen) {
                    var controlsVisible by remember { mutableStateOf(false) }

                    // Reset timer when controls become visible
                    LaunchedEffect(controlsVisible) {
                        if (controlsVisible) {
                            delay(3000) // Hide controls after 3 seconds
                            controlsVisible = false
                        }
                    }

                    // Detect taps to show controls
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { controlsVisible = true }
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        // Show controls when mouse moves
                                        if (event.type == PointerEventType.Move) {
                                            controlsVisible = true
                                        }
                                    }
                                }
                            }
                    ) {
                        // Show controls when visible
                        AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(
                                        color = Color.Black.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .padding(16.dp)
                            ) {
                                // Play/Pause button
                                IconButton(
                                    onClick = {
                                        if (playerState.isPlaying) playerState.pause() else playerState.play()
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                }

                                // Exit fullscreen button
                                IconButton(
                                    onClick = { playerState.toggleFullscreen() }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FullscreenExit,
                                        contentDescription = "Exit Fullscreen",
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (playerState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun TimelineControls(
    playerState: VideoPlayerState
) {
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
                text = playerState.positionText,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = playerState.durationText,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun PrimaryControls(
    playerState: VideoPlayerState,
    videoFileLauncher: () -> Unit,
    onSubtitleDialogRequest: () -> Unit,
    onMetadataDialogRequest: () -> Unit,
    onContentScaleDialogRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledIconButton(
            onClick = { videoFileLauncher() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = "Load Video")
        }
        FilledIconButton(
            onClick = {
                if (playerState.isPlaying) playerState.pause() else playerState.play()
            },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Icon(
                imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playerState.isPlaying) "Pause" else "Play"
            )
        }
        FilledIconButton(
            onClick = { playerState.stop() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Icon(Icons.Default.Stop, contentDescription = "Stop")
        }

        FilledIconButton(
            onClick = { onSubtitleDialogRequest() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Icon(Icons.Default.Subtitles, contentDescription = "Subtitles")
        }

        FilledIconButton(
            onClick = { playerState.toggleFullscreen() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
        }

        FilledIconButton(
            onClick = { onMetadataDialogRequest() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Icon(Icons.Default.Info, contentDescription = "Metadata")
        }

        FilledIconButton(
            onClick = { onContentScaleDialogRequest() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Icon(Icons.Default.AspectRatio, contentDescription = "Content Scale")
        }
    }
}

@Composable
fun VolumeAndPlaybackControls(
    playerState: VideoPlayerState
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Volume control
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
                    text = "${(playerState.volume * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(40.dp)
                )
            }

            // Playback speed control
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Playback Speed",
                    modifier = Modifier.width(50.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Slider(
                    value = playerState.playbackSpeed,
                    onValueChange = { playerState.playbackSpeed = it },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.width(100.dp)
                )
                Text(
                    text = "${(playerState.playbackSpeed * 10).toInt() / 10.0}x",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

@Composable
fun VideoUrlInput(
    videoUrl: String,
    onVideoUrlChange: (String) -> Unit,
    onOpenUrl: () -> Unit
) {
    OutlinedTextField(
        value = videoUrl,
        onValueChange = onVideoUrlChange,
        modifier = Modifier.fillMaxWidth(.7f),
        label = { Text("Video URL") },
        trailingIcon = {
            IconButton(onClick = onOpenUrl) {
                Icon(Icons.Default.PlayCircle, contentDescription = "Open URL")
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
fun AudioLevelDisplay(
    leftLevel: Float,
    rightLevel: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Left: ${leftLevel.toInt()}%")
        Text("Right: ${rightLevel.toInt()}%")
    }
}

@Composable
fun MetadataDisplay(
    playerState: VideoPlayerState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (!playerState.metadata.isAllNull()) {
            // Display metadata properties in a grid layout
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                playerState.metadata.title?.let {
                    MetadataRow("Title", it)
                }
                playerState.metadata.artist?.let {
                    MetadataRow("Artist", it)
                }
                playerState.metadata.width?.let { width ->
                    playerState.metadata.height?.let { height ->
                        MetadataRow("Resolution", "$width × $height")
                    }
                }
                playerState.metadata.frameRate?.let {
                    MetadataRow("Frame Rate", "$it fps")
                }
                playerState.metadata.bitrate?.let {
                    MetadataRow("Bitrate", "${it / 1000} kbps")
                }
                playerState.metadata.mimeType?.let {
                    MetadataRow("Format", it)
                }
                playerState.metadata.audioChannels?.let { channels ->
                    playerState.metadata.audioSampleRate?.let { sampleRate ->
                        MetadataRow("Audio", "$channels channels, ${sampleRate / 1000} kHz")
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorSnackbar(
    error: VideoPlayerError,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Snackbar(
            action = {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.inversePrimary
                    )
                ) {
                    Text("Close")
                }
            },
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ) {
            Text(
                text = when (error) {
                    is VideoPlayerError.CodecError -> "Codec error: ${error.message}"
                    is VideoPlayerError.NetworkError -> "Network error: ${error.message}"
                    is VideoPlayerError.SourceError -> "Source error: ${error.message}"
                    is VideoPlayerError.UnknownError -> "Unknown error: ${error.message}"
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ControlsCard(
    playerState: VideoPlayerState,
    videoUrl: String,
    onVideoUrlChange: (String) -> Unit,
    onOpenUrl: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Volume and playback speed controls
            VolumeAndPlaybackControls(playerState)

            Spacer(modifier = Modifier.height(8.dp))


            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(start = 8.dp)
            ) {

                // Video URL input
                VideoUrlInput(
                    videoUrl = videoUrl,
                    onVideoUrlChange = onVideoUrlChange,
                    onOpenUrl = onOpenUrl
                )
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Loop control
                    Text(
                        text = "Loop",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = playerState.loop,
                        onCheckedChange = { playerState.loop = it }
                    )
                }
            }

        }

        AudioLevelDisplay(
            leftLevel = playerState.leftLevel,
            rightLevel = playerState.rightLevel
        )
    }
}

@Composable
fun MetadataDialog(
    playerState: VideoPlayerState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Video Metadata",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // Use the existing MetadataDisplay component
                MetadataDisplay(playerState = playerState)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", style = MaterialTheme.typography.labelLarge)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 6.dp
    )
}

@Composable
fun ContentScaleDialog(
    currentContentScale: ContentScale,
    onContentScaleSelected: (ContentScale) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Content Scale",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                ContentScaleOption(
                    name = "Fit",
                    description = "Scale the content to fit within the bounds while maintaining aspect ratio",
                    isSelected = currentContentScale == ContentScale.Fit,
                    onClick = { onContentScaleSelected(ContentScale.Fit) }
                )
                ContentScaleOption(
                    name = "Crop",
                    description = "Scale the content to fill the bounds while maintaining aspect ratio",
                    isSelected = currentContentScale == ContentScale.Crop,
                    onClick = { onContentScaleSelected(ContentScale.Crop) }
                )
                ContentScaleOption(
                    name = "Inside",
                    description = "Scale the content to fit within the bounds while maintaining aspect ratio",
                    isSelected = currentContentScale == ContentScale.Inside,
                    onClick = { onContentScaleSelected(ContentScale.Inside) }
                )
                ContentScaleOption(
                    name = "None",
                    description = "Don't scale the content",
                    isSelected = currentContentScale == ContentScale.None,
                    onClick = { onContentScaleSelected(ContentScale.None) }
                )
                ContentScaleOption(
                    name = "Fill Bounds",
                    description = "Scale the content to fill the bounds exactly",
                    isSelected = currentContentScale == ContentScale.FillBounds,
                    onClick = { onContentScaleSelected(ContentScale.FillBounds) }
                )
                ContentScaleOption(
                    name = "Fill Height",
                    description = "Scale the content to fill the height while maintaining aspect ratio",
                    isSelected = currentContentScale == ContentScale.FillHeight,
                    onClick = { onContentScaleSelected(ContentScale.FillHeight) }
                )
                ContentScaleOption(
                    name = "Fill Width",
                    description = "Scale the content to fill the width while maintaining aspect ratio",
                    isSelected = currentContentScale == ContentScale.FillWidth,
                    onClick = { onContentScaleSelected(ContentScale.FillWidth) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", style = MaterialTheme.typography.labelLarge)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 6.dp
    )
}

@Composable
private fun ContentScaleOption(
    name: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick
        )
        Column(
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
