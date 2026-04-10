package sample.app.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    playerState: VideoPlayerState,
    selectedContentScale: ContentScale,
    onContentScaleChange: (ContentScale) -> Unit,
    initialPlayerState: InitialPlayerState,
    onInitialPlayerStateChange: (InitialPlayerState) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            // Volume
            Section("Volume") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        playerState.volume = if (playerState.volume > 0f) 0f else 1f
                    }) {
                        Icon(
                            imageVector = if (playerState.volume > 0f)
                                Icons.AutoMirrored.Filled.VolumeUp
                            else
                                Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Toggle mute",
                        )
                    }
                    Slider(
                        value = playerState.volume,
                        onValueChange = { playerState.volume = it },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(playerState.volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }

            // Speed
            Section("Playback Speed") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = playerState.playbackSpeed,
                        onValueChange = { playerState.playbackSpeed = it },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(playerState.playbackSpeed * 10).toInt() / 10.0}x",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }

            HorizontalDivider()

            // Content scale
            Section("Content Scale") {
                val scales = listOf(
                    "Fit" to ContentScale.Fit,
                    "Crop" to ContentScale.Crop,
                    "Inside" to ContentScale.Inside,
                    "Fill" to ContentScale.FillBounds,
                    "Fill W" to ContentScale.FillWidth,
                    "Fill H" to ContentScale.FillHeight,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    scales.forEach { (label, scale) ->
                        FilterChip(
                            selected = selectedContentScale == scale,
                            onClick = { onContentScaleChange(scale) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // Toggles
            Section("Playback") {
                ToggleRow("Loop", playerState.loop) { playerState.loop = it }
                ToggleRow(
                    label = "Auto-play on load",
                    checked = initialPlayerState == InitialPlayerState.PLAY,
                    onCheckedChange = {
                        onInitialPlayerStateChange(
                            if (it) InitialPlayerState.PLAY else InitialPlayerState.PAUSE,
                        )
                    },
                )
                ToggleRow("Picture-in-Picture", playerState.isPipEnabled) {
                    playerState.isPipEnabled = it
                }
            }

            // Metadata
            if (!playerState.metadata.isAllNull()) {
                HorizontalDivider()
                Section("Metadata") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        playerState.metadata.title?.let { InfoRow("Title", it) }
                        playerState.metadata.width?.let { w ->
                            playerState.metadata.height?.let { h ->
                                InfoRow("Resolution", "${w}x$h")
                            }
                        }
                        playerState.metadata.frameRate?.let { InfoRow("Frame Rate", "$it fps") }
                        playerState.metadata.bitrate?.let { InfoRow("Bitrate", "${it / 1000} kbps") }
                        playerState.metadata.mimeType?.let { InfoRow("Format", it) }
                        playerState.metadata.audioChannels?.let { ch ->
                            playerState.metadata.audioSampleRate?.let { sr ->
                                InfoRow("Audio", "$ch ch, ${sr / 1000} kHz")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
