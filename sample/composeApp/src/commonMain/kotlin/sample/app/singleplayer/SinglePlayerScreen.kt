package sample.app.singleplayer

// Import the extracted composable functions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import sample.app.SubtitleManagementDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SinglePlayerScreen() {
    MaterialTheme {
        // Default video URL
        var videoUrl by remember { mutableStateOf("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4") }
        val playerState = rememberVideoPlayerState()
        
        // State for initial player state (PLAY or PAUSE)
        var initialPlayerState by remember { mutableStateOf<InitialPlayerState>(InitialPlayerState.PLAY) }

        // List of subtitle tracks and the currently selected track
        val subtitleTracks = remember { mutableStateListOf<SubtitleTrack>() }
        var selectedSubtitleTrack by remember { mutableStateOf<SubtitleTrack?>(null) }

        // Launcher for selecting a local video file
        val videoFileLauncher = rememberFilePickerLauncher(
            type = FileKitType.Video,
            title = "Select a video"
        ) { file ->
            file?.let {
                playerState.openFile(it, initialPlayerState)
            }
        }

        // Launcher for selecting a local subtitle file (VTT format)
        val subtitleFileLauncher = rememberFilePickerLauncher(
            type = FileKitType.File("vtt", "srt"),
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

        // State to show/hide the metadata dialog
        var showMetadataDialog by remember { mutableStateOf(false) }

        // State to show/hide the content scale dialog
        var showContentScaleDialog by remember { mutableStateOf(false) }

        // State to store the selected content scale
        var selectedContentScale by remember { mutableStateOf<ContentScale>(ContentScale.Fit) }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val isLandscape = maxWidth > maxHeight

            if (isLandscape) {
                // Landscape layout (horizontal)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Left side: Video and Timeline
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        // Header with title
                        PlayerHeader(title = "Compose Media Player Sample",)

                        // Video display area
                        VideoDisplay(
                            playerState = playerState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentScale = selectedContentScale
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Video timeline and slider
                        TimelineControls(playerState = playerState)
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Right side: Controls
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = 48.dp) // Align with video content
                    ) {
                        // Primary controls: load video, play/pause, stop
                        PrimaryControls(
                            playerState = playerState,
                            videoFileLauncher = { videoFileLauncher.launch() },
                            onSubtitleDialogRequest = { showSubtitleDialog = true },
                            onMetadataDialogRequest = { showMetadataDialog = true },
                            onContentScaleDialogRequest = { showContentScaleDialog = true }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Secondary controls: volume, loop, video URL input
                        ControlsCard(
                            playerState = playerState,
                            videoUrl = videoUrl,
                            onVideoUrlChange = { videoUrl = it },
                            onOpenUrl = {
                                if (videoUrl.isNotEmpty()) {
                                    playerState.openUri(videoUrl, initialPlayerState)
                                }
                            },
                            initialPlayerState = initialPlayerState,
                            onInitialPlayerStateChange = { initialPlayerState = it }
                        )
                    }
                }
            } else {
                // Portrait layout (vertical)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header with title
                    PlayerHeader(title = "Compose Media Player Sample",)

                    // Video display area
                    VideoDisplay(
                        playerState = playerState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentScale = selectedContentScale
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Video timeline and slider
                    TimelineControls(playerState = playerState)

                    Spacer(modifier = Modifier.height(16.dp))

                    // Primary controls: load video, play/pause, stop
                    PrimaryControls(
                        playerState = playerState,
                        videoFileLauncher = { videoFileLauncher.launch() },
                        onSubtitleDialogRequest = { showSubtitleDialog = true },
                        onMetadataDialogRequest = { showMetadataDialog = true },
                        onContentScaleDialogRequest = { showContentScaleDialog = true }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Secondary controls: volume, loop, video URL input
                    ControlsCard(
                        playerState = playerState,
                        videoUrl = videoUrl,
                        onVideoUrlChange = { videoUrl = it },
                        onOpenUrl = {
                            if (videoUrl.isNotEmpty()) {
                                playerState.openUri(videoUrl, initialPlayerState)
                            }
                        },
                        initialPlayerState = initialPlayerState,
                        onInitialPlayerStateChange = { initialPlayerState = it }
                    )
                }
            }

            // Animated error Snackbar
            playerState.error?.let { error ->
                ErrorSnackbar(
                    error = error,
                    onDismiss = { playerState.clearError() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
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

            // Metadata dialog
            if (showMetadataDialog) {
                MetadataDialog(
                    playerState = playerState,
                    onDismiss = {
                        showMetadataDialog = false
                    }
                )
            }

            // Content scale dialog
            if (showContentScaleDialog) {
                ContentScaleDialog(
                    currentContentScale = selectedContentScale,
                    onContentScaleSelected = { contentScale ->
                        selectedContentScale = contentScale
                        showContentScaleDialog = false
                    },
                    onDismiss = {
                        showContentScaleDialog = false
                    }
                )
            }
        }
    }
}
