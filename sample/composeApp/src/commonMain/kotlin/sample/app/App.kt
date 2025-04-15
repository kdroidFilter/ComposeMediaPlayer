package sample.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import sample.app.singleplayer.SinglePlayerScreen

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
