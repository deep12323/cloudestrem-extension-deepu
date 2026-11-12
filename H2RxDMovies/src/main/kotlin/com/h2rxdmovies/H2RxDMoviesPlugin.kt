package com.h2rxdmovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class H2RxDMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(H2RxDMoviesProvider())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HubCloudIst())
    }
}
