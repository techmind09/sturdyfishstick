package com.sturdyfishstick.tellyhd

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TellyHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TellyHD())
        registerExtractorAPI(Ds2play())
        registerExtractorAPI(Vidsp())
        registerExtractorAPI(VidHideplus())
        registerExtractorAPI(VidHidedht())
        registerExtractorAPI(Vidhidehub())
        registerExtractorAPI(Bigwarp())
        registerExtractorAPI(Vidhidetoul())
        registerExtractorAPI(Luluvid())
        registerExtractorAPI(Xtapes())
        registerExtractorAPI(Ultrastream())
    }

    companion object {
        var cachedDomains: Domains = Domains("https://tellyhd.st")

        suspend fun getDomains(forceRefresh: Boolean = false): Domains {
            return cachedDomains
        }

        data class Domains(
            @JsonProperty("tellyhd")
            val Tellyhd: String,
        )
    }
}
