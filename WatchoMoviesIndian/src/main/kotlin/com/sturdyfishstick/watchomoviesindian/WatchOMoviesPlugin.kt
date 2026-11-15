package com.sturdyfishstick.watchomoviesindian

import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class WatchOMoviesPlugin : BasePlugin() {
    override fun load(context: Context) {
        registerMainAPI(WatchOMoviesIndianProvider())
    }
}
