package com.externalplayers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExternalPlayersPlugin : Plugin() {

    override fun load(context: Context) {
        // Register MPV Infinity and 1DM as external players and video click actions
        registerVideoClickAction(MpvInfinityAction())
        registerVideoClickAction(OneDmAction())
    }
}
