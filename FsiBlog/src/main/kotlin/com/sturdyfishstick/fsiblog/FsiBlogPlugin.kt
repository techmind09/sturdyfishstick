package com.sturdyfishstick.fsiblog

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Fsi52Plugin : Plugin() {
    override fun load(context: Context) {
        // Apne provider ko load karwao
        registerMainAPI(Fsi52Provider())
        

    }
}
