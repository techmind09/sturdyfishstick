package com.sturdyfishstick.desiblog

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DesiMaalPlugin : Plugin() {
    override fun load(context: Context) {
        // Apne provider ko load karwao
        registerMainAPI(DesiMaalProvider())
        

    }
}
