package com.androidtvplayer.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.leanback.preference.LeanbackSettingsFragmentCompat
import androidx.preference.*
import com.androidtvplayer.R
import com.androidtvplayer.cache.CacheManager
import com.androidtvplayer.data.PreferencesManager
import com.androidtvplayer.data.StreamItem
import com.androidtvplayer.data.StreamType

class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        // ── Cache section ─────────────────────────────────────────────────
        PreferenceCategory(context).apply {
            title = "Cache Settings"
            screen.addPreference(this)

            // Cache size preference
            addPreference(EditTextPreference(context).apply {
                key = "cache_size_mb"
                title = "Cache Size"
                val currentMb = PreferencesManager.getCacheSizeMb(context)
                summary = "$currentMb MB (${currentMb / 1024} GB)"
                text = currentMb.toString()
                setOnPreferenceChangeListener { pref, newValue ->
                    val mb = (newValue as String).toLongOrNull()
                    if (mb != null && mb > 0) {
                        PreferencesManager.setCacheSizeMb(context, mb)
                        pref.summary = "$mb MB (${mb / 1024} GB)"
                        CacheManager.rebuildCache()
                        true
                    } else {
                        Toast.makeText(context, "Enter a valid size in MB", Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            })

            // Cache path info
            addPreference(Preference(context).apply {
                title = "Cache Location"
                val stats = CacheManager.getCacheStats()
                summary = stats.storagePath
                isEnabled = false
            })

            // Cache usage info
            addPreference(Preference(context).apply {
                title = "Cache Usage"
                val stats = CacheManager.getCacheStats()
                summary = "${stats.usedMb} MB used of ${stats.maxMb} MB (${stats.percentUsed}%)"
                isEnabled = false
            })

            // Clear cache
            addPreference(Preference(context).apply {
                title = "Clear Cache"
                summary = "Delete all cached stream data"
                setOnPreferenceClickListener {
                    CacheManager.clearCache()
                    Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }

        // ── Streams section ───────────────────────────────────────────────
        PreferenceCategory(context).apply {
            title = "Streams"
            screen.addPreference(this)

            // Add stream
            addPreference(Preference(context).apply {
                title = "Add Stream"
                summary = "Add a new HLS or DASH stream URL"
                setOnPreferenceClickListener {
                    showAddStreamDialog()
                    true
                }
            })

            // List existing streams
            val streams = PreferencesManager.getStreams(context)
            streams.forEach { stream ->
                addPreference(Preference(context).apply {
                    title = stream.title
                    summary = stream.url
                    setOnPreferenceClickListener {
                        showDeleteStreamDialog(stream)
                        true
                    }
                })
            }
        }

        preferenceScreen = screen
    }

    private fun showAddStreamDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val titleInput = EditText(context).apply {
            hint = "Stream title"
        }
        val urlInput = EditText(context).apply {
            hint = "Stream URL (m3u8 or mpd)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }

        layout.addView(titleInput)
        layout.addView(urlInput)

        AlertDialog.Builder(context)
            .setTitle("Add New Stream")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val title = titleInput.text.toString().trim()
                val url = urlInput.text.toString().trim()

                if (title.isEmpty() || url.isEmpty()) {
                    Toast.makeText(context, "Title and URL are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val type = if (url.endsWith(".mpd")) StreamType.DASH else StreamType.HLS
                val streams = PreferencesManager.getStreams(context)
                streams.add(StreamItem(title = title, url = url, type = type))
                PreferencesManager.saveStreams(context, streams)

                Toast.makeText(context, "Stream added", Toast.LENGTH_SHORT).show()
                // Refresh fragment
                onCreatePreferences(null, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteStreamDialog(stream: StreamItem) {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle("Remove Stream")
            .setMessage("Remove \"${stream.title}\"?")
            .setPositiveButton("Remove") { _, _ ->
                val streams = PreferencesManager.getStreams(context)
                streams.removeAll { it.id == stream.id }
                PreferencesManager.saveStreams(context, streams)
                Toast.makeText(context, "Stream removed", Toast.LENGTH_SHORT).show()
                onCreatePreferences(null, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
