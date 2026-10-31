Package com.sturdyfishstick.tellyhd

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TellyHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TellyHD())
        registerExtractorAPI(Ds2play())
        registerExtractorAPI(DoodCom())
        registerExtractorAPI(DoodTo())
        registerExtractorAPI(DoodWatch())
        registerExtractorAPI(DoodLa())
        registerExtractorAPI(DoodPm())
        registerExtractorAPI(DoodSh())
        registerExtractorAPI(DoodWs())
        registerExtractorAPI(DoodOne())
        registerExtractorAPI(DoodSo())
        registerExtractorAPI(DoodWork())
        registerExtractorAPI(Lulustream())
        registerExtractorAPI(Luluvid())
        registerExtractorAPI(Luluvdo())
        registerExtractorAPI(Lulust())
        registerExtractorAPI(CdnTelly())
        registerExtractorAPI(Ultrastream())
        registerExtractorAPI(Bysezoxexe())
        registerExtractorAPI(VidaraTo())
        registerExtractorAPI(Vidhideplus())
        registerExtractorAPI(Vidhidehub())
        registerExtractorAPI(TellyXin())
        registerExtractorAPI(Pixeldrain())
    }

    companion object {
        var cachedDomains: Domains? = Domains("https://tellyhd.st")

        suspend fun getDomains(forceRefresh: Boolean = false): Domains? {
            return cachedDomains
        }

        data class Domains(
            @JsonProperty("tellyhd")
            val Tellyhd: String,
        )
    }
}
