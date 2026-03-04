package com.androidtvplayer.ui.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.androidtvplayer.R

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, BrowseFragment())
                .commit()
        }
    }
}
