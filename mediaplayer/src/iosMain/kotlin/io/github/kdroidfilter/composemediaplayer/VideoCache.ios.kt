package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import platform.Foundation.NSURLCache

private val cacheLogger = TaggedLogger("iOSVideoCache")

/**
 * Manages the shared [NSURLCache] configuration for AVPlayer on iOS.
 *
 * AVPlayer uses the shared URL loading system under the hood. By configuring
 * [NSURLCache] with a generous disk capacity, HTTP responses (including partial
 * content / range requests used during seek) are stored on disk and served
 * from the cache on subsequent plays of the same URI.
 *
 * This works transparently with standard HTTP caching headers. Most CDNs and
 * video hosting services send appropriate `Cache-Control` / `ETag` headers
 * that allow caching.
 */
internal object IosVideoCache {
    private var configured = false
    private var previousMemoryCapacity: ULong = 0u
    private var previousDiskCapacity: ULong = 0u

    @Synchronized
    fun configure(maxCacheSizeBytes: Long) {
        if (configured) return

        val sharedCache = NSURLCache.sharedURLCache
        previousMemoryCapacity = sharedCache.memoryCapacity
        previousDiskCapacity = sharedCache.diskCapacity

        // Set disk capacity to the requested size; keep a reasonable memory cache (10 MB)
        sharedCache.memoryCapacity = maxOf(sharedCache.memoryCapacity, (10L * 1024 * 1024).toULong())
        sharedCache.diskCapacity = maxOf(sharedCache.diskCapacity, maxCacheSizeBytes.toULong())

        configured = true
        cacheLogger.d {
            "NSURLCache configured: disk=${sharedCache.diskCapacity} bytes, memory=${sharedCache.memoryCapacity} bytes"
        }
    }

    @Synchronized
    fun clear() {
        NSURLCache.sharedURLCache.removeAllCachedResponses()
        cacheLogger.d { "NSURLCache cleared" }
    }

    @Synchronized
    fun release() {
        if (!configured) return

        val sharedCache = NSURLCache.sharedURLCache
        sharedCache.memoryCapacity = previousMemoryCapacity
        sharedCache.diskCapacity = previousDiskCapacity
        configured = false
        cacheLogger.d { "NSURLCache restored to previous configuration" }
    }
}
