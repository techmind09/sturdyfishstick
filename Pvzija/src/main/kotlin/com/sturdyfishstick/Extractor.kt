package com.sturdyfishstick

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

open class PerverZijaExtractor : ExtractorApi() {
    override var name = "PerverZija"
    override var mainUrl = "https://pervl2.xtremestream.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("kraptor_PerverZijaExtract", "url = $url")

        // Determine if it uses the newer xtremestream script style or fallback loop
        try {
            val html = app.get(url, referer = referer).text
            val playerScript = Jsoup.parse(html)
                .selectXpath("//script[contains(text(),'var video_id')]")
                .html()

            if (playerScript.isNotBlank()) {
                val videoId = playerScript.substringAfter("var video_id = `").substringBefore("`;")
                val m3u8LoaderUrl = playerScript.substringAfter("var m3u8_loader_url = `").substringBefore("`;")

                if (videoId.isNotBlank() && m3u8LoaderUrl.isNotBlank()) {
                    val resolutions = listOf(1080, 720, 480)
                    resolutions.forEach { resolution ->
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - $resolution",
                                url = "$m3u8LoaderUrl/$videoId&q=$resolution",
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = resolution
                                this.referer = url
                                this.headers = mapOf(
                                    "Accept" to "*/*",
                                    "Referer" to url,
                                    "User-Agent" to USER_AGENT
                                )
                            }
                        )
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.d("kraptor_PerverZijaExtract", "Script parse failed, fallback to loop: ${e.message}")
        }

        // Fallback / standard server generation method
        val liste = listOf("480", "720", "1080", "2160")
        liste.forEach { kalite ->
            val videoQuality = kalite.toIntOrNull() ?: 720
            val changeUrl = url.replace("index.php", "xs1.php") + "&q=$videoQuality"

            Log.d("kraptor_PerverZijaExtract", "changeUrl = $changeUrl")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $videoQuality",
                    url = changeUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = videoQuality
                }
            )
        }
    }
}

// Separate or secondary Extractor class for Xtremestream if you prefer it modular
open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl4.xtremestream.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: "$mainUrl/").text
        val playerScript = Jsoup.parse(html)
            .selectXpath("//script[contains(text(),'var video_id')]")
            .html()

        if (playerScript.isNotBlank()) {
            val videoId = playerScript.substringAfter("var video_id = `").substringBefore("`;")
            val m3u8LoaderUrl = playerScript.substringAfter("var m3u8_loader_url = `").substringBefore("`;")

            if (videoId.isNotBlank() && m3u8LoaderUrl.isNotBlank()) {
                val resolutions = listOf(1080, 720, 480)

                resolutions.forEach { resolution ->
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $resolution",
                            url = "$m3u8LoaderUrl/$videoId&q=$resolution",
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = resolution
                            this.referer = url
                            this.headers = mapOf(
                                "Accept" to "*/*",
                                "Referer" to url,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
            }
        }
    }
}
