package com.androidtvplayer.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.androidtvplayer.R
import com.androidtvplayer.cache.CacheManager
import com.androidtvplayer.data.StreamItem
import com.androidtvplayer.data.StreamType
import com.google.gson.Gson

@UnstableApi
class PlayerActivity : FragmentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var cacheStatusText: TextView
    private var player: ExoPlayer? = null
    private lateinit var stream: StreamItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        loadingSpinner = findViewById(R.id.loading_spinner)
        errorText = findViewById(R.id.error_text)
        cacheStatusText = findViewById(R.id.cache_status_text)
        stream = Gson().fromJson(intent.getStringExtra(EXTRA_STREAM_JSON), StreamItem::class.java)
        initializePlayer()
        updateCacheStatus()
    }

    private fun buildLoadControl(): LoadControl {
        return DefaultLoadControl.Builder()
            // Allocate 128GB as the target buffer in memory pipeline
            // (actual storage goes to SSD via CacheDataSource)
            .setBufferDurationsMs(
                // Min buffer before starting playback: 10 seconds
                10_000,
                // Max buffer to keep in memory pipeline: 2 hours
                // ExoPlayer will download as fast as possible up to this
                2 * 60 * 60 * 1000,
                // Buffer needed to resume after rebuffer: 5 seconds
                5_000,
                // Buffer needed to resume after seek: 10 seconds
                10_000
            )
            // Allocate maximum memory buffer (128MB in-memory, rest goes to SSD)
            .setTargetBufferBytes(128 * 1024 * 1024)
            // Always buffer as fast as possible regardless of playback speed
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    private fun initializePlayer() {
        val cacheDataSourceFactory = CacheManager.buildCacheDataSourceFactory()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setLoadControl(buildLoadControl())
            .build().also { exoPlayer ->
                playerView.player = exoPlayer
                val mediaSource = when (stream.type) {
                    StreamType.HLS -> HlsMediaSource.Factory(cacheDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(stream.url))
                    StreamType.DASH -> DashMediaSource.Factory(cacheDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(stream.url))
                }
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> showLoading(true)
                            Player.STATE_READY -> { showLoading(false); updateCacheStatus() }
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

    private fun updateCacheStatus() {
        val stats = CacheManager.getCacheStats()
        cacheStatusText.text = "Cache: ${stats.usedGb}GB / ${stats.maxGb}GB  ▼ Buffering to SSD..."
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
        player?.release()
        player = null
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
