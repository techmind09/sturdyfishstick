package com.sturdyfishstick.tellyhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TellyHD : MainAPI() {
    override var mainUrl = "https://tellyhd.st"
    override var name = "TellyHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/tvshows/page/" to "TV Shows",
        "$mainUrl/genre/hindi-hot/" to "Hindi Hot",
        "$mainUrl/genre/ullu/" to "Ullu",
        "$mainUrl/genre/alt-balaji/" to "ALTBalaji",
        "$mainUrl/genre/prime-shots/" to "Prime Shots"
    )

    override suspend function mainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, headers = headers).document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".data h3 a, .title a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst(".data h3 a, .title a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        val quality = this.selectFirst(".quality")?.text()?.trim()

        return if (href.contains("/tvshows/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = headers).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    data class LinkData(
        val type: String,
        val post: String,
        val nume: String
    )

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url, headers = headers)
        val document = request.document
        val title = document.selectFirst("div.data > h1")?.text()?.trim().toString()
        var posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = fixUrlNull(document.select("div.poster img").attr("src"))
        }
        val description = document.select("div.wp-content > p").text().trim()
        
        // li ko hata kar direct attributes aur container classes ko target kiya gaya hai
        val episodes = document.select(".dooplay_player_option, [data-post], [data-nume]").mapNotNull { element ->
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
        }.distinctBy { data -> data.name }

        val isMovie = url.contains("/movies/") || episodes.size <= 1

        return if (isMovie && episodes.isNotEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val linkData = parseJson<LinkData>(data)
        val apiUrl = "$mainUrl/wp-json/dooplayer/v2/${linkData.post}/${linkData.type}/${linkData.nume}"
        
        val res = app.get(apiUrl, headers = headers).text
        val iframeUrl = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(res)?.groupValues?.get(1)?.replace("\\/", "/") ?: return false
        val decodedUrl = org.jsoup.Jsoup.parse(iframeUrl).text()
        val finalIframe = if (decodedUrl.startsWith("//")) "https:$decodedUrl" else decodedUrl

        if (finalIframe.contains("telly.xin")) {
            TellyXin().getUrl(finalIframe, "$mainUrl/")?.forEach(callback)
        } else {
            loadExtractor(finalIframe, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
}
