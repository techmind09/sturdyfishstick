package com.sturdyfishstick.epornerindian

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class EpornerIndianPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Eporner())
    }
}
