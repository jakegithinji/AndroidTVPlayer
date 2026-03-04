package com.androidtvplayer

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.androidtvplayer.cache.CacheManager
import com.androidtvplayer.data.PreferencesManager
import java.io.File

class TVPlayerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CacheManager.initialize(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        CacheManager.release()
    }

    companion object {
        lateinit var instance: TVPlayerApplication
            private set
    }
}
