package com.sturdyfishstick.destblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Desi49Provider : MainAPI() {
    override var mainUrl = "https://desi49.to"
    override var name = "Desi49"
    override val supportedTypes = setOf(TvType.Adult, TvType.Movie)
    override var lang = "hi"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/videos" to "All Videos",
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val home = doc.select("a[href*='/videos/'], div.group").mapNotNull { element ->
            val title = element.select("h2, .title, img").attr("alt").ifEmpty { element.text() }
            val href = fixUrl(element.attr("href"))
            val posterUrl = fixUrl(element.select("img").attr("src"))
            
            if (href.contains("/videos/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else null
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/videos?search=$query").document
        return doc.select("a[href*='/videos/']").mapNotNull { element ->
            val title = element.select("h2, img").attr("alt").ifEmpty { element.text() }
            val href = fixUrl(element.attr("href"))
            val posterUrl = fixUrl(element.select("img").attr("src"))
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("h1").text()
        val poster = doc.select("video, img").attr("src")
        val description = doc.select("p").text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrl(poster)
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        
        doc.select("video source, video").forEach { element ->
            val videoUrl = element.attr("src").ifEmpty { element.attr("data-src") }
            if (videoUrl.isNotEmpty()) {
                callback(
                    ExtractorLink(
                        name,
                        name,
                        fixUrl(videoUrl),
                        data,
                        Qualities.Unknown.value,
                        false
                    )
                )
            }
        }
        
        val html = doc.html()
        val regex = Regex("https://[^\"]+\\.mp4")
        regex.findAll(html).forEach { match ->
            val videoUrl = match.value
            callback(
                ExtractorLink(
                    name,
                    name,
                    videoUrl,
                    data,
                    Qualities.Unknown.value,
                    false
                )
            )
        }

        return true
    }
}
