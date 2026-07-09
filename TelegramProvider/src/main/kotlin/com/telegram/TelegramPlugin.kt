package com.telegram

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TelegramPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize Telegram repository (starts proxy, checks session)
        TelegramRepository.initialize(context)

        // Register the main provider API
        registerMainAPI(TelegramProvider())
        registerMainAPI(TeleflixProvider())

        // Hook up the plugin settings button to show the bottom sheet dialog
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            val frag = TelegramSettingsFragment(this)
            activity?.let {
                frag.show(it.supportFragmentManager, "TelegramSettings")
            }
        }
    }
}