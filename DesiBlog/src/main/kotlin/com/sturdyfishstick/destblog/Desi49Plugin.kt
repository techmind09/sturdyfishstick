package com.sturdyfishstick.desiblog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Desi49Provider
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import android.content.Context

@CloudStreamPlugin
class Desi49Plugin : Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(Desi49Provider())
    }
}
