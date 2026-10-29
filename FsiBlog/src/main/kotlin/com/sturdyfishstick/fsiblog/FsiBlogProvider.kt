package com.sturdyfishstick.fsiblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class Fsi52Provider : MainAPI() {
    override var mainUrl = "https://fsi52.com"
    override var name = "Desi 18+"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    // Adult content ke liye
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest Videos",
        "category/big-boobs" to "Big Boobs",
        "category/college" to "College",
        "category/desi-blowjob" to "Desi Blowjob",
        "category/desi-romance" to "Desi Romance",
        "category/devar-bhabhi" to "Devar Bhabhi",
        "category/doggy-style" to "Doggy Style",
        "category/fingering" to "Fingering",
        "category/hindi-mms" to "Hindi MMS",
        "category/homemade-sex" to "Homemade Sex",
        "category/indian-aunty" to "Indian Aunty",
        "category/indian-bhabhi" to "Indian Bhabhi",
        "category/mature" to "Mature",
        "category/randi" to "Randi",
        "category/riding" to "Riding",
        "category/sex-with-boyfriend" to "Sex with Boyfriend",
        "category/village-sex" to "Village Sex"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page" else "$mainUrl/${request.data}/page/$page"
        }

        val document = app.get(url).document

        val searchResults = document.select("article.vcard").mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(aTag.attr("href"))
            val title = aTag.attr("title").takeIf { it.isNotBlank() } ?: element.selectFirst(".ct")?.text() ?: "Unknown Title"
            val posterUrl = element.selectFirst("img.lazy")?.attr("data-src")

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.url } // Duplicate URLs hatane ke liye

        if (searchResults.isEmpty()) return null
        return newHomePageResponse(request.name, searchResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document

        return document.select("article.vcard").mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(aTag.attr("href"))
            val title = aTag.attr("title").takeIf { it.isNotBlank() } ?: element.selectFirst(".ct")?.text() ?: ""
            val posterUrl = element.selectFirst("img.lazy")?.attr("data-src")

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.vp-title")?.text() ?: return null
        val posterUrl = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content") 
            ?: document.selectFirst("video")?.attr("poster")

        val tags = document.select(".tags-row a.tpill").map { it.text() }

        // HTML se Related Videos / Recommendations nikalna
        val recommendations = document.select(".vgrid article.vcard").mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val recLink = fixUrl(aTag.attr("href"))

            if (recLink == url) return@mapNotNull null // Same video ko list mein dobara na dikhaye

            val recTitle = aTag.attr("title").takeIf { it.isNotBlank() } ?: element.selectFirst(".ct")?.text() ?: ""
            val recPoster = element.selectFirst("img.lazy")?.attr("data-src")

            newMovieSearchResponse(recTitle, recLink, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // Direct MP4 Source Extract Karna
        val videoUrl = document.selectFirst("meta[itemprop=contentUrl]")?.attr("content")
            ?: document.selectFirst("source[type=video/mp4]")?.attr("src")

        if (videoUrl != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "HD Stream",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.P1080.value,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                )
            )
            return true
        }
        return false
    }
}
