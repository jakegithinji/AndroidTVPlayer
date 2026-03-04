package com.androidtvplayer.cache

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

object StreamDownloader {

    private const val TAG = "StreamDownloader"
    private const val MIN_PLAY_THRESHOLD = 0.03
    private const val PARALLEL_THREADS = 16
    private const val BUFFER_SIZE = 8 * 1024 * 1024

    data class DownloadState(
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val speedMbps: Double = 0.0,
        val isReadyToPlay: Boolean = false,
        val isComplete: Boolean = false,
        val error: String? = null
    ) {
        val downloadedGb: String get() = "%.2f".format(downloadedBytes / (1024.0 * 1024 * 1024))
        val totalGb: String get() = "%.2f".format(totalBytes / (1024.0 * 1024 * 1024))
        val progressPercent: Int get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
    }

    suspend fun download(
        url: String,
        outputFile: File,
        onProgress: (DownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val totalBytes = getFileSize(url)
            Log.i(TAG, "Total file size: ${totalBytes / (1024 * 1024)} MB")

            if (totalBytes <= 0) {
                singleThreadDownload(url, outputFile, onProgress)
                return@withContext
            }

            outputFile.parentFile?.mkdirs()
            RandomAccessFile(outputFile, "rw").use { it.setLength(totalBytes) }

            val chunkSize = totalBytes / PARALLEL_THREADS
            val chunks = (0 until PARALLEL_THREADS).map { i ->
                val start = i * chunkSize
                val end = if (i == PARALLEL_THREADS - 1) totalBytes - 1 else start + chunkSize - 1
                Pair(start, end)
            }

            val downloadedPerChunk = LongArray(PARALLEL_THREADS)
            var lastSpeedCheck = System.currentTimeMillis()
            var lastBytesForSpeed = 0L
            var playbackSignaled = false

            coroutineScope {
                val jobs = chunks.mapIndexed { index, (start, end) ->
                    async(Dispatchers.IO) {
                        downloadChunk(url, outputFile, start, end, index) { bytesDownloaded ->
                            synchronized(downloadedPerChunk) {
                                downloadedPerChunk[index] = bytesDownloaded
                            }

                            val totalDownloaded = downloadedPerChunk.sum()
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastSpeedCheck

                            var speedMbps = 0.0
                            if (elapsed >= 500) {
                                val bytesDelta = totalDownloaded - lastBytesForSpeed
                                speedMbps = (bytesDelta * 8.0) / (elapsed * 1000.0)
                                lastSpeedCheck = now
                                lastBytesForSpeed = totalDownloaded
                            }

                            val progress = totalDownloaded.toDouble() / totalBytes
                            if (progress >= MIN_PLAY_THRESHOLD && !playbackSignaled) {
                                playbackSignaled = true
                            }

                            onProgress(
                                DownloadState(
                                    downloadedBytes = totalDownloaded,
                                    totalBytes = totalBytes,
                                    speedMbps = speedMbps,
                                    isReadyToPlay = progress >= MIN_PLAY_THRESHOLD,
                                    isComplete = false
                                )
                            )
                        }
                    }
                }
                jobs.awaitAll()
            }

            onProgress(
                DownloadState(
                    downloadedBytes = totalBytes,
                    totalBytes = totalBytes,
                    speedMbps = 0.0,
                    isReadyToPlay = true,
                    isComplete = true
                )
            )
            Log.i(TAG, "Download complete: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            onProgress(DownloadState(error = e.message ?: "Download failed"))
        }
    }

    private suspend fun downloadChunk(
        url: String,
        outputFile: File,
        start: Long,
        end: Long,
        chunkIndex: Int,
        onBytesWritten: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        var written = 0L
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=$start-$end")
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 15_000
                readTimeout = 30_000
                connect()
            }

            val buffer = ByteArray(BUFFER_SIZE)
            RandomAccessFile(outputFile, "rw").use { raf ->
                BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                    var bytesRead: Int
                    while (isActive) {
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        synchronized(raf) {
                            raf.seek(start + written)
                            raf.write(buffer, 0, bytesRead)
                        }
                        written += bytesRead
                        onBytesWritten(written)
                    }
                }
            }
            Log.i(TAG, "Chunk $chunkIndex complete: ${written / (1024 * 1024)} MB")
        } catch (e: Exception) {
            Log.e(TAG, "Chunk $chunkIndex error", e)
        }
    }

    private suspend fun singleThreadDownload(
        url: String,
        outputFile: File,
        onProgress: (DownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        var downloaded = 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            setRequestProperty("Connection", "keep-alive")
            connectTimeout = 15_000
            readTimeout = 0
            connect()
        }
        val total = connection.contentLengthLong
        val buffer = ByteArray(BUFFER_SIZE)
        BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
            outputFile.outputStream().use { output ->
                var bytesRead: Int
                while (isActive) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onProgress(
                        DownloadState(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            isReadyToPlay = total > 0 && downloaded.toDouble() / total >= MIN_PLAY_THRESHOLD
                        )
                    )
                }
            }
        }
    }

    private fun getFileSize(url: String): Long {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 10_000
            connection.connect()
            val size = connection.contentLengthLong
            connection.disconnect()
            size
        } catch (e: Exception) {
            Log.e(TAG, "Could not get file size", e)
            -1L
        }
    }
}
