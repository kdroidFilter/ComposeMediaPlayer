package sample.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.audio.AudioPlayerState
import io.github.kdroidfilter.composemediaplayer.audio.ErrorListener
import io.github.kdroidfilter.composemediaplayer.audio.rememberAudioPlayerLiveState
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name

@Composable
fun AudioPlayerScreen() {
    AudioPlayerScreenCore()
}

@Composable
private fun AudioPlayerScreenCore() {
    val audioState = rememberAudioPlayerLiveState()
    var streamUrl by remember { mutableStateOf("https://download.samplelib.com/wav/sample-12s.wav") }
    var selectedSource by remember { mutableStateOf(AudioSource.Stream) }
    var selectedFile by remember { mutableStateOf<PlatformFile?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var lastOpenedUri by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val audioFileLauncher = rememberFilePickerLauncher(
        type = FileKitType.File("mp3", "wav", "ogg", "m4a", "aac", "flac")
    ) { file ->
        if (file != null) {
            selectedFile = file
            selectedFileName = file.name
            selectedSource = AudioSource.Local
            val uri = file.getUri()
            audioState.player.play(uri)
            lastOpenedUri = uri
        }
    }

    val stateLabel = audioState.state.name

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Audio Player", style = MaterialTheme.typography.headlineMedium)
        Text("State: $stateLabel", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Position: ${formatMillis(audioState.position)} / ${formatMillis(audioState.duration)}",
            style = MaterialTheme.typography.bodyMedium
        )

        errorMessage?.let { message ->
            Text("Error: $message", color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedSource == AudioSource.Stream) {
                Button(onClick = { selectedSource = AudioSource.Stream }) { Text("Stream") }
            } else {
                OutlinedButton(onClick = { selectedSource = AudioSource.Stream }) { Text("Stream") }
            }

            if (selectedSource == AudioSource.Local) {
                Button(onClick = { selectedSource = AudioSource.Local }) { Text("Local") }
            } else {
                OutlinedButton(onClick = { selectedSource = AudioSource.Local }) { Text("Local") }
            }
        }

        if (selectedSource == AudioSource.Stream) {
            TextField(
                value = streamUrl,
                onValueChange = { streamUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Stream URL") },
                singleLine = true
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { audioFileLauncher.launch() }) {
                    Text("Select file")
                }
                Text(selectedFileName ?: "No file selected")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val isIdle = audioState.state == AudioPlayerState.IDLE
                    when (selectedSource) {
                        AudioSource.Local -> {
                            val uri = selectedFile?.getUri()
                            if (!uri.isNullOrBlank()) {
                                if (uri != lastOpenedUri || isIdle) {
                                    audioState.player.play(uri)
                                    lastOpenedUri = uri
                                } else {
                                    audioState.player.play()
                                }
                            }
                        }
                        AudioSource.Stream -> {
                            if (streamUrl.isNotBlank()) {
                                if (streamUrl != lastOpenedUri || isIdle) {
                                    audioState.player.play(streamUrl)
                                    lastOpenedUri = streamUrl
                                } else {
                                    audioState.player.play()
                                }
                            }
                        }
                    }
                }
            ) {
                Text("Play")
            }
            Button(
                onClick = { audioState.player.pause() },
                enabled = audioState.state == AudioPlayerState.PLAYING
            ) {
                Text("Pause")
            }
            Button(
                onClick = { audioState.player.stop() },
                enabled = audioState.state != AudioPlayerState.IDLE
            ) {
                Text("Stop")
            }
        }

        val durationMs = audioState.duration
        val positionFraction = if (durationMs > 0L) {
            (audioState.position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        Slider(
            value = positionFraction,
            onValueChange = { fraction ->
                if (durationMs > 0L) {
                    val target = (durationMs * fraction).toLong()
                    audioState.player.seekTo(target)
                }
            },
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth()
        )

        VolumeSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            initialVolume = audioState.volume
        ) { newVolume ->
            audioState.player.setVolume(newVolume)
        }
    }

    DisposableEffect(audioState.player) {
        val listener = object : ErrorListener {
            override fun onError(message: String?) {
                errorMessage = message ?: "Unknown error"
            }
        }
        audioState.player.setOnErrorListener(listener)
        onDispose { }
    }
}

@Composable
private fun VolumeSlider(
    modifier: Modifier = Modifier,
    initialVolume: Float = 0.5f,
    onVolumeChange: (Float) -> Unit = {}
) {
    var volume by remember { mutableStateOf(initialVolume) }

    LaunchedEffect(initialVolume) {
        volume = initialVolume
    }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            val clampedVolume = volume.coerceIn(0f, 1f)
            val path = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width * clampedVolume, size.height)
                lineTo(0f, 0f)
                close()
            }
            drawPath(path, color = Color.Blue)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Slider(
            value = volume,
            onValueChange = {
                volume = it
                onVolumeChange(it)
            },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private enum class AudioSource {
    Stream,
    Local
}

private fun formatMillis(value: Long): String {
    if (value <= 0L) return "00:00"
    val totalSeconds = value / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return "$mm:$ss"
}
