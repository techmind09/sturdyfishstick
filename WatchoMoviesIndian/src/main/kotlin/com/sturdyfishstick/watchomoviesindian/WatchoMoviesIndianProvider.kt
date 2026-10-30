package com.sturdyfishstick.watchomoviesindian

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class WatchOMoviesIndianProvider : MainAPI() {
    override var mainUrl              = "https://watchomovies.study"
    override var name                 = "WatchOMovies Indian"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "movies" to "Movies",
        "series" to "Web Series",
        "tag/ullu-originals" to "Ullu Originals",
        "tag/fliz-movies" to "Fliz Movies",
        "tag/nuefliks" to "Nuefliks",
        "tag/hotshots" to "Hotshots",
        "tag/hunters" to "Hunters",
        "tag/moodx" to "MoodX",
        "tag/voovi" to "VooVi",
        "tag/primeplay" to "PrimePlay",
        "tag/besharams" to "Besharams",
        "tag/kooku" to "Kooku",
        "tag/gupchup" to "Gupchup",
        "tag/feneomovies" to "Feneomovies",
        "tag/cinemadosti" to "Cinemadosti",
        "tag/primeflix" to "Primeflix",
        "tag/gemplex" to "Gemplex",
        "tag/rabbit" to "Rabbit",
        "tag/hotmasti" to "HotMasti",
        "tag/mastiprime" to "MastiPrime",
        "tag/ek-night-show" to "Ek Night Show",
        "tag/flixskmovies" to "Flixskmovies",
        "tag/lootlo" to "Lootlo",
        "tag/hootzy" to "Hootzy",
        "tag/balloons" to "Balloons",
        "tag/big-movie-zoo" to "Big Movie Zoo",
        "tag/bambooflix" to "Bambooflix",
        "tag/piliflix" to "Piliflix",
        "tag/11upmovies" to "11upmovies",
        "tag/eightshots" to "Eightshots",
        "tag/i-entertainment" to "I-Entertainment",
        "tag/hotprime" to "Hotprime",
        "tag/bananaprime" to "BananaPrime",
        "tag/chikooflix" to "Chikooflix",
        "tag/glamheart" to "Glamheart",
        "tag/worldprime" to "Worldprime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.isBlank() && page <= 1 -> "$mainUrl/"
            request.data.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/${request.data}/"
            else -> "$mainUrl/${request.data}/page/$page/"
        }
        
        val document = app.get(url).document
        val home = document.select("article, div.item, div.post, div.flix-item, .result-item").mapNotNull { it.toSearchResult() }
        
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
            ?: linkElem.attr("title")
            ?: linkElem.text()
        if (titleText.isNullOrBlank()) return null
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
        val document = app.get(url).document
        return document.select("article, div.item, div.post, .result-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url).document
        val results = document.select("article, div.item, div.post, .result-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "No Title"
            
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".poster img, .dt_poster img")?.attr("src")
        )
        
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst(".wp-content p, .entry-content p")?.text()?.trim()

        val tags = document.select(".tags a, .genres a, a[href*='/tag/'], a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("article, div.item, .related-posts article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val isSeries = url.contains("series") || document.select(".episodios, .seasons").isNotEmpty()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>() // Fixed mutableListof -> mutableListOf
            document.select(".episodios li, .les-content a").forEachIndexed { index, ep ->
                val epLink = fixUrlNull(ep.attr("href")) ?: url
                val epTitle = ep.select(".episodiotitle, .servername").text().ifBlank { "Episode ${index + 1}" }
                
                // Fixed Episode constructor to newEpisode
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
        val document = app.get(data).document
        
        document.select("iframe, source, video").forEach { element ->
            val src = element.attr("src").ifBlank { element.attr("data-src") }
            if (src.isNotBlank() && !src.contains("ads")) {
                val fixedUrl = fixUrl(src)
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
            }
        }

        document.select("a.download, .links_table a, .button.flix, .dooplay_player_option").forEach { element ->
            val href = element.attr("href").ifBlank { element.attr("data-url") }
            if (href.isNotBlank() && !href.startsWith("javascript") && !href.contains("#")) {
                loadExtractor(fixUrl(href), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
