package com.googledrive

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GoogleDrivePlugin : Plugin() {

    init {
        openSettings = { ctx ->
            var activity: AppCompatActivity? = null
            var currentContext = ctx
            while (currentContext is android.content.ContextWrapper) {
                if (currentContext is AppCompatActivity) {
                    activity = currentContext
                    break
                }
                currentContext = currentContext.baseContext
            }
            
            val frag = GoogleDriveSettingsFragment(this)
            activity?.let {
                frag.show(it.supportFragmentManager, "GoogleDriveSettings")
            }
        }
    }

    override fun load(context: Context) {
        registerMainAPI(GoogleDriveProvider())
    }
}
