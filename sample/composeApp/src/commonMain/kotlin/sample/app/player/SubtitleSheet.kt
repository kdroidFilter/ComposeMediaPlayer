package sample.app.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack

private const val DEFAULT_SUBTITLE_URL =
    "https://raw.githubusercontent.com/kdroidFilter/ComposeMediaPlayer/refs/heads/master/assets/subtitles/en.vtt"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleSheet(
    subtitleTracks: List<SubtitleTrack>,
    selectedTrack: SubtitleTrack?,
    onTrackSelected: (SubtitleTrack) -> Unit,
    onDisableSubtitles: () -> Unit,
    onPickFile: () -> Unit,
    onAddTrack: (SubtitleTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    var subtitleUrl by remember { mutableStateOf(DEFAULT_SUBTITLE_URL) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            // URL input
            OutlinedTextField(
                value = subtitleUrl,
                onValueChange = { subtitleUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Subtitle URL") },
                singleLine = true,
                trailingIcon = {
                    FilledTonalIconButton(onClick = {
                        if (subtitleUrl.isNotBlank()) {
                            onAddTrack(SubtitleTrack(label = "URL Subtitles", language = "en", src = subtitleUrl))
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add subtitle")
                    }
                },
            )

            // File picker
            OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Select local file (VTT / SRT)")
            }

            // Track selection
            if (subtitleTracks.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Available tracks",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column {
                    subtitleTracks.forEach { track ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedTrack == track,
                                onClick = { onTrackSelected(track) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(track.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedTrack == null,
                            onClick = onDisableSubtitles,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Disabled",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
