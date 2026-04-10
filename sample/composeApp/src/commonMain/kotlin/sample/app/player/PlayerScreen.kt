package sample.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(modifier: Modifier = Modifier) {
    val playerState = rememberVideoPlayerState()
    val scope = rememberCoroutineScope()

    var videoUrl by remember { mutableStateOf(SAMPLE_VIDEOS.first().second) }
    var initialPlayerState by remember { mutableStateOf(InitialPlayerState.PLAY) }
    val subtitleTracks = remember { mutableStateListOf<SubtitleTrack>() }
    var selectedSubtitleTrack by remember { mutableStateOf<SubtitleTrack?>(null) }
    var selectedContentScale by remember { mutableStateOf(ContentScale.Fit) }

    var controlsVisible by remember { mutableStateOf(true) }
    var showSourceSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }

    val videoFileLauncher = rememberFilePickerLauncher(type = FileKitType.Video) { file ->
        file?.let { playerState.openFile(it, initialPlayerState) }
    }
    val subtitleFileLauncher = rememberFilePickerLauncher(
        type = FileKitType.File("vtt", "srt"),
    ) { file ->
        file?.let {
            val track = SubtitleTrack(label = it.name, language = "en", src = it.getUri())
            subtitleTracks.add(track)
            selectedSubtitleTrack = track
            playerState.selectSubtitleTrack(track)
        }
    }

    // Auto-hide controls when playing
    LaunchedEffect(controlsVisible, playerState.isPlaying) {
        if (controlsVisible && playerState.isPlaying) {
            delay(4000)
            controlsVisible = false
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        // Video fills the entire area
        VideoPlayerSurface(
            playerState = playerState,
            modifier = Modifier.fillMaxSize(),
            contentScale = selectedContentScale,
        ) {
            if (playerState.isFullscreen) {
                FullscreenOverlay(playerState)
            }
        }

        // Empty state placeholder
        if (!playerState.hasMedia && !playerState.isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Load a video to get started",
                    color = Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Loading
        if (playerState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.8f),
                strokeWidth = 3.dp,
            )
        }

        // Tap handler + animated controls overlay
        if (!playerState.isFullscreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { controlsVisible = !controlsVisible },
            ) {
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(tween(250)),
                    exit = fadeOut(tween(250)),
                ) {
                    ControlsOverlay(
                        playerState = playerState,
                        onSourceClick = { showSourceSheet = true },
                        onSubtitlesClick = { showSubtitleSheet = true },
                        onSettingsClick = { showSettingsSheet = true },
                        onPipClick = { scope.launch { playerState.enterPip() } },
                    )
                }
            }
        }

        // Error snackbar
        playerState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { playerState.clearError() }) { Text("Dismiss") }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    text = when (error) {
                        is VideoPlayerError.CodecError -> "Codec: ${error.message}"
                        is VideoPlayerError.NetworkError -> "Network: ${error.message}"
                        is VideoPlayerError.SourceError -> "Source: ${error.message}"
                        is VideoPlayerError.UnknownError -> "Error: ${error.message}"
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // Bottom sheets
    if (showSourceSheet) {
        MediaSourceSheet(
            videoUrl = videoUrl,
            onUrlChange = { videoUrl = it },
            onLoadUrl = {
                if (videoUrl.isNotEmpty()) playerState.openUri(videoUrl, initialPlayerState)
                showSourceSheet = false
            },
            onPickFile = {
                videoFileLauncher.launch()
                showSourceSheet = false
            },
            onSelectPreset = { url ->
                videoUrl = url
                playerState.openUri(url, initialPlayerState)
                showSourceSheet = false
            },
            onDismiss = { showSourceSheet = false },
        )
    }
    if (showSettingsSheet) {
        SettingsSheet(
            playerState = playerState,
            selectedContentScale = selectedContentScale,
            onContentScaleChange = { selectedContentScale = it },
            initialPlayerState = initialPlayerState,
            onInitialPlayerStateChange = { initialPlayerState = it },
            onDismiss = { showSettingsSheet = false },
        )
    }
    if (showSubtitleSheet) {
        SubtitleSheet(
            subtitleTracks = subtitleTracks,
            selectedTrack = selectedSubtitleTrack,
            onTrackSelected = { track ->
                selectedSubtitleTrack = track
                playerState.selectSubtitleTrack(track)
            },
            onDisableSubtitles = {
                selectedSubtitleTrack = null
                playerState.disableSubtitles()
            },
            onPickFile = { subtitleFileLauncher.launch() },
            onAddTrack = { track ->
                subtitleTracks.add(track)
                selectedSubtitleTrack = track
                playerState.selectSubtitleTrack(track)
            },
            onDismiss = { showSubtitleSheet = false },
        )
    }
}

// region Overlay controls

@Composable
private fun ControlsOverlay(
    playerState: VideoPlayerState,
    onSourceClick: () -> Unit,
    onSubtitlesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPipClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.18f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                    ),
                ),
        )

        // Bottom gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                    ),
                ),
        )

        // Top bar: title
        playerState.metadata.title?.let { title ->
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp)
                    .fillMaxWidth(0.7f),
            )
        }

        // Center: large play/pause (only when media is loaded)
        if (playerState.hasMedia) {
            FilledIconButton(
                onClick = {
                    if (playerState.isPlaying) playerState.pause() else playerState.play()
                },
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.Center),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // Bottom: seekbar + time + actions
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Seek bar
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
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
            )

            // Time + action icons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${playerState.positionText}  /  ${playerState.durationText}",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    OverlayIconButton(onClick = onSourceClick) {
                        Icon(Icons.Default.UploadFile, "Source", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    OverlayIconButton(onClick = onSubtitlesClick) {
                        Icon(Icons.Default.Subtitles, "Subtitles", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    OverlayIconButton(onClick = { playerState.toggleFullscreen() }) {
                        Icon(Icons.Default.Fullscreen, "Fullscreen", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    OverlayIconButton(onClick = onPipClick) {
                        Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    OverlayIconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, "Settings", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        content()
    }
}

// endregion

// region Fullscreen overlay

@Composable
private fun FullscreenOverlay(playerState: VideoPlayerState) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            delay(3000)
            visible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { visible = true }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move) visible = true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.large)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
            ) {
                IconButton(onClick = {
                    if (playerState.isPlaying) playerState.pause() else playerState.play()
                }) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
                IconButton(onClick = { playerState.toggleFullscreen() }) {
                    Icon(
                        Icons.Default.FullscreenExit,
                        contentDescription = "Exit fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
    }
}

// endregion

internal val SAMPLE_VIDEOS = listOf(
    "Big Buck Bunny" to "https://media.w3.org/2010/05/bunny/trailer.mp4",
    "Sintel" to "https://media.w3.org/2010/05/sintel/trailer.mp4",
    "W3C Test Video" to "https://media.w3.org/2010/05/video/movie_300.mp4",
    "Big Buck Bunny (clip)" to "https://www.w3schools.com/html/mov_bbb.mp4",
    "Sample Video" to "https://archive.org/download/big-bunny-sample-video/SampleVideo.mp4",
    "Big Buck Bunny (full)" to "https://media.w3.org/2010/05/bunny/movie.mp4",
)
