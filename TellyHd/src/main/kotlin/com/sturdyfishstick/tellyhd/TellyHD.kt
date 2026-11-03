package com.sturdyfishstick.tellyhd

import android.annotation.SuppressLint
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        
        val imgElement = this.select("div.poster img").last()
        var posterUrl = imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() } 
            ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotEmpty() }
            ?: imgElement?.attr("src")

        if (posterUrl != null && posterUrl.contains(".gif")) {
            posterUrl = this.select("div.poster img").attr("data-wpfc-original-src").takeIf { it.isNotEmpty() } ?: posterUrl
        }
        posterUrl = fixUrlNull(posterUrl)

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

                val json = LinkData(type, post, nume).toJson()
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
        val loadData = tryParseJson<LinkData>(data)
        val response = app.post(
            url = "$mainUrl/wp-admin/admin-ajax.php", 
            data = mapOf(
                "action" to "doo_player_ajax", 
                "post" to "${loadData?.post}", 
                "nume" to "${loadData?.nume}", 
                "type" to "${loadData?.type}"
            ), 
            referer = mainUrl, 
            headers = mapOf(
                "Accept" to "*/*", 
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<ResponseHash>()

        val iframe = response?.embedUrl?.getIframe() ?: return false

        if (!iframe.contains("youtube") && iframe.isNotEmpty()) {
            loadExtractor(
                iframe,
                "$mainUrl/",
                subtitleCallback,
                callback
            )
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

    private fun String.getIframe(): String {
        return Jsoup.parse(this).select("iframe").attr("src")
    }

    data class LinkData(
        val type: String? = null,
        val post: String? = null,
        val nume: String? = null,
    )

    data class ResponseHash(
        @JsonProperty("embed_url")
        val embedUrl: String? = null,
        val type: String? = null,
    )
}
