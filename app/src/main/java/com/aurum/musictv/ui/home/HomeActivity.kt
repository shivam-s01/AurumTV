package com.aurum.musictv.ui.home

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.aurum.musictv.R

class HomeActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.home_fragment_container, HomeBrowseFragment())
                .commitNow()
        }
    }
}
