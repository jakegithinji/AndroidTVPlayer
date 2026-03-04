package com.androidtvplayer.cache

import android.content.Context
import android.os.Environment
import android.os.StatFs
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

object CacheManager {

    private const val TAG = "CacheManager"
    private const val CACHE_DIR_NAME = "tvplayer_cache"

    // 128 GB in bytes
    private const val SSD_CACHE_SIZE_BYTES = 128L * 1024 * 1024 * 1024

    private var simpleCache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        buildCache()
    }

    private fun buildCache() {
        release()

        val cacheDir = resolveCacheDirectory()
        
        // Use full available space on the SSD, up to 128GB
        val availableBytes = getAvailableSpace(cacheDir)
        val cacheSizeBytes = minOf(SSD_CACHE_SIZE_BYTES, availableBytes)

        Log.i(TAG, "Cache directory: ${cacheDir.absolutePath}")
        Log.i(TAG, "Available space: ${availableBytes / (1024 * 1024 * 1024)} GB")
        Log.i(TAG, "Cache size set to: ${cacheSizeBytes / (1024 * 1024 * 1024)} GB")

        databaseProvider = StandaloneDatabaseProvider(appContext)
        simpleCache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(cacheSizeBytes),
            databaseProvider!!
        )
    }

    private fun getAvailableSpace(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            SSD_CACHE_SIZE_BYTES
        }
    }

    fun resolveCacheDirectory(): File {
        val externalDirs = appContext.getExternalFilesDirs(null)

        // Prefer physical non-emulated storage (SSD/USB)
        for (dir in externalDirs) {
            if (dir == null) continue
            val state = Environment.getExternalStorageState(dir)
            if (state == Environment.MEDIA_MOUNTED) {
                if (!Environment.isExternalStorageEmulated(dir)) {
                    val cacheDir = File(dir, CACHE_DIR_NAME)
                    if (cacheDir.mkdirs() || cacheDir.exists()) {
                        Log.i(TAG, "Using SSD/USB: ${cacheDir.absolutePath}")
                        return cacheDir
                    }
                }
            }
        }

        // Fallback to any mounted external
        for (dir in externalDirs) {
            if (dir == null) continue
            if (Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED) {
                val cacheDir = File(dir, CACHE_DIR_NAME)
                if (cacheDir.mkdirs() || cacheDir.exists()) {
                    Log.i(TAG, "Using external storage: ${cacheDir.absolutePath}")
                    return cacheDir
                }
            }
        }

        val internalCacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
        internalCacheDir.mkdirs()
        Log.w(TAG, "Fallback to internal cache: ${internalCacheDir.absolutePath}")
        return internalCacheDir
    }

    fun buildCacheDataSourceFactory(): CacheDataSource.Factory {
        val cache = simpleCache ?: run { buildCache(); simpleCache!! }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val upstreamFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            // Cache everything, never skip due to errors
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            // No cap on how much of a single resource to cache (full file)
            .setCacheWriteDataSinkFactory(null)
    }

    fun getCacheStats(): CacheStats {
        val cache = simpleCache ?: return CacheStats(0L, SSD_CACHE_SIZE_BYTES, resolveCacheDirectory())
        val cacheDir = resolveCacheDirectory()
        val usedBytes = cache.cacheSpace
        val availableBytes = getAvailableSpace(cacheDir)
        val maxBytes = minOf(SSD_CACHE_SIZE_BYTES, usedBytes + availableBytes)
        return CacheStats(usedBytes, maxBytes, cacheDir)
    }

    fun clearCache() {
        try {
            simpleCache?.let { c ->
                c.keys.toList().forEach { key -> c.removeResource(key) }
            }
            Log.i(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    fun rebuildCache() { buildCache() }

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
        val usedGb: String get() = "%.1f".format(usedBytes / (1024.0 * 1024 * 1024))
        val maxGb: String get() = "%.1f".format(maxBytes / (1024.0 * 1024 * 1024))
        val percentUsed: Int get() = if (maxBytes > 0) ((usedBytes * 100) / maxBytes).toInt() else 0
        val storagePath: String get() = cacheDir.absolutePath
    }
}
