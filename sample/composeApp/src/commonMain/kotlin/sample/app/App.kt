package sample.app

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.*
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name

// Define screens for navigation
enum class Screen {
    SinglePlayer, MultiPlayer, VideoAttachmentPlayer
}

@Composable
fun App() {
    MaterialTheme {
        // Navigation state
        var currentScreen by remember { mutableStateOf(Screen.SinglePlayer) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Single Player") },
                        label = { Text("Single Player") },
                        selected = currentScreen == Screen.SinglePlayer,
                        onClick = { currentScreen = Screen.SinglePlayer }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Multi Player") },
                        label = { Text("Multi Player") },
                        selected = currentScreen == Screen.MultiPlayer,
                        onClick = { currentScreen = Screen.MultiPlayer }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Subtitles, contentDescription = "Video Attachment") },
                        label = { Text("Video Attachment") },
                        selected = currentScreen == Screen.VideoAttachmentPlayer,
                        onClick = { currentScreen = Screen.VideoAttachmentPlayer }
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (currentScreen) {
                    Screen.SinglePlayer -> SinglePlayerScreen()
                    Screen.MultiPlayer -> MultiPlayerScreen()
                    Screen.VideoAttachmentPlayer -> VideoAttachmentPlayerScreen()
                }
            }
        }
    }
}

@Composable
fun SinglePlayerScreen() {
    MaterialTheme {
        // Default video URL
        var videoUrl by remember { mutableStateOf("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4") }
        val playerState = rememberVideoPlayerState()

        // List of subtitle tracks and the currently selected track
        val subtitleTracks = remember { mutableStateListOf<SubtitleTrack>() }
        var selectedSubtitleTrack by remember { mutableStateOf<SubtitleTrack?>(null) }

        // Launcher for selecting a local video file
        val videoFileLauncher = rememberFilePickerLauncher(
            type = FileKitType.Video,
            title = "Select a video"
        ) { file ->
            file?.let {
                playerState.openFile(it)
            }
        }

        // Launcher for selecting a local subtitle file (VTT format)
        val subtitleFileLauncher = rememberFilePickerLauncher(
            type = FileKitType.File("vtt"),
            title = "Select a subtitle file"
        ) { file ->
            file?.let {
                val subtitleUri = it.getUri()
                val track = SubtitleTrack(
                    label = it.name,
                    language = "en",
                    src = subtitleUri
                )
                // Add the track to the list and select it
                subtitleTracks.add(track)
                selectedSubtitleTrack = track
                playerState.selectSubtitleTrack(track)
            }
        }

        // State to show/hide the subtitle management dialog
        var showSubtitleDialog by remember { mutableStateOf(false) }

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
                // Header with title and loading indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Compose Media Player",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    if (playerState.isLoading) {
                        CircularProgressIndicator()
                    }
                }

                // Video display area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.BottomCenter
                ) {

                    VideoPlayerSurface(
                        playerState = playerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                    )

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

                Spacer(modifier = Modifier.height(16.dp))

                // Video timeline and slider
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

                Spacer(modifier = Modifier.height(16.dp))

                // Primary controls: load video, play/pause, stop
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = { videoFileLauncher.launch() },
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
                        onClick = { showSubtitleDialog = true },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Subtitles, contentDescription = "Subtitles")
                    }

                }

                Spacer(modifier = Modifier.height(16.dp))

                // Secondary controls: volume, loop, video URL input and subtitle management dialog trigger
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
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
                            // Loop control
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
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
                        Spacer(modifier = Modifier.height(8.dp))
                        // Video URL input
                        OutlinedTextField(
                            value = videoUrl,
                            onValueChange = { videoUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Video URL") },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (videoUrl.isNotEmpty()) {
                                            playerState.openUri(videoUrl)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = "Open URL")
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Left: ${playerState.leftLevel.toInt()}%")
                        Text("Right: ${playerState.rightLevel.toInt()}%")
                    }
                }

            }

            // Animated error Snackbar
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

            // Subtitle management dialog
            if (showSubtitleDialog) {
                SubtitleManagementDialog(
                    subtitleTracks = subtitleTracks,
                    selectedSubtitleTrack = selectedSubtitleTrack,
                    onSubtitleSelected = { track ->
                        selectedSubtitleTrack = track
                        playerState.selectSubtitleTrack(track)
                    },
                    onDisableSubtitles = {
                        selectedSubtitleTrack = null
                        playerState.disableSubtitles()
                    },
                    subtitleFileLauncher = { subtitleFileLauncher.launch() },
                    onDismiss = {
                        showSubtitleDialog = false
                    }
                )
            }
        }
    }
}

