package io.github.kdroidfilter.composemediaplayer.subtitle

import io.github.kdroidfilter.composemediaplayer.wasmVideoLogger
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.url.URL
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

/**
 * WASM JS implementation of the loadSubtitleContent function.
 * Loads subtitle content from a URL using XMLHttpRequest.
 *
 * @param src The source URI of the subtitle file
 * @return The content of the subtitle file as a string
 */
actual suspend fun loadSubtitleContent(src: String): String = suspendCancellableCoroutine { continuation ->
    try {
        // Handle different types of URLs
        val url = when {
            // Handle HTTP/HTTPS URLs directly
            src.startsWith("http://") || src.startsWith("https://") -> src

            // Handle blob: URLs directly
            src.startsWith("blob:") -> src

            // Handle data: URLs directly
            src.startsWith("data:") -> src

            // Handle file: URLs
            src.startsWith("file:") -> {
                wasmVideoLogger.d { "File URLs are not directly supported in browser. Using as-is: $src" }
                src
            }

            // For any other format, assume it's a relative path
            else -> {
                try {
                    // Try to resolve relative to the current page
                    URL(src, window.location.href).toString()
                } catch (e: Exception) {
                    wasmVideoLogger.e { "Failed to resolve URL: $src - ${e.message}" }
                    src // Use as-is if resolution fails
                }
            }
        }

        // Log the URL we're fetching
        wasmVideoLogger.d { "Fetching subtitle content from: $url" }

        // Use XMLHttpRequest to fetch the content
        val xhr = XMLHttpRequest()
        xhr.open("GET", url, true)
        // We want text response, which is the default, so no need to set responseType

        xhr.onload = {
            if (xhr.status.toInt() in 200..299) {
                val content = xhr.responseText ?: ""
                continuation.resume(content)
            } else {
                wasmVideoLogger.e { "Failed to fetch subtitle content: ${xhr.status} ${xhr.statusText}" }
                continuation.resume("")
            }
        }

        xhr.onerror = {
            wasmVideoLogger.e { "Error fetching subtitle content" }
            continuation.resume("")
        }

        xhr.send()

        // Register cancellation handler
        continuation.invokeOnCancellation {
            try {
                xhr.abort()
            } catch (e: Exception) {
                // Ignore abort errors
            }
        }
    } catch (e: Exception) {
        wasmVideoLogger.e { "Error loading subtitle content: ${e.message}" }
        continuation.resume("")
    }
}
