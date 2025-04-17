package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * JVM implementation of the loadSubtitleContent function.
 * Loads subtitle content from a local file or a remote URL.
 *
 * @param src The source URI of the subtitle file
 * @return The content of the subtitle file as a string
 */
actual suspend fun loadSubtitleContent(src: String): String = withContext(Dispatchers.IO) {
    try {
        when {
            // Handle HTTP/HTTPS URLs
            src.startsWith("http://") || src.startsWith("https://") -> {
                val url = URL(src)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.use { it.readText() }
                connection.disconnect()
                content
            }
            
            // Handle file:// URIs
            src.startsWith("file://") -> {
                val file = File(URI(src))
                file.readText()
            }
            
            // Handle local file paths
            else -> {
                val file = File(src)
                if (file.exists()) {
                    file.readText()
                } else {
                    // Try to interpret as a URI
                    try {
                        val uri = URI(src)
                        val uriFile = File(uri)
                        uriFile.readText()
                    } catch (e: Exception) {
                        println("Error loading subtitle file: ${e.message}")
                        ""
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("Error loading subtitle content: ${e.message}")
        ""
    }
}