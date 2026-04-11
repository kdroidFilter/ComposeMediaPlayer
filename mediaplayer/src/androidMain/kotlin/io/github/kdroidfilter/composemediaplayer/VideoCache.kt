package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Singleton managing the shared [SimpleCache] instance for ExoPlayer.
 *
 * The cache is lazily initialized on first access and is shared across all
 * player instances so that video data downloaded by one player is available
 * to every other player without a second network round-trip.
 */
@UnstableApi
internal object VideoCache {
    private var simpleCache: SimpleCache? = null
    private var currentMaxBytes: Long = 0L

    @Synchronized
    fun getCache(
        context: Context,
        maxCacheSizeBytes: Long,
    ): SimpleCache {
        val existing = simpleCache
        if (existing != null && currentMaxBytes == maxCacheSizeBytes) return existing

        // Release the previous cache if the size changed
        existing?.release()

        val cacheDir = File(context.cacheDir, "compose_media_player_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(maxCacheSizeBytes)
        val dbProvider = StandaloneDatabaseProvider(context)

        return SimpleCache(cacheDir, evictor, dbProvider).also {
            simpleCache = it
            currentMaxBytes = maxCacheSizeBytes
        }
    }

    @Synchronized
    fun release() {
        simpleCache?.release()
        simpleCache = null
        currentMaxBytes = 0L
    }

    @Synchronized
    fun clear(
        context: Context,
        maxCacheSizeBytes: Long,
    ) {
        val cache = getCache(context, maxCacheSizeBytes)
        cache.keys.toList().forEach { key ->
            cache.removeResource(key)
        }
    }
}

@OptIn(UnstableApi::class)
internal fun buildCachingDataSourceFactory(
    context: Context,
    maxCacheSizeBytes: Long,
): DataSource.Factory {
    val upstreamFactory = DefaultDataSource.Factory(context)
    return CacheDataSource
        .Factory()
        .setCache(VideoCache.getCache(context, maxCacheSizeBytes))
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
