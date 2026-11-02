package com.sturdyfishstick

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller

class Perverzija : MainAPI() {
    override var mainUrl          = "https://tube.perverzija.com"
    override var name             = "Perverzija"
    override val hasMainPage      = true
    override var lang             = "en"
    override val hasQuickSearch   = true
    override val supportedTypes   = setOf(TvType.NSFW)
    override val vpnStatus        = VPNStatus.MightBeNeeded

    private val cfInterceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/featured-scenes/page/%d/?orderby=date" to "Featured",
        "$mainUrl/studio/onlyfans/page/%d/" to "Onlyfans",
        "$mainUrl/studio/vxn/page/%d/" to "Vxn",
        "$mainUrl/studio/brazzers/page/%d/" to "Brazzers",
        "$mainUrl/studio/private/page/%d/" to "Private",
        "$mainUrl/studio/nubiles/page/%d/" to "Nubiles",
        "$mainUrl/studio/realitykings/page/%d/" to "Reality Kings",
        "$mainUrl/studio/bangbros/page/%d/" to "Bangbros",
        "$mainUrl/studio/naughtyamerica/page/%d/" to "Naughty America",
        "$mainUrl/tag/4k-quality/page/%d/" to "4K Quality",
        "$mainUrl/full-movie/page/%d/" to "Full Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.format(page)
        val document = app.get(url, interceptor = cfInterceptor, timeout = 100L).document
        
        // Multi-selector to handle both modern grid styles & template variations across the site
        val home = document.select("div.row div div.post, div.col-md-3").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ), 
            hasNext = true
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Fallback checks for various poster and link structures
        val posterUrl = fixUrlNull(
            this.selectFirst("div.item-thumbnail img")?.attr("src") 
                ?: this.selectFirst("img")?.attr("src") 
                ?: this.selectFirst("img")?.attr("data-src")
        )
        val title = this.selectFirst("div.item-head a")?.text() 
            ?: this.selectFirst("img")?.attr("title") 
            ?: this.selectFirst("a")?.attr("title") 
            ?: return null
        
        val href = fixUrlNull(
            this.selectFirst("div.item-head a")?.attr("href") 
                ?: this.selectFirst("a")?.attr("href")
        ) ?: return null

        return newMovieSearchResponse(title, href, TvType.NSFW) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (query.contains(" ") || page > 1) {
            "$mainUrl/page/$page/?s=${query.replace(" ", "+")}&orderby=date"
        } else {
            "$mainUrl/tag/$query/page/$page/"
        }

        val document = app.get(url, interceptor = cfInterceptor).document
        val searchResults = document.select("div.row div div.post, div.col-md-3").mapNotNull { 
            it.toMainPageResult() 
        }.distinctBy { it.url }

        return newSearchResponseList(searchResults, hasNext = searchResults.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query).list

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cfInterceptor).document

        val title = document.selectFirst("div.title-info h1.light-title.entry-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("div#featured-img-id img")?.attr("src") 
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        
        val pTags = document.select("div.item-content p")
        val description = if (pTags.isNotEmpty()) {
            StringBuilder().apply { pTags.forEach { append(it.text()).append("\n") } }.toString().trim()
        } else {
            document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        }

        val year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.item-tax-list div a, div.item-tax-list div:has(strong:contains(tags)) a").map { it.text() }
        val score = document.selectFirst("span.dt_rating_vgs")?.text()?.trim()
        val duration = document.selectFirst("span.runtime")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val actors = document.select("div.item-tax-list div:has(strong:contains(stars)) a").map { Actor(it.text()) }
        
        val recommendations = document.select("div.related-gallery dl.gallery-item, div.srelacionados article").mapNotNull { 
            it.toRecommendationResult() 
        }
        
        val trailer = Regex("""embed\/(.*)\?rel""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = score?.let { Score.from10(it) }
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title = this.selectFirst("dd a, a img")?.text() 
            ?: this.selectFirst("a img")?.attr("alt") 
            ?: return null
        val href = fixUrlNull(this.selectFirst("dt a, a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("dt a img")?.attr("src") 
                ?: this.selectFirst("a img")?.attr("data-src")
                ?: this.selectFirst("a img")?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_$name", "data = $data")
        val document = app.get(data, interceptor = cfInterceptor).document

        // Safely pull iframe sources from alternative container layouts
        val iframeUrl = document.selectFirst("div#player-embed iframe, iframe")?.attr("src")

        if (!iframeUrl.isNullOrBlank()) {
            Log.d("kraptor_$name", "iframe = $iframeUrl")
            loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}
