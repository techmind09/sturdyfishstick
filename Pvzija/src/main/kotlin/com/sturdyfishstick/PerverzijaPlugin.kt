package com.sturdyfishstick.pvzija

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PerverzijaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Perverzija())
        registerMainAPI(XtremestreamMain())
        registerExtractorAPI(PerverZijaExtractor())
        registerExtractorAPI(XtremestreamExtractor())
    }
}
