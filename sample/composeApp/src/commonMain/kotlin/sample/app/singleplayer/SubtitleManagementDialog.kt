package sample.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack

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