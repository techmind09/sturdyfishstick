package com.sturdyfishstick.watchomoviesindian

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WatchOMoviesPlugin : Plugin() {
    override fun load() {
        registerMainAPI(WatchOMoviesIndianProvider())
    }
}
