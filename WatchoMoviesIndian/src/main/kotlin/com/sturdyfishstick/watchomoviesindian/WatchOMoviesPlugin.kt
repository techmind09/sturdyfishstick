package com.sturdyfishstick.watchomoviesindian

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class WatchOMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        // Updated to register WatchOMoviesIndianProvider() correctly
        registerMainAPI(WatchOMoviesIndianProvider())
    }
}
