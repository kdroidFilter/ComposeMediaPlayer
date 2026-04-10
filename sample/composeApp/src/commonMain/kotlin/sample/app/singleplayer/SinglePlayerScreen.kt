package sample.app.singleplayer

// Import the extracted composable functions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.DrmConfiguration
import io.github.kdroidfilter.composemediaplayer.DrmType
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.PreviewableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import org.jetbrains.compose.ui.tooling.preview.Preview
import sample.app.SubtitleManagementDialog

@Composable
fun SinglePlayerScreen() {
    SinglePlayerScreenCore(rememberVideoPlayerState())
}

@Composable
@Preview
private fun SinglePlayerScreen_OnPaused_Preview() {
    val playerState = PreviewableVideoPlayerState(isPlaying = false)
    SinglePlayerScreenCore(playerState)
}

@Composable
@Preview
private fun SinglePlayerScreen_OnError_Preview() {
    val playerState = PreviewableVideoPlayerState(
        loop = false,
        error = VideoPlayerError.CodecError("Unable to decode")
    )
    SinglePlayerScreenCore(playerState)
}

@Composable
private fun SinglePlayerScreenCore(playerState: VideoPlayerState) {
    MaterialTheme {
        // Default video URL (non-DRM for basic testing)
        var videoUrl by remember { mutableStateOf("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4") }
        
        // DRM test stream URL
        val drmTestUrl = "https://media.axprod.net/TestVectors/Cmaf/protected_1080p_h264_cbcs/manifest.mpd"

        // State for initial player state (PLAY or PAUSE)
        var initialPlayerState by remember { mutableStateOf<InitialPlayerState>(InitialPlayerState.PLAY) }
        
        // DRM Settings - AxDRM test stream defaults
        var drmEnabled by remember { mutableStateOf(false) }
        var drmType by remember { mutableStateOf("WIDEVINE") }
        var licenseUrl by remember { mutableStateOf("https://drm-widevine-licensing.axtest.net/AcquireLicense") }
        var drmHeaders by remember { mutableStateOf("""{"X-AxDRM-Message": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.ewogICJ2ZXJzaW9uIjogMSwKICAiY29tX2tleV9pZCI6ICI2OWU1NDA4OC1lOWUwLTQ1MzAtOGMxYS0xZWI2ZGNkMGQxNGUiLAogICJtZXNzYWdlIjogewogICAgInR5cGUiOiAiZW50aXRsZW1lbnRfbWVzc2FnZSIsCiAgICAidmVyc2lvbiI6IDIsCiAgICAibGljZW5zZSI6IHsKICAgICAgImFsbG93X3BlcnNpc3RlbmNlIjogdHJ1ZQogICAgfSwKICAgICJjb250ZW50X2tleXNfc291cmNlIjogewogICAgICAiaW5saW5lIjogWwogICAgICAgIHsKICAgICAgICAgICJpZCI6ICIzMDJmODBkZC00MTFlLTQ4ODYtYmNhNS1iYjFmODAxOGEwMjQiLAogICAgICAgICAgImVuY3J5cHRlZF9rZXkiOiAicm9LQWcwdDdKaTFpNDNmd3YremZ0UT09IiwKICAgICAgICAgICJ1c2FnZV9wb2xpY3kiOiAiUG9saWN5IEEiCiAgICAgICAgfQogICAgICBdCiAgICB9LAogICAgImNvbnRlbnRfa2V5X3VzYWdlX3BvbGljaWVzIjogWwogICAgICB7CiAgICAgICAgIm5hbWUiOiAiUG9saWN5IEEiLAogICAgICAgICJwbGF5cmVhZHkiOiB7CiAgICAgICAgICAibWluX2RldmljZV9zZWN1cml0eV9sZXZlbCI6IDE1MCwKICAgICAgICAgICJwbGF5X2VuYWJsZXJzIjogWwogICAgICAgICAgICAiNzg2NjI3RDgtQzJBNi00NEJFLThGODgtMDhBRTI1NUIwMUE3IgogICAgICAgICAgXQogICAgICAgIH0KICAgICAgfQogICAgXQogIH0KfQ._NfhLVY7S6k8TJDWPeMPhUawhympnrk6WAZHOVjER6M"}""") }
        
        // Helper function to parse headers JSON (simple parser)
        fun parseHeaders(): Map<String, String> {
            return try {
                if (drmHeaders.isBlank() || drmHeaders == "{}") {
                    emptyMap()
                } else {
                    // Simple JSON object parser for {"key": "value", ...}
                    val result = mutableMapOf<String, String>()
                    val content = drmHeaders.trim().removePrefix("{").removeSuffix("}")
                    if (content.isNotBlank()) {
                        val pairs = content.split(",")
                        for (pair in pairs) {
                            val keyValue = pair.split(":")
                            if (keyValue.size == 2) {
                                val key = keyValue[0].trim().removeSurrounding("\"")
                                val value = keyValue[1].trim().removeSurrounding("\"")
                                result[key] = value
                            }
                        }
                    }
                    result
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
        
        // Helper function to create DRM config
        fun createDrmConfig(): DrmConfiguration? {
            if (!drmEnabled || licenseUrl.isBlank()) return null
            val type = when (drmType) {
                "WIDEVINE" -> DrmType.WIDEVINE
                "PLAYREADY" -> DrmType.PLAYREADY
                "CLEARKEY" -> DrmType.CLEARKEY
                else -> DrmType.WIDEVINE
            }
            return DrmConfiguration(
                drmType = type,
                licenseUrl = licenseUrl,
                licenseHeaders = parseHeaders()
            )
        }

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
                                    playerState.openUri(videoUrl, createDrmConfig(), initialPlayerState)
                                }
                            },
                            initialPlayerState = initialPlayerState,
                            onInitialPlayerStateChange = { initialPlayerState = it }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // DRM Controls
                        DrmControlsCard(
                            licenseUrl = licenseUrl,
                            onLicenseUrlChange = { licenseUrl = it },
                            drmHeaders = drmHeaders,
                            onDrmHeadersChange = { drmHeaders = it },
                            drmEnabled = drmEnabled,
                            onDrmEnabledChange = { drmEnabled = it },
                            drmType = drmType,
                            onDrmTypeChange = { drmType = it },
                            onLoadTestStream = {
                                videoUrl = drmTestUrl
                            }
                        )
                    }
                }
            } else {
                // Portrait layout (vertical)
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(scrollState)
                ) {
                    // Header with title
                    PlayerHeader(title = "Compose Media Player Sample",)

                    // Video display area
                    VideoDisplay(
                        playerState = playerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
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
                                playerState.openUri(videoUrl, createDrmConfig(), initialPlayerState)
                            }
                        },
                        initialPlayerState = initialPlayerState,
                        onInitialPlayerStateChange = { initialPlayerState = it }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // DRM Controls
                    DrmControlsCard(
                        licenseUrl = licenseUrl,
                        onLicenseUrlChange = { licenseUrl = it },
                        drmHeaders = drmHeaders,
                        onDrmHeadersChange = { drmHeaders = it },
                        drmEnabled = drmEnabled,
                        onDrmEnabledChange = { drmEnabled = it },
                        drmType = drmType,
                        onDrmTypeChange = { drmType = it },
                        onLoadTestStream = {
                            videoUrl = drmTestUrl
                        }
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
