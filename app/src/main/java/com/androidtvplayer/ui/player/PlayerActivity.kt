package com.androidtvplayer.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.androidtvplayer.R
import com.androidtvplayer.cache.CacheManager
import com.androidtvplayer.cache.StreamDownloader
import com.androidtvplayer.data.StreamItem
import com.androidtvplayer.data.StreamType
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
class PlayerActivity : FragmentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var downloadProgress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var statusText: TextView
    private lateinit var downloadText: TextView

    private var player: ExoPlayer? = null
    private var downloadJob: Job? = null
    private var downloadedFile: File? = null
    private var playbackStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        loadingSpinner = findViewById(R.id.loading_spinner)
        downloadProgress = findViewById(R.id.download_progress) as android.widget.ProgressBar as android.widget.ProgressBar
        errorText = findViewById(R.id.error_text)
        statusText = findViewById(R.id.cache_status_text)
        downloadText = findViewById(R.id.download_text)

        val url = resolveUrl(intent)
        if (url == null) {
            showError("No stream URL provided")
            return
        }

        startDownloadThenPlay(url)
    }

    private fun resolveUrl(intent: Intent): String? {
        intent.getStringExtra(EXTRA_STREAM_JSON)?.let { json ->
            return Gson().fromJson(json, StreamItem::class.java).url
        }
        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { return it }
            intent.data?.toString()?.let { return it }
        }
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val urlRegex = Regex("https?://\\S+")
                return urlRegex.find(text)?.value ?: text.trim()
            }
        }
        return null
    }

    private fun startDownloadThenPlay(url: String) {
        val outputFile = CacheManager.getDownloadFile(url)
        downloadedFile = outputFile

        statusText.text = "⬇ Downloading to SSD at full speed..."
        downloadProgress.visibility = View.VISIBLE
        downloadText.visibility = View.VISIBLE
        showLoading(true)

        downloadJob = lifecycleScope.launch {
            StreamDownloader.download(url, outputFile) { state ->
                runOnUiThread {
                    when {
                        state.error != null -> {
                            // Download failed — fall back to direct stream
                            showLoading(false)
                            statusText.text = "⚠ SSD unavailable, streaming directly..."
                            downloadProgress.visibility = View.GONE
                            downloadText.visibility = View.GONE
                            startPlayer(url, fromFile = false)
                        }
                        state.isReadyToPlay && !playbackStarted -> {
                            // Enough downloaded — start playing from SSD file
                            playbackStarted = true
                            downloadProgress.visibility = View.GONE
                            showLoading(false)
                            startPlayer(outputFile.absolutePath, fromFile = true)
                            updateDownloadStatus(state)
                        }
                        state.isComplete -> {
                            downloadText.text = "✅ Fully cached to SSD: ${state.downloadedGb} GB"
                            statusText.text = "Playing from SSD"
                        }
                        else -> {
                            // Still downloading
                            downloadProgress.progress = state.progressPercent
                            val speedText = if (state.speedMbps > 0) " @ ${"%.1f".format(state.speedMbps)} Mbps" else ""
                            downloadText.text = "⬇ ${state.downloadedGb} GB / ${state.totalGb} GB$speedText"
                            statusText.text = "${state.progressPercent}% cached to SSD"
                        }
                    }
                }
            }
        }
    }

    private fun updateDownloadStatus(state: StreamDownloader.DownloadState) {
        if (state.isComplete) return
        val speedText = if (state.speedMbps > 0) " @ ${"%.1f".format(state.speedMbps)} Mbps" else ""
        downloadText.text = "⬇ ${state.downloadedGb} GB / ${state.totalGb} GB$speedText"
        statusText.text = "${state.progressPercent}% cached — Playing from SSD"
    }

    private fun detectMediaType(url: String): StreamType {
        val clean = url.lowercase().split("?")[0]
        return when {
            clean.endsWith(".m3u8") || clean.contains(".m3u8") -> StreamType.HLS
            clean.endsWith(".mpd") || clean.contains(".mpd") -> StreamType.DASH
            else -> StreamType.HLS
        }
    }

    private fun buildLoadControl(): LoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 30_000, 2_500, 5_000)
            .setTargetBufferBytes(Int.MAX_VALUE)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    private fun startPlayer(urlOrPath: String, fromFile: Boolean) {
        val cacheDataSourceFactory = CacheManager.buildCacheDataSourceFactory()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setLoadControl(buildLoadControl())
            .build().also { exoPlayer ->
                playerView.player = exoPlayer

                val mediaSource = if (fromFile) {
                    // Playing from local SSD file
                    ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri("file://$urlOrPath"))
                } else {
                    // Fallback: direct network stream
                    when (detectMediaType(urlOrPath)) {
                        StreamType.HLS -> HlsMediaSource.Factory(cacheDataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(urlOrPath))
                        StreamType.DASH -> DashMediaSource.Factory(cacheDataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(urlOrPath))
                    }
                }

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> showLoading(true)
                            Player.STATE_READY -> showLoading(false)
                            Player.STATE_ENDED -> finish()
                            else -> {}
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        showLoading(false)
                        showError("Playback error: ${error.message}")
                    }
                })
            }
    }

    private fun showLoading(show: Boolean) {
        loadingSpinner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }; true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                player?.seekTo((player!!.currentPosition + 10_000).coerceAtMost(player!!.duration)); true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> {
                player?.seekTo((player!!.currentPosition - 10_000).coerceAtLeast(0)); true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStart() { super.onStart(); player?.play() }
    override fun onStop() { super.onStop(); player?.pause() }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        player?.release()
        player = null
        // Delete downloaded file from SSD on exit
        downloadedFile?.delete()
        CacheManager.clearCache()
    }

    companion object {
        private const val EXTRA_STREAM_JSON = "extra_stream_json"
        fun createIntent(context: Context, stream: StreamItem): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_JSON, Gson().toJson(stream))
            }
    }
}
