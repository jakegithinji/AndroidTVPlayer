package com.androidtvplayer.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object PreferencesManager {

    private const val PREFS_NAME = "tvplayer_prefs"
    private const val KEY_CACHE_SIZE_MB = "cache_size_mb"
    private const val KEY_STREAMS = "streams_json"
    private const val KEY_LAST_PLAYED_URL = "last_played_url"

    // Default: 20 480 MB = 20 GB
    private const val DEFAULT_CACHE_SIZE_MB = 20_480L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Cache size ──────────────────────────────────────────────────────────

    fun getCacheSizeMb(context: Context): Long =
        prefs(context).getLong(KEY_CACHE_SIZE_MB, DEFAULT_CACHE_SIZE_MB)

    fun setCacheSizeMb(context: Context, sizeMb: Long) {
        prefs(context).edit().putLong(KEY_CACHE_SIZE_MB, sizeMb).apply()
    }

    // ── Stream list ─────────────────────────────────────────────────────────

    fun getStreams(context: Context): MutableList<StreamItem> {
        val json = prefs(context).getString(KEY_STREAMS, null) ?: return mutableListOf(
            // Built-in demo streams
            StreamItem(
                id = "demo_hls_1",
                title = "Apple HLS Demo (4K HDR)",
                url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/adv_dv_atmos/main.m3u8",
                type = StreamType.HLS
            ),
            StreamItem(
                id = "demo_hls_2",
                title = "Blender Open Movie (HLS)",
                url = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
                type = StreamType.HLS
            ),
            StreamItem(
                id = "demo_dash_1",
                title = "Blender Open Movie (DASH)",
                url = "https://bitdash-a.akamaihd.net/content/sintel/sintel.mpd",
                type = StreamType.DASH
            )
        )
        return try {
            val type = object : TypeToken<MutableList<StreamItem>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveStreams(context: Context, streams: List<StreamItem>) {
        prefs(context).edit()
            .putString(KEY_STREAMS, Gson().toJson(streams))
            .apply()
    }

    // ── Last played ─────────────────────────────────────────────────────────

    fun setLastPlayedUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_LAST_PLAYED_URL, url).apply()
    }

    fun getLastPlayedUrl(context: Context): String? =
        prefs(context).getString(KEY_LAST_PLAYED_URL, null)
}
