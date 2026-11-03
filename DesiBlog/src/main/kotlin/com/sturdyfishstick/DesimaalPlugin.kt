package com.sturdyfishstick

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesimaalPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DesimaalProvider())
    }
}
