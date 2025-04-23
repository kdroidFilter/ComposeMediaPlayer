package sample.app.singleplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import sample.app.SubtitleManagementDialog
// Import the extracted composable functions
import sample.app.singleplayer.PlayerHeader
import sample.app.singleplayer.VideoDisplay
import sample.app.singleplayer.TimelineControls
import sample.app.singleplayer.PrimaryControls
import sample.app.singleplayer.ControlsCard
import sample.app.singleplayer.MetadataDisplay
import sample.app.singleplayer.MetadataDialog
import sample.app.singleplayer.ErrorSnackbar

@OptIn(ExperimentalMaterial3Api::class)
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

        // State to show/hide the metadata dialog
        var showMetadataDialog by remember { mutableStateOf(false) }

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
                PlayerHeader(
                    title = "Compose Media Player",
                    isLoading = playerState.isLoading
                )

                // Video display area
                VideoDisplay(
                    playerState = playerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                    onMetadataDialogRequest = { showMetadataDialog = true }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Secondary controls: volume, loop, video URL input
                ControlsCard(
                    playerState = playerState,
                    videoUrl = videoUrl,
                    onVideoUrlChange = { videoUrl = it },
                    onOpenUrl = {
                        if (videoUrl.isNotEmpty()) {
                            playerState.openUri(videoUrl)
                        }
                    }
                )
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
        }
    }
}
