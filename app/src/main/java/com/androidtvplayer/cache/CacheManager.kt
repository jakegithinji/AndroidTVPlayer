package com.androidtvplayer.cache

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object CacheManager {

    private const val TAG = "CacheManager"
    private const val CACHE_DIR_NAME = "tvplayer_cache"
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
        val availableBytes = getAvailableSpace(cacheDir)
        val cacheSizeBytes = minOf(SSD_CACHE_SIZE_BYTES, availableBytes)
        Log.i(TAG, "Cache dir: ${cacheDir.absolutePath}")
        Log.i(TAG, "Cache size: ${cacheSizeBytes / (1024 * 1024 * 1024)} GB")
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
        for (dir in externalDirs) {
            if (dir == null) continue
            if (Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED &&
                !Environment.isExternalStorageEmulated(dir)) {
                val cacheDir = File(dir, CACHE_DIR_NAME)
                if (cacheDir.mkdirs() || cacheDir.exists()) {
                    Log.i(TAG, "Using SSD: ${cacheDir.absolutePath}")
                    return cacheDir
                }
            }
        }
        for (dir in externalDirs) {
            if (dir == null) continue
            if (Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED) {
                val cacheDir = File(dir, CACHE_DIR_NAME)
                if (cacheDir.mkdirs() || cacheDir.exists()) {
                    Log.i(TAG, "Using external: ${cacheDir.absolutePath}")
                    return cacheDir
                }
            }
        }
        val internalCacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
        internalCacheDir.mkdirs()
        Log.w(TAG, "Using internal: ${internalCacheDir.absolutePath}")
        return internalCacheDir
    }

    fun buildCacheDataSourceFactory(): CacheDataSource.Factory {
        val cache = simpleCache ?: run { buildCache(); simpleCache!! }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            // Large buffer to keep download pipe full
            .setDefaultRequestProperties(mapOf(
                "Connection" to "keep-alive"
            ))

        val upstreamFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            // Remove the 2MB per-resource cap — allow unlimited file size caching
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
        val usedGb: String get() = "%.2f".format(usedBytes / (1024.0 * 1024 * 1024))
        val maxGb: String get() = "%.1f".format(maxBytes / (1024.0 * 1024 * 1024))
        val percentUsed: Int get() = if (maxBytes > 0) ((usedBytes * 100) / maxBytes).toInt() else 0
        val storagePath: String get() = cacheDir.absolutePath
    }
}
