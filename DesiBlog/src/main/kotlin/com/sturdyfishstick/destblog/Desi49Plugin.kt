package com.sturdyfishstick.destblog

import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudStreamPlugin
class Desi49Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Desi49Provider())
    }
}
