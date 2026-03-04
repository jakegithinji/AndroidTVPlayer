package com.androidtvplayer.ui.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.androidtvplayer.R
import com.androidtvplayer.cache.CacheManager

class CacheCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_cache, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val stats = item as CacheManager.CacheStats
        val view = viewHolder.view

        view.findViewById<TextView>(R.id.cache_used).text =
            "${stats.usedMb} MB / ${stats.maxMb} MB used"
        view.findViewById<TextView>(R.id.cache_path).text = stats.storagePath
        view.findViewById<ProgressBar>(R.id.cache_progress).progress = stats.percentUsed
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}

class SettingsCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_settings, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val option = item as SettingsOption
        view(viewHolder).findViewById<TextView>(R.id.settings_label).text = option.label
    }

    private fun view(vh: ViewHolder) = vh.view

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
