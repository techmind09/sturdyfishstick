package com.sturdyfishstick.tellyhd

import android.annotation.SuppressLint
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Year 

class TellyHD : MainAPI() {

    override var mainUrl: String = "https://tellyhd.si"
    override var name = "TellyHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries, TvType.Movie)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    @SuppressLint("NewApi")
    override val mainPage = mainPageOf(
        "release/${Year.now().value}" to "Latest",
        "genre/18plus" to "18+",
        "genre/9redmovie" to "9redmovie",
        "genre/addatv" to "Addatv",
        "genre/aahasflix" to "AahaFlix",
        "genre/altbalaji" to "Alt Balaji",
        "genre/atrangii" to "Atrangii",
        "genre/jav" to "JAV",
        "genre/bindastimes" to "Bindastimes",
        "genre/brazzers" to "Brazzers",
        "genre/cineprime" to "CinePrime",
        "genre/digital-playground" to "Digital Playground",
        "genre/english" to "English",
        "genre/france" to "France",
        "genre/goddesmahi" to "GoddesMahi",
        "genre/hindi" to "Hindi",
        "genre/marcdorcel" to "Marc Dorcel",
        "genre/movies" to "Movies",
        "genre/neonx" to "NeonX",
        "genre/niksindian" to "Niksindian",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url, headers = headers).document
        val home = document.select("div.items.normal article, div#archive-content article, div.items.full article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episodes/") -> {
                var title = uri.substringAfter("$mainUrl/episodes/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }
            uri.contains("/seasons/") -> {
                var title = uri.substringAfter("$mainUrl/seasons/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }
            else -> uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 > a")?.text() ?: return null
        val href = getProperLink(fixUrl(this.selectFirst("h3 > a")!!.attr("href")))
        var posterUrl = this.select("div.poster img").last()?.getImageAttr()
        if (posterUrl != null && posterUrl.contains(".gif")) {
            posterUrl = fixUrlNull(this.select("div.poster img").attr("data-wpfc-original-src"))
        }
        val quality = getQualityFromString(this.select("span.quality").text())
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query", headers = headers).document
        return document.select("div.result-item").map {
            val title = it.selectFirst("div.title > a")!!.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(it.selectFirst("div.title > a")!!.attr("href"))
            val posterUrl = it.selectFirst("img")!!.attr("src")
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url, headers = headers)
        val document = request.document
        val title = document.selectFirst("div.data > h1")?.text()?.trim().toString()
        var posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = fixUrlNull(document.select("div.poster img").attr("src"))
        }
        val description = document.select("div.wp-content > p").text().trim()
        
        val episodes = document.select("ul#playeroptionsul > li, div.play-options li, .sources-box li, .dooplay_player_option, .options li")
            .mapNotNull { element ->
                val name = element.selectFirst("span.title")?.text()?.trim()
                    ?: element.select("span").text().trim()
                    ?: element.text().trim()

                val type = element.attr("data-type").ifEmpty { element.attr("data-film") }.ifEmpty { "movie" }
                val post = element.attr("data-post").ifEmpty { element.attr("data-id") }.ifEmpty { element.attr("data-movie") }
                val nume = element.attr("data-nume").ifEmpty { element.attr("data-episode") }.ifEmpty { element.attr("data-seq") }.ifEmpty { "1" }

                if (post.isNullOrEmpty() || nume.isNullOrEmpty()) return@mapNotNull null
                if (name.contains("trailer", ignoreCase = true)) return@mapNotNull null

                val json = LinkData(type, post, nume, url).toJson()
                newEpisode(json) {
                    this.name = name.ifEmpty { "Server" }
                }
            }.distinctBy { it.name }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LinkData>(data) ?: return false
        val refererUrl = loadData.url ?: "$mainUrl/"

        val apiUrl = "$mainUrl/wp-json/dooplayer/v2/${loadData.post}/${loadData.type}/${loadData.nume}"
        val apiRes = runCatching { app.get(apiUrl, headers = headers).text }.getOrNull() ?: ""

        val embedUrl = tryParseJson<ResponseHash>(apiRes)?.embedUrl 
            ?: Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(apiRes)?.groupValues?.get(1)?.replace("\\\\/", "/")?.replace("\\\"", "\"") ?: ""

        var iframeUrl = Jsoup.parse(embedUrl).select("iframe").attr("src")
        if (iframeUrl.isEmpty()) {
            iframeUrl = Regex("src=['\"]([^'\"]+)['\"]").find(embedUrl)?.groupValues?.get(1) ?: iframeUrl
        }

        if (iframeUrl.isEmpty() || iframeUrl.contains("about:blank")) {
            val doc = app.get(refererUrl, headers = headers).document
            iframeUrl = doc.select("iframe").attr("src")
                .ifEmpty { doc.select("source").attr("src") }
        }

        val finalIframe = when {
            iframeUrl.startsWith("//") -> "https:$iframeUrl"
            else -> iframeUrl
        }.trim()

        if (finalIframe.isEmpty() || finalIframe.contains("about:blank")) return false

        if (!finalIframe.contains("youtube", ignoreCase = true)) {
            when {
                finalIframe.contains("telly.xin", ignoreCase = true) || finalIframe.contains("ultrastream", ignoreCase = true) -> {
                    TellyXin().getUrl(finalIframe, refererUrl)?.forEach(callback)
                }
                finalIframe.contains("pixeldrain.com", ignoreCase = true) -> {
                    val id = finalIframe.substringAfterLast("/").substringBefore("?")
                    callback.invoke(
                        newExtractorLink("PixelDrain", "PixelDrain", "https://pixeldrain.com/api/file/$id?download", ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
                finalIframe.contains("vcloud") || finalIframe.contains("hubcloud") -> {
                    VCloud().getUrl(finalIframe, refererUrl, subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(finalIframe, refererUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    data class LinkData(
        val type: String? = null,
        val post: String? = null,
        val nume: String? = null,
        val url: String? = null,
    )

    data class ResponseHash(
        @JsonProperty("embed_url")
        val embedUrl: String,
        val type: String? = null,
    )
}
