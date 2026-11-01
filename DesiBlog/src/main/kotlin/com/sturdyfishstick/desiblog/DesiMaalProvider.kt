package com.sturdyfishstick.desiblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.fasterxml.jackson.annotation.JsonProperty

class DesiMaalProvider : MainAPI() {
    override var mainUrl = "https://desimaal.live"
    override var name = "Desi 18+"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    // Adult content ke liye
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest Videos",
        // OTT Platforms
        "ott/ullu" to "Ullu Web Series",
        "ott/wow" to "WoW Web Series",
        "ott/moodx" to "MoodX Web Series",
        "ott/primeplay" to "PrimePlay Web Series",
        "ott/rabbit" to "Rabbit Web Series",
        "ott/atrangii" to "Atrangii Web Series",
        "ott/jugnu" to "Jugnu Web Series",
        "ott/prime-shots" to "Prime Shots Web Series",
        "ott/voovi" to "Voovi Web Series",
        "ott/hitprime" to "HitPrime Web Series",
        "ott/digi-movieplex" to "Digi Movieplex Web Series",
        "ott/bull" to "Bull Web Series",
        "ott/cineprime" to "CinePrime Web Series",
        "ott/altt" to "ALTT Web Series",
        "ott/hulchul" to "Hulchul Web Series",
        "ott/bigshots" to "BigShots Web Series",
        "ott/kooku" to "Kooku Web Series",
        "ott/hunters" to "Hunters Web Series",
        
        // Browse by Genre
        "genre/hot" to "Hot",
        "genre/uncut" to "Uncut",
        "genre/aunty" to "Aunty",
        "genre/sexy" to "Sexy",
        "genre/adult" to "Adult",
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

        val searchResults = document.select("article.vcard, .grid .card, .video-item").mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(aTag.attr("href"))
            val title = aTag.attr("title").takeIf { it.isNotBlank() } ?: element.selectFirst(".ct, .title")?.text() ?: "Unknown Title"
            val posterUrl = element.selectFirst("img.lazy, img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { t -> t.url }

        if (searchResults.isEmpty()) return null
        return newHomePageResponse(request.name, searchResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document

        return document.select("article.vcard, .grid .card, .video-item").mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(aTag.attr("href"))
            val title = aTag.attr("title").takeIf { it.isNotBlank() } ?: element.selectFirst(".ct, .title")?.text() ?: ""
            val posterUrl = element.selectFirst("img.lazy, img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }

            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { t -> t.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.vp-title, h1")?.text() ?: return null
        val posterUrl = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content") 
            ?: document.selectFirst("video")?.attr("poster")

        val tags = document.select(".tags-row a.tpill, .tags a").map { it.text() }

        val recommendations = document.select(".vgrid article.vcard, .recommendations .card").mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val recLink = fixUrl(aTag.attr("href"))

            if (recLink == url) return@mapNotNull null

            val recTitle = aTag.attr("title").takeIf { it.isNotBlank() } ?: element.selectFirst(".ct, .title")?.text() ?: ""
            val recPoster = element.selectFirst("img.lazy, img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }

            newMovieSearchResponse(recTitle, recLink, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }.distinctBy { t -> t.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    data class PlayUrlResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("videoUrl") val videoUrl: String? = null
    )

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // 1. Direct HTML se video source check karein
        val directVideoUrl = document.selectFirst("meta[itemprop=contentUrl]")?.attr("content")
            ?: document.selectFirst("video source, source[type=video/mp4]")?.attr("src")

        if (!directVideoUrl.isNullOrBlank()) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "HD Stream",
                    url = directVideoUrl,
                    referer = mainUrl,
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        // 2. Agar URL me video ID maujood hai toh API call karein
        val videoId = data.trimEnd('/').split("/").lastOrNull()
        if (!videoId.isNullOrBlank()) {
            try {
                val apiUrl = "https://api.desimaal.live/api/videos/$videoId/play-url"
                val jsonResponse = app.get(apiUrl, headers = mapOf("Referer" to mainUrl)).parsedSafe<PlayUrlResponse>()
                val fetchedUrl = jsonResponse?.url ?: jsonResponse?.file ?: jsonResponse?.videoUrl

                if (!fetchedUrl.isNullOrBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "API Stream",
                            url = fetchedUrl,
                            referer = mainUrl,
                            quality = Qualities.P1080.value,
                            type = if (fetchedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return false
    }
}
