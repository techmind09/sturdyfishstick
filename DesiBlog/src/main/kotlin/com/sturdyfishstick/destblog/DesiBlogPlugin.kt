package com.sturdyfishstick.desiblog

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Desi49Plugin : Plugin() {
    override fun load(context: Context) {
        // Class name fixed to Desi49Provider
        registerMainAPI(Desi49Provider())
    }
}
