package com.androidtvplayer.cache

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.androidtvplayer.data.PreferencesManager
import java.io.File

/**
 * CacheManager
 *
 * Manages ExoPlayer's SimpleCache, routing the cache directory to an
 * external USB/SSD drive when one is available, with fallback to
 * internal storage.
 *
 * Cache size is user-configurable via PreferencesManager.
 */
object CacheManager {

    private const val TAG = "CacheManager"
    private const val CACHE_DIR_NAME = "tvplayer_cache"

    // Default cache size: 20 GB
    private const val DEFAULT_CACHE_SIZE_BYTES = 20L * 1024 * 1024 * 1024

    private var simpleCache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        buildCache()
    }

    private fun buildCache() {
        release() // release any existing cache first

        val cacheDir = resolveCacheDirectory()
        val cacheSizeBytes = PreferencesManager.getCacheSizeMb(appContext) * 1024L * 1024L

        Log.i(TAG, "Cache directory: ${cacheDir.absolutePath}")
        Log.i(TAG, "Cache size: ${cacheSizeBytes / (1024 * 1024)} MB")

        databaseProvider = StandaloneDatabaseProvider(appContext)
        simpleCache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(cacheSizeBytes),
            databaseProvider!!
        )
    }

    /**
     * Resolves the best available cache directory.
     * Priority: External SSD > External SD card > Internal storage
     */
    fun resolveCacheDirectory(): File {
        val externalDirs = appContext.getExternalFilesDirs(null)

        // Walk through available external volumes; prefer removable/high-capacity ones
        for (dir in externalDirs) {
            if (dir == null) continue
            val state = Environment.getExternalStorageState(dir)
            if (state == Environment.MEDIA_MOUNTED) {
                // Prefer non-emulated (physical) storage (SSD/USB drive)
                if (!Environment.isExternalStorageEmulated(dir)) {
                    val cacheDir = File(dir, CACHE_DIR_NAME)
                    if (cacheDir.mkdirs() || cacheDir.exists()) {
                        Log.i(TAG, "Using external storage (SSD/USB): ${cacheDir.absolutePath}")
                        return cacheDir
                    }
                }
            }
        }

        // Fallback: first available mounted external dir (emulated/SD)
        for (dir in externalDirs) {
            if (dir == null) continue
            val state = Environment.getExternalStorageState(dir)
            if (state == Environment.MEDIA_MOUNTED) {
                val cacheDir = File(dir, CACHE_DIR_NAME)
                if (cacheDir.mkdirs() || cacheDir.exists()) {
                    Log.i(TAG, "Using external storage (emulated): ${cacheDir.absolutePath}")
                    return cacheDir
                }
            }
        }

        // Final fallback: internal app cache
        val internalCacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
        internalCacheDir.mkdirs()
        Log.w(TAG, "No external storage found. Using internal cache: ${internalCacheDir.absolutePath}")
        return internalCacheDir
    }

    /**
     * Returns a CacheDataSource.Factory that wraps an HTTP source with
     * the SimpleCache for transparent read-through caching.
     */
    fun buildCacheDataSourceFactory(): CacheDataSource.Factory {
        val cache = simpleCache ?: run {
            buildCache()
            simpleCache!!
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val upstreamFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Returns cache statistics for display in the UI.
     */
    fun getCacheStats(): CacheStats {
        val cache = simpleCache ?: return CacheStats(0L, 0L, resolveCacheDirectory())
        val cacheDir = resolveCacheDirectory()
        val usedBytes = cache.cacheSpace
        val maxBytes = PreferencesManager.getCacheSizeMb(appContext) * 1024L * 1024L
        return CacheStats(usedBytes, maxBytes, cacheDir)
    }

    /**
     * Clears all cached content.
     */
    fun clearCache() {
        try {
            simpleCache?.let { c ->
                c.keys.toList().forEach { key ->
                    c.removeResource(key)
                }
            }
            Log.i(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    /**
     * Rebuilds cache with updated settings (e.g. new size or new directory).
     */
    fun rebuildCache() {
        buildCache()
    }

    fun release() {
        try {
            simpleCache?.release()
            simpleCache = null
            databaseProvider = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing cache", e)
        }
    }

    data class CacheStats(
        val usedBytes: Long,
        val maxBytes: Long,
        val cacheDir: File
    ) {
        val usedMb: Long get() = usedBytes / (1024 * 1024)
        val maxMb: Long get() = maxBytes / (1024 * 1024)
        val percentUsed: Int get() = if (maxBytes > 0) ((usedBytes * 100) / maxBytes).toInt() else 0
        val storagePath: String get() = cacheDir.absolutePath
    }
}
