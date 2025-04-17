package io.github.kdroidfilter.composemediaplayer.subtitle

import android.net.Uri
import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.androidVideoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android implementation of the loadSubtitleContent function.
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
            
            // Handle content:// URIs
            src.startsWith("content://") -> {
                val context = ContextProvider.getContext()
                val uri = Uri.parse(src)
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                } ?: ""
            }
            
            // Handle file:// URIs or local file paths
            else -> {
                val context = ContextProvider.getContext()
                val uri = if (src.startsWith("file://")) {
                    Uri.parse(src)
                } else {
                    Uri.fromFile(java.io.File(src))
                }
                
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().use { it.readText() }
                    } ?: ""
                } catch (e: Exception) {
                    // Fallback to direct file access if content resolver fails
                    try {
                        java.io.File(src).readText()
                    } catch (e2: Exception) {
                        androidVideoLogger.e { "Failed to load subtitle file: ${e2.message}" }
                        ""
                    }
                }
            }
        }
    } catch (e: Exception) {
        androidVideoLogger.e { "Error loading subtitle content: ${e.message}" }
        ""
    }
}