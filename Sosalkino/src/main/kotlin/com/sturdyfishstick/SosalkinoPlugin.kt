package com.sturdyfishstick.sosalkino

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class  SosalkinoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SosalkinoProvider())
    }
}
