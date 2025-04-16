package sample.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.platformtools.darkmodedetector.windows.setWindowsAdaptiveTitleBar

fun main()  {
    application {
        val windowState = rememberWindowState(width = 720.dp, height = 1000.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Compose Media Player",
            state = windowState
        ) {
            window.setWindowsAdaptiveTitleBar()
            App()
        }
    }
}


