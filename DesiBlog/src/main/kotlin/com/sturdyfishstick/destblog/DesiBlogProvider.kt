package com.sturdyfishstick.desiblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Desi49Provider : MainAPI() {
    override var mainUrl = "https://desi49.to"
    override var name = "Desi49"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "videos" to "Most Recent",
        "videos/filter/views/all/1" to "Most Viewed",
        "categories" to "All Categories",
        "category/amateur" to "Amateur",
        "category/viral-mms" to "Viral MMS",
        "category/big-tits" to "Big Tits",
        "category/milf" to "MILF",
        "category/blowjob" to "Blowjob",
        "category/big-ass" to "Big Ass",
        "category/bengali" to "Bengali",
        "category/threesome" to "Threesome",
        "category/anal" to "Anal"
    )

    private fun toResult(post: Element): SearchResponse? {
        val anchor = post.selectFirst("a[href]") ?: post.selectFirst("a") ?: return null
        val url = fixUrl(anchor.attr("href"))
        if (url.isBlank() || url.contains("/page/") || url == mainUrl) return null

        val title = post.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: post.selectFirst("h2, h3, .title, .category-name, span")?.text()?.takeIf { it.isNotBlank() }
            ?: anchor.text()

        if (title.isBlank() || title.equals("Videos", ignoreCase = true)) return null

        // Fix image selection to avoid grabbing site logos/avatars
        val imgElement = post.select("img").firstOrNull { img ->
            val src = img.attr("src") + img.attr("data-src") + img.attr("data-lazy-src")
            !src.contains("logo") && !src.contains("avatar") && !src.contains("user")
        } ?: post.selectFirst("img")

        val imageUrl = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        return newMovieSearchResponse(fixTitle(title), url, TvType.NSFW) {
            this.posterUrl = fixUrlNull(imageUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.isEmpty() && page == 1 -> "$mainUrl/"
            request.data.isEmpty() -> "$mainUrl/page/$page/"
            request.data == "categories" -> "$mainUrl/categories/"
            page == 1 -> "$mainUrl/${request.data}/"
            else -> "$mainUrl/${request.data}/page/$page/"
        }
        val document = app.get(url).document
        val home = document.select("article, .post, .item, div.flex.flex-col, .category-item, .card").mapNotNull { toResult(it) }.distinctBy { it.url }
        val hasNext = document.select("link[rel=next]").isNotEmpty() && home.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, home, true), hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/page/$page/?s=$query").document
        val searchResult: List<SearchResponse> =
            document.select("article, .post, .item, div.flex.flex-col").mapNotNull { toResult(it) }.distinctBy { it.url }
        return searchResult.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim().toString()
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val streamLink = doc.selectFirst("iframe")?.attr("src")
            ?: doc.selectFirst("iframe[data-litespeed-src]")?.attr("data-litespeed-src")
            ?: doc.selectFirst("video source")?.attr("src")
            ?: url

        return newMovieLoadResponse(title, url, TvType.NSFW, fixUrl(streamLink)) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isEmpty()) return false
        return loadExtractor(data, referer = mainUrl, subtitleCallback, callback)
    }
}
