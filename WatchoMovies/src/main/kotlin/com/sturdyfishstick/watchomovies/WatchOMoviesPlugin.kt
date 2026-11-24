package com.sturdyfishstick.watchomoviesindian

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WatchOMoviesPlugin : Plugin() {
    override fun load() {
        val provider = WatchOMoviesProvider()

        // App start par saved domain load karein
        AcraApplication.context?.let { ctx ->
            val prefs = WatchOMoviesSettingsDialog.getPrefs(ctx)
            provider.mainUrl = prefs.getString(
                WatchOMoviesSettingsDialog.KEY_CUSTOM_DOMAIN,
                WatchOMoviesSettingsDialog.DEFAULT_DOMAIN
            ) ?: WatchOMoviesSettingsDialog.DEFAULT_DOMAIN
        }

        registerMainAPI(WatchOMoviesProvider())

        // CloudStream Extensions me Settings icon enable karein
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            activity?.let {
                WatchOMoviesSettingsDialog {
                    // Save click karne par turant naya domain apply hoga
                    val prefs = WatchOMoviesSettingsDialog.getPrefs(ctx)
                    provider.mainUrl = prefs.getString(
                        WatchOMoviesSettingsDialog.KEY_CUSTOM_DOMAIN,
                        WatchOMoviesSettingsDialog.DEFAULT_DOMAIN
                    ) ?: WatchOMoviesSettingsDialog.DEFAULT_DOMAIN
                }.show(it.supportFragmentManager, "WatchOMoviesSettings")
            }
        }
    }
}

