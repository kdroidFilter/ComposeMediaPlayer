package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import platform.Foundation.NSURLCache
import kotlin.concurrent.AtomicInt

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
    private val configuredFlag = AtomicInt(0)
    private var previousMemoryCapacity: ULong = 0u
    private var previousDiskCapacity: ULong = 0u

    fun configure(maxCacheSizeBytes: Long) {
        if (!configuredFlag.compareAndSet(0, 1)) return

        val sharedCache = NSURLCache.sharedURLCache
        previousMemoryCapacity = sharedCache.memoryCapacity
        previousDiskCapacity = sharedCache.diskCapacity

        // Set disk capacity to the requested size; keep a reasonable memory cache (10 MB)
        sharedCache.memoryCapacity = maxOf(sharedCache.memoryCapacity, (10L * 1024 * 1024).toULong())
        sharedCache.diskCapacity = maxOf(sharedCache.diskCapacity, maxCacheSizeBytes.toULong())

        cacheLogger.d {
            "NSURLCache configured: disk=${sharedCache.diskCapacity} bytes, memory=${sharedCache.memoryCapacity} bytes"
        }
    }

    fun clear() {
        NSURLCache.sharedURLCache.removeAllCachedResponses()
        cacheLogger.d { "NSURLCache cleared" }
    }

    fun release() {
        if (!configuredFlag.compareAndSet(1, 0)) return

        val sharedCache = NSURLCache.sharedURLCache
        sharedCache.memoryCapacity = previousMemoryCapacity
        sharedCache.diskCapacity = previousDiskCapacity
        cacheLogger.d { "NSURLCache restored to previous configuration" }
    }
}
