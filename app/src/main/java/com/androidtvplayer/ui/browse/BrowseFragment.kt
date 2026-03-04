package com.androidtvplayer.ui.browse

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import com.androidtvplayer.R
import com.androidtvplayer.cache.CacheManager
import com.androidtvplayer.data.PreferencesManager
import com.androidtvplayer.data.StreamItem
import com.androidtvplayer.ui.player.PlayerActivity
import com.androidtvplayer.ui.settings.SettingsActivity

class BrowseFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = Color.parseColor("#1A1A2E")
        title = getString(R.string.app_name)

        setupEventListeners()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadRows()
    }

    private fun loadRows() {
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

        // ── Streams row ────────────────────────────────────────────────────
        val streamPresenter = StreamCardPresenter()
        val streamsAdapter = ArrayObjectAdapter(streamPresenter)
        val streams = PreferencesManager.getStreams(requireContext())
        streams.forEach { streamsAdapter.add(it) }

        rowsAdapter.add(
            ListRow(HeaderItem(0, getString(R.string.row_streams)), streamsAdapter)
        )

        // ── Cache status row ───────────────────────────────────────────────
        val cachePresenter = CacheCardPresenter()
        val cacheAdapter = ArrayObjectAdapter(cachePresenter)
        val stats = CacheManager.getCacheStats()
        cacheAdapter.add(stats)
        rowsAdapter.add(
            ListRow(HeaderItem(1, getString(R.string.row_cache_status)), cacheAdapter)
        )

        // ── Settings row ───────────────────────────────────────────────────
        val settingsPresenter = SettingsCardPresenter()
        val settingsAdapter = ArrayObjectAdapter(settingsPresenter)
        settingsAdapter.add(SettingsOption.CACHE_SIZE)
        settingsAdapter.add(SettingsOption.CLEAR_CACHE)
        settingsAdapter.add(SettingsOption.ADD_STREAM)
        rowsAdapter.add(
            ListRow(HeaderItem(2, getString(R.string.row_settings)), settingsAdapter)
        )

        adapter = rowsAdapter
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is StreamItem -> playStream(item)
                is CacheManager.CacheStats -> {
                    // Refresh cache stats
                    loadRows()
                }
                is SettingsOption -> handleSettingsOption(item)
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            // Could prefetch/preload metadata here
        }
    }

    private fun playStream(stream: StreamItem) {
        PreferencesManager.setLastPlayedUrl(requireContext(), stream.url)
        val intent = PlayerActivity.createIntent(requireContext(), stream)
        startActivity(intent)
    }

    private fun handleSettingsOption(option: SettingsOption) {
        when (option) {
            SettingsOption.CACHE_SIZE,
            SettingsOption.ADD_STREAM -> {
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
            SettingsOption.CLEAR_CACHE -> {
                CacheManager.clearCache()
                loadRows() // Refresh stats
            }
        }
    }
}

enum class SettingsOption(val label: String) {
    CACHE_SIZE("Cache Size"),
    CLEAR_CACHE("Clear Cache"),
    ADD_STREAM("Add Stream")
}
