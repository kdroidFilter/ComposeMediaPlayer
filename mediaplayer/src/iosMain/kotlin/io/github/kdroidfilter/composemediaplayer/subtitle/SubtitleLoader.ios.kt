package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.stringWithContentsOfURL

/**
 * iOS implementation of the loadSubtitleContent function.
 * Loads subtitle content from a local file or a remote URL.
 *
 * @param src The source URI of the subtitle file
 * @return The content of the subtitle file as a string
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun loadSubtitleContent(src: String): String = withContext(Dispatchers.Default) {
    try {
        when {
            // Handle HTTP/HTTPS URLs
            src.startsWith("http://") || src.startsWith("https://") -> {
                val nsUrl = NSURL(string = src)
                nsUrl?.let {
                    try {
                        NSString.stringWithContentsOfURL(it, encoding = NSUTF8StringEncoding, error = null) ?: ""
                    } catch (e: Exception) {
                        println("Error loading URL: ${e.message}")
                        ""
                    }
                } ?: ""
            }

            // Handle file:// URIs
            src.startsWith("file://") -> {
                val nsUrl = NSURL(string = src)
                nsUrl?.let {
                    try {
                        NSString.stringWithContentsOfURL(it, encoding = NSUTF8StringEncoding, error = null) ?: ""
                    } catch (e: Exception) {
                        println("Error loading file URL: ${e.message}")
                        ""
                    }
                } ?: ""
            }

            // Handle local file paths
            else -> {
                try {
                    NSString.stringWithContentsOfFile(src, encoding = NSUTF8StringEncoding, error = null) ?: ""
                } catch (e: Exception) {
                    // Try as file URL
                    try {
                        val fileUrl = NSURL.fileURLWithPath(src)
                        fileUrl?.let {
                            NSString.stringWithContentsOfURL(it, encoding = NSUTF8StringEncoding, error = null) ?: ""
                        } ?: ""
                    } catch (e2: Exception) {
                        println("Error loading file path: ${e2.message}")
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
