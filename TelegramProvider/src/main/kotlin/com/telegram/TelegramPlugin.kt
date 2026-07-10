package com.telegram

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TelegramPlugin : Plugin() {

    override var openSettings: ((Context) -> Unit)? = { ctx ->
        var activity: AppCompatActivity? = null
        var currentContext = ctx
        while (currentContext is android.content.ContextWrapper) {
            if (currentContext is AppCompatActivity) {
                activity = currentContext
                break
            }
            currentContext = currentContext.baseContext
        }
        
        val frag = TelegramSettingsFragment(this)
        activity?.let {
            frag.show(it.supportFragmentManager, "TelegramSettings")
        }
    }

    override fun load(context: Context) {
        // Initialize Telegram repository (starts proxy, checks session)
        TelegramRepository.initialize(context)

        // Register the main provider API
        registerMainAPI(TelegramProvider())
        registerMainAPI(TeleflixProvider())
    }
}