package com.sturdyfishstick.watchomoviesindian

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WatchOMoviesPlugin : Plugin() {
    override fun load() {
        CloudStreamApp.context?.let { WatchOMoviesSettingsDialog.bindContext(it) }

        val provider = WatchOMoviesProvider()
        provider.applySavedSettings()
        registerMainAPI(provider)

        openSettings = { ctx ->
            WatchOMoviesSettingsDialog.show(
                rawCtx = ctx,
                onSettingsApplied = { provider.applySavedSettings() },
                onClearProviderCfCache = { provider.clearCfCache() }
            )
        }
    }
}

