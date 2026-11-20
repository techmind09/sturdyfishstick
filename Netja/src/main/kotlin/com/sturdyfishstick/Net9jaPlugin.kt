package com.sturdyfishstick.taboodaddy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Net9jaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Net9jaProvider())
    }
}
