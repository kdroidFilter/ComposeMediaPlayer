package io.github.kdroidfilter.composemediaplayer

/**
 * Configuration for video caching. When enabled, downloaded video data is stored
 * on disk so that subsequent plays of the same URI load from the local cache
 * instead of re-downloading.
 *
 * The cache is shared across all [VideoPlayerState] instances that use the same
 * configuration, which makes it ideal for scroll-based UIs (e.g. VerticalPager)
 * where multiple player instances may load the same URLs.
 *
 * Caching only applies to URIs opened via [VideoPlayerState.openUri]; local files
 * and assets are not cached.
 *
 * Currently supported on **Android** and **iOS** only. On other platforms the
 * configuration is accepted but has no effect.
 *
 * @param enabled Whether caching is active. Default is `false`.
 * @param maxCacheSizeBytes Maximum disk space the cache may use, in bytes.
 *   When the limit is reached, the least-recently-used entries are evicted.
 *   Default is 100 MB.
 */
data class CacheConfig(
    val enabled: Boolean = false,
    val maxCacheSizeBytes: Long = 100L * 1024L * 1024L,
)
