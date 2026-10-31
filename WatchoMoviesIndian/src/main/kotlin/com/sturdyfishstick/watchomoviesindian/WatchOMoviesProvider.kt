package com.sturdyfishstick.watchomoviesindian

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class WatchOMoviesIndianProvider : MainAPI() {
    override var mainUrl              = "https://watchomovies.study"
    override var name                 = "WatchOMoviesIndian"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "movies" to "Movies",
        "web-series" to "Web Series",
        "director/ullu-originals" to "Ullu Originals",
        "director/fliz-movies" to "Fliz Movies",
        "director/nuefliks" to "Nuefliks",
        "director/hotshots" to "Hotshots",
        "director/hunters" to "Hunters",
        "director/moodx-originals" to "MoodX",
        "director/voovi" to "VooVi",
        "director/primeplay" to "PrimePlay",
        "director/besharams" to "Besharams",
        "director/kooku" to "Kooku",
        "director/gupchup" to "Gupchup",
        "director/feneomovies" to "Feneomovies",
        "director/cinemadosti" to "Cinemadosti",
        "director/primeflix" to "Primeflix",
        "director/gemplex" to "Gemplex",
        "director/rabbit" to "Rabbit",
        "director/hotmasti" to "HotMasti",
        "director/mastiprime" to "MastiPrime",
        "director/ek-night-show" to "Ek Night Show",
        "director/flixskmovies" to "Flixskmovies",
        "director/lootlo" to "Lootlo",
        "director/hootzy" to "Hootzy",
        "director/balloons" to "Balloons",
        "director/big-movie-zoo" to "Big Movie Zoo",
        "director/bambooflix" to "Bambooflix",
        "director/piliflix" to "Piliflix",
        "director/11upmovies" to "11upmovies",
        "director/eightshots" to "Eightshots",
        "director/i-entertainment" to "I-Entertainment",
        "director/hotprime" to "Hotprime",
        "director/bananaprime" to "BananaPrime",
        "director/chikooflix" to "Chikooflix",
        "director/glamheart" to "Glamheart",
        "director/worldprime" to "Worldprime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.isBlank() && page <= 1 -> "$mainUrl/"
            request.data.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/${request.data}/"
            else -> "$mainUrl/${request.data}/page/$page/"
        }
        
        val document = app.get(url, headers = headers).document
        val home = document.select("div.item, article, div.post, div.flix-item, .result-item, .animation-2").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home.distinctBy { it.url },
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val titleText = this.selectFirst(".title, h2, h3, h4, .name")?.text()
            ?: linkElem.attr("title").takeIf { it.isNotBlank() }
            ?: linkElem.text()
            
        if (titleText.isBlank()) return null
        val title = fixTitle(titleText).trim()

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        val isSeries = href.contains("series") || title.contains("Season", true) || title.contains("Episode", true)
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val document = app.get(url, headers = headers).document
        return document.select("div.item, article, div.post, .result-item, .animation-2").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url, headers = headers).document
        val results = document.select("div.item, article, div.post, .result-item, .animation-2").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "No Title"
            
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".poster img, .dt_poster img, .entry-content img")?.attr("src")
        )
        
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst(".wp-content p, .entry-content p, .sbox p")?.text()?.trim()

        val tags = document.select(".sgeneros a, .tags a, .genres a, a[href*='/tag/'], a[href*='/genre/'], a[href*='/director/'], .elementor-tag a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.item, article, .related-posts article, .animation-2")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val isSeries = url.contains("series") || document.select(".episodios, .seasons, .les-content").isNotEmpty()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select(".episodios li, .les-content a, .seasons-list li a").forEachIndexed { index, ep ->
                val epLink = fixUrlNull(ep.attr("href")) ?: url
                val epTitle = ep.select(".episodiotitle, .servername").text().ifBlank { ep.text() }.ifBlank { "Episode ${index + 1}" }
                
                episodes.add(
                    newEpisode(epLink) {
                        this.name = epTitle
                        this.episode = index + 1
                    }
                )
            }

            val defaultEpisode = listOf(
                newEpisode(url) {
                    this.name = "Full Movie / Video"
                }
            )

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, if (episodes.isNotEmpty()) episodes else defaultEpisode) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        
        document.select("iframe, source, video").forEach { element ->
            val src = element.attr("src").ifBlank { element.attr("data-src") }
            if (src.isNotBlank() && !src.contains("ads")) {
                val fixedUrl = fixUrl(src)
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
            }
        }

        document.select("a.download, .links_table a, .button.flix, .dooplay_player_option, .elemento-button").forEach { element ->
            val href = element.attr("href").ifBlank { element.attr("data-url") }
            if (href.isNotBlank() && !href.startsWith("javascript") && !href.contains("#")) {
                loadExtractor(fixUrl(href), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