@Composable
fun SubtitleManagementDialog(
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleTrack: SubtitleTrack?,
    onSubtitleSelected: (SubtitleTrack) -> Unit,
    onDisableSubtitles: () -> Unit,
    subtitleFileLauncher: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Initial value for the subtitle URL
    var subtitleUrl by remember {
        mutableStateOf("https://raw.githubusercontent.com/kdroidFilter/ComposeMediaPlayer/refs/heads/master/assets/subtitles/en.vtt")
    }
    var dropdownExpanded by remember { mutableStateOf(false) }
    // Variable to store the button width
    var buttonWidth by remember { mutableStateOf(0.dp) }
    // LocalDensity for converting between pixels and dp
    val density = LocalDensity.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Subtitle Management",
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
                // Section to add a subtitle via URL
                Text(
                    text = "Add via URL",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = subtitleUrl,
                        onValueChange = { subtitleUrl = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Subtitle URL") },
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ElevatedButton(
                        onClick = {
                            if (subtitleUrl.isNotBlank()) {
                                val track = SubtitleTrack(
                                    label = "URL Subtitles",
                                    language = "en",
                                    src = subtitleUrl
                                )
                                // Add the new subtitle track and select it
                                (subtitleTracks as? MutableList)?.add(track)
                                onSubtitleSelected(track)
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Button to choose a local file
                Text(
                    text = "Or choose a local file",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                ElevatedButton(
                    onClick = subtitleFileLauncher,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Local file")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select File")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Dropdown menu to select an existing subtitle track or disable subtitles
                if (subtitleTracks.isNotEmpty()) {
                    Text(
                        text = "Select a subtitle track",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box {
                        ElevatedButton(
                            onClick = { dropdownExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    // Convert the button width from pixels to dp
                                    buttonWidth = with(density) { coordinates.size.width.toDp() }
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = "Subtitles"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Current: ${selectedSubtitleTrack?.label ?: "None"}"
                            )
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.width(buttonWidth)
                        ) {
                            subtitleTracks.forEach { track ->
                                DropdownMenuItem(
                                    onClick = {
                                        onSubtitleSelected(track)
                                        dropdownExpanded = false
                                    },
                                    text = { Text(track.label) }
                                )
                            }
                            DropdownMenuItem(
                                onClick = {
                                    onDisableSubtitles()
                                    dropdownExpanded = false
                                },
                                text = { Text("Disable Subtitles") }
                            )
                        }
                    }
                }
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
fun MultiPlayerScreen() {
    // List of video URLs
    val videoUrls = listOf(
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"
    )

    // Create player states for each video
    val playerStates = videoUrls.map { url ->
        val state = rememberVideoPlayerState()
        LaunchedEffect(url) {
            state.openUri(url)
        }
        state
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Multi Video Player",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // List of videos
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(playerStates.zip(videoUrls)) { (playerState, url) ->
                MultiVideoItem(playerState = playerState, videoUrl = url)
            }
        }
    }
}

@Composable
fun MultiVideoItem(playerState: VideoPlayerState, videoUrl: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // Video title
            Text(
                text = videoUrl.substringAfterLast('/'),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Video player
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier.fillMaxSize()
                )

                if (playerState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Pause button
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

                // Stop button
                FilledIconButton(
                    onClick = { playerState.stop() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }

                // Volume control
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val currentVolume = playerState.volume
                            if (currentVolume > 0f) {
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
                        onValueChange = { newVolume -> 
                            playerState.volume = newVolume
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.width(100.dp)
                    )
                }
            }
        }
    }
}
