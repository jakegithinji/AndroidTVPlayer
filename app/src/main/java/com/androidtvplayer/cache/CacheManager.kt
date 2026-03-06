package com.androidtvplayer.cache

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object CacheManager {

    private const val TAG = "CacheManager"
    private const val CACHE_DIR_NAME = "tvplayer_cache"

    private var simpleCache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        buildCache()
    }

    private fun buildCache() {
        release()
        val cacheDir = getCacheDirectory()
        val availableBytes = getAvailableSpace(cacheDir)
        // Use 90% of available internal storage
        val cacheSizeBytes = (availableBytes * 0.9).toLong()
        Log.i(TAG, "Cache dir: ${cacheDir.absolutePath}")
        Log.i(TAG, "Cache size: ${cacheSizeBytes / (1024 * 1024 * 1024)} GB")
        databaseProvider = StandaloneDatabaseProvider(appContext)
        simpleCache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(cacheSizeBytes),
            databaseProvider!!
        )
    }

    fun getCacheDirectory(): File {
        val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
        cacheDir.mkdirs()
        return cacheDir
    }

    fun getAvailableSpace(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            10L * 1024 * 1024 * 1024 // fallback 10GB
        }
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
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
    }

    fun getDownloadFile(url: String): File {
        val cacheDir = getCacheDirectory()
        val fileName = url.substringAfterLast("/").substringBefore("?")
            .ifEmpty { "stream_${System.currentTimeMillis()}.mkv" }
        return File(cacheDir, fileName)
    }

    fun getCacheStats(): CacheStats {
        val cacheDir = getCacheDirectory()
        val availableBytes = getAvailableSpace(cacheDir)
        val usedBytes = simpleCache?.cacheSpace ?: 0L
        val maxBytes = usedBytes + availableBytes
        return CacheStats(usedBytes, maxBytes, cacheDir)
    }

    fun clearCache() {
        try {
            getCacheDirectory().listFiles()?.forEach { it.delete() }
            simpleCache?.let { c ->
                c.keys.toList().forEach { key -> c.removeResource(key) }
            }
            Log.i(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
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
        val usedGb: String get() = "%.2f".format(usedBytes / (1024.0 * 1024 * 1024))
        val maxGb: String get() = "%.1f".format(maxBytes / (1024.0 * 1024 * 1024))
        val percentUsed: Int get() = if (maxBytes > 0) ((usedBytes * 100) / maxBytes).toInt() else 0
        val storagePath: String get() = cacheDir.absolutePath
    }
}
