package com.sturdyfishstick.watchomovies

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

        // Register all custom extractors
        registerExtractorAPI(LuluCustomStream())
        registerExtractorAPI(LuluVidCustom())
        registerExtractorAPI(LuluVdo())
        registerExtractorAPI(LuluPvp())
        registerExtractorAPI(Vidsp())
        registerExtractorAPI(VidHideplus())
        registerExtractorAPI(VidHidedht())
        registerExtractorAPI(Vidhidehub())
        registerExtractorAPI(Vidhidetoul())
        registerExtractorAPI(Xtapes())
        registerExtractorAPI(FilemoonCustom())
        registerExtractorAPI(Ds2play())
        registerExtractorAPI(Bigwarp())

        // Connect MovieFox-style Settings Dialog
        openSettings = { ctx ->
            WatchOMoviesSettingsDialog.show(
                rawCtx = ctx,
                onSettingsApplied = { provider.applySavedSettings() },
                onClearProviderCfCache = { provider.clearCfCache() }
            )
        }
    }
}

