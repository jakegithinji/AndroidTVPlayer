package com.androidtvplayer.cache

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * StreamDownloader
 *
 * Downloads a file to the SSD at maximum speed using:
 * - 16 parallel download threads (each downloads a chunk of the file)
 * - 8MB read buffers per thread
 * - HTTP Range requests for parallel chunked downloading
 *
 * Reports progress via onProgress callback.
 * Once MIN_PLAY_THRESHOLD is downloaded, signals ready for playback.
 */
object StreamDownloader {

    private const val TAG = "StreamDownloader"

    // Start playback after 3% of file is downloaded
    private const val MIN_PLAY_THRESHOLD = 0.03

    // Number of parallel download threads
    private const val PARALLEL_THREADS = 16

    // Read buffer per thread: 8MB
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
            // Step 1: Get file size via HEAD request
            val totalBytes = getFileSize(url)
            Log.i(TAG, "Total file size: ${totalBytes / (1024 * 1024)} MB")

            if (totalBytes <= 0) {
                // Fallback: single thread download if size unknown
                singleThreadDownload(url, outputFile, onProgress)
                return@withContext
            }

            // Step 2: Pre-allocate file on SSD for maximum write speed
            outputFile.parentFile?.mkdirs()
            RandomAccessFile(outputFile, "rw").use { it.setLength(totalBytes) }

            // Step 3: Split file into chunks for parallel downloading
            val chunkSize = totalBytes / PARALLEL_THREADS
            val chunks = (0 until PARALLEL_THREADS).map { i ->
                val start = i * chunkSize
                val end = if (i == PARALLEL_THREADS - 1) totalBytes - 1 else start + chunkSize - 1
                Pair(start, end)
            }

            // Step 4: Track progress across all threads
            val downloadedPerChunk = LongArray(PARALLEL_THREADS)
            var lastSpeedCheck = System.currentTimeMillis()
            var lastBytesForSpeed = 0L
            var playbackSignaled = false

            // Step 5: Launch parallel download coroutines
            val jobs = chunks.mapIndexed { index, (start, end) ->
                kotlinx.coroutines.async(Dispatchers.IO) {
                    downloadChunk(url, outputFile, start, end, index) { bytesDownloaded ->
                        downloadedPerChunk[index] = bytesDownloaded

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
                        val readyToPlay = progress >= MIN_PLAY_THRESHOLD

                        if (readyToPlay && !playbackSignaled) {
                            playbackSignaled = true
                        }

                        onProgress(
                            DownloadState(
                                downloadedBytes = totalDownloaded,
                                totalBytes = totalBytes,
                                speedMbps = speedMbps,
                                isReadyToPlay = readyToPlay,
                                isComplete = false
                            )
                        )
                    }
                }
            }

            // Wait for all chunks to complete
            jobs.forEach { it.await() }

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
                raf.seek(start)
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
