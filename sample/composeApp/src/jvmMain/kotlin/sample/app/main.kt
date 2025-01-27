package sample.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main()  {
    System.setProperty("compose.interop.blending", "true")
    application {
        System.setProperty("compose.interop.blending", "true")
        val windowState = rememberWindowState()
        Window(
            onCloseRequest = ::exitApplication,
            title = "Compose Media Player",
            state = windowState
        ) {
            App()
        }
    }
}


