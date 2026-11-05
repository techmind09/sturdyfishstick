package com.sturdyfishstick.tellyhd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

class TellyHDEn : MainAPI() {

    override var mainUrl = "https://tellyhd.eu"
    override var name = "TellyHD Other"
    override val hasMainPage = true
    override var lang = "hi"

    override val supportedTypes = setOf(
        TvType.NSFW,
        TvType.TvSeries,
        TvType.Movie
    )

    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    private val playerHeaders = mapOf(
        "User-Agent" to headers["User-Agent"].orEmpty(),
        "Accept" to "*/*"
    )

    override val mainPage = mainPageOf(
        "release/${Calendar.getInstance().get(Calendar.YEAR)}" to "Latest",
        "genre/adventure" to "Adventure",
        "genre/attackers" to "Attackers",
        "genre/brazzers" to "Brazzers",
        "genre/chinese" to "Chinese",
        "genre/altbalaji" to "Alt Balaji",
        "genre/deeper" to "Deeper",
        "genre/digital-playground" to "Digital Playground",
        "genre/documentary" to "Documentary",
        "genre/eng-subtitle" to "Eng Subtitle",
        "genre/english" to "English",
        "genre/france" to "France",
        "genre/hong-kong" to "Hong Kong",
        "genre/japan" to "Japan",
        "genre/jav" to "JAV",
        "genre/korean" to "Korean",
        "genre/marc-dorcel" to "Marc Dorcel",
        "genre/onlyfans" to "OnlyFans",
        "genre/philippine" to "Philippine",
        "genre/private" to "Private",
        "genre/science-fiction" to "Science Fiction",
        "genre/sexart" to "SexArt",
        "genre/sexmex" to "Sexmex",
        "genre/sunny-leone" to "Sunny Leone",
        "genre/tbonx" to "TbonX",
        "genre/usa" to "USA",
        "genre/vintage" to "Vintage",
        "genre/vivamax" to "VivaMax",
        "genre/vixen" to "Vixen",
        "genre/wicked" to "Wicked"
    )

    private fun getProperLink(uri: String): String {
        val path = uri.substringBefore("?")
        return when {
            path.contains("/episodes/") -> {
                val slug = path.substringAfter("/episodes/").substringBefore("/")
                val showSlug = slug.substringBefore("-season").ifBlank { slug }
                "$mainUrl/tvshows/$showSlug/"
            }
            path.contains("/seasons/") -> {
                val slug = path.substringAfter("/seasons/").substringBefore("/")
                val showSlug = slug.substringBefore("-season").ifBlank { slug }
                "$mainUrl/tvshows/$showSlug/"
            }
            else -> uri
        }
    }

    private fun resolveUrl(baseUrl: String, url: String): String {
        val value = url.trim()
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            else -> runCatching {
                URL(URL(baseUrl), value).toString()
            }.getOrDefault(value)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}/"
        } else {
            "$mainUrl/${request.data}/page/$page/"
        }

        val document = app.get(
            url = url,
            headers = headers
        ).document

        val home = document.select(
            "div.items.normal article, " +
                "div#archive-content article, " +
                "div.items.full article"
        ).mapNotNull {
            it.toSearchResult()
        }

        val hasNext = document.selectFirst(
            "a.next, a[rel=next], " +
                ".pagination a.next, " +
                "a.page-numbers.next"
        ) != null

        return newHomePageResponse(
            request.name,
            home,
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h3 > a") ?: return null
        val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return null
        val rawUrl = fixUrlNull(anchor.attr("href")) ?: return null
        val href = getProperLink(rawUrl)

        val image = select("div.poster img").lastOrNull()
        var posterUrl = image?.getImageAttr()

        if (posterUrl?.contains(".gif", true) == true) {
            posterUrl = image
                ?.attr("abs:data-wpfc-original-src")
                ?.takeIf { it.isNotBlank() }
                ?: posterUrl
        }

        posterUrl = fixUrlNull(posterUrl)

        val quality = getQualityFromString(
            select("span.quality").text()
        )

        return newMovieSearchResponse(
            title,
            href,
            TvType.NSFW
        ) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    private suspend fun getSearchPage(
        query: String,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {
        val encodedQuery = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        ).replace("+", "%20")

        val url = if (page <= 1) {
            "$mainUrl/search/$encodedQuery/"
        } else {
            "$mainUrl/search/$encodedQuery/page/$page/"
        }

        val document = app.get(
            url = url,
            headers = headers
        ).document

        val results = document.select("div.result-item")
            .mapNotNull { element ->
                val anchor = element.selectFirst("div.title > a")
                    ?: return@mapNotNull null

                val title = anchor.text()
                    .replace(Regex("\\(\\d{4}\\)"), "")
                    .trim()

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val href = fixUrlNull(anchor.attr("href"))
                    ?: return@mapNotNull null

                val posterUrl = element
                    .selectFirst("img")
                    ?.getImageAttr()
                    ?.let { fixUrlNull(it) }

                newMovieSearchResponse(
                    title,
                    getProperLink(href),
                    TvType.NSFW
                ) {
                    this.posterUrl = posterUrl
                }
            }

        val hasNext = document.selectFirst(
            "a.next, a[rel=next], " +
                ".pagination a.next, " +
                "a.page-numbers.next"
        ) != null

        return results to hasNext
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return getSearchPage(query, 1).first
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val (results, hasNext) = getSearchPage(query, page)
        return newSearchResponseList(
            results,
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url = url,
            headers = headers
        ).document

        val title = document
            .selectFirst("div.data > h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        var posterUrl = document
            .selectFirst("div.poster img")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }

        if (posterUrl.isNullOrEmpty()) {
            posterUrl = fixUrlNull(
                document.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content")
            )
        }

        val description = document.selectFirst(
            "div[itemprop=description] p, div.wp-content p"
        )?.text()?.trim()

        val year = document.selectFirst("span.date")
            ?.text()
            ?.substringAfterLast(", ")
            ?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)")
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val tags = document.select("div.sgeneros a")
            .map { it.text() }
            .filter { it.isNotBlank() }

        val recommendations = document
            .select("div#single_relacionados article")
            .mapNotNull { element ->
                val anchor = element.selectFirst("a")
                    ?: return@mapNotNull null

                val recTitle = element.selectFirst("img")
                    ?.attr("alt")
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.text().trim()

                if (recTitle.isBlank()) {
                    return@mapNotNull null
                }

                val recUrl = fixUrlNull(anchor.attr("href"))
                    ?: return@mapNotNull null

                val recPoster = element
                    .selectFirst("img")
                    ?.getImageAttr()
                    ?.let { fixUrlNull(it) }

                newMovieSearchResponse(
                    recTitle,
                    getProperLink(recUrl),
                    TvType.NSFW
                ) {
                    this.posterUrl = recPoster
                }
            }

        val episodes = document.select(
            "ul#playeroptionsul > li, " +
                "div.play-options li, " +
                ".sources-box li, " +
                ".dooplay_player_option, " +
                ".options li"
        ).mapNotNull { element ->
            val name = element.selectFirst("span.title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: element.select("span").text().trim()
                    .ifBlank { element.text().trim() }

            val type = element.attr("data-type")
                .ifBlank { element.attr("data-film") }
                .ifBlank { "movie" }

            val post = element.attr("data-post")
                .ifBlank { element.attr("data-id") }
                .ifBlank { element.attr("data-movie") }
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val nume = element.attr("data-nume")
                .ifBlank { element.attr("data-episode") }
                .ifBlank { element.attr("data-seq") }
                .ifBlank { "1" }

            if (name.contains("trailer", true)) {
                return@mapNotNull null
            }

            val json = LinkData(
                type = type,
                post = post,
                nume = nume
            ).toJson()

            newEpisode(json) {
                this.name = name.ifBlank { "Server" }
            }
        }.distinctBy { it.name }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LinkData>(data)
            ?: return false

        val post = loadData.post
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val type = loadData.type
            ?.takeIf { it.isNotBlank() }
            ?: "movie"

        val nume = loadData.nume
            ?.takeIf { it.isNotBlank() }
            ?: "1"

        val apiUrl = "$mainUrl/wp-json/dooplayer/v2/$post/$type/$nume"

        val response = app.get(
            url = apiUrl,
            referer = mainUrl,
            headers = headers + mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<ResponseHash>()

        val iframe = response
            ?.embedUrl
            ?.getIframe()
            ?: return false

        if (iframe.contains("telly", ignoreCase = true)) {
            val playerDoc = app.get(
                url = iframe,
                referer = mainUrl,
                headers = headers
            ).document

            val scriptTag = playerDoc.selectFirst(
                "script#player-loader"
            )

            val rawStreamUrl = scriptTag
                ?.attr("data-stream-url")
                ?.takeIf { it.isNotBlank() }
                ?: return false

            val streamUrl = resolveUrl(
                iframe,
                rawStreamUrl
            )

            if (!streamUrl.startsWith("http", true)) {
                return false
            }

            val link = newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = iframe
                this.quality = Qualities.Unknown.value
                this.headers = playerHeaders
            }

            callback.invoke(link)
            return true
        }

        if (
            iframe.isNotBlank() &&
            !iframe.contains("youtube", ignoreCase = true)
        ) {
            // Cloudstream will automatically resolve Ultra Stream, Lulustream, Doodstream, Filemoon, Earnvids, Bigwarp, Vidhide, etc. via built-in extractors.
            return loadExtractor(
                iframe,
                mainUrl,
                subtitleCallback,
                callback
            )
        }

        return false
    }

    private fun Element.getImageAttr(): String {
        val candidates = listOf(
            "abs:data-wpfc-original-src",
            "abs:data-src",
            "abs:data-lazy-src",
            "abs:src"
        )

        for (attribute in candidates) {
            val value = attr(attribute).trim()

            if (
                value.isNotBlank() &&
                !value.startsWith("data:image", true)
            ) {
                return value
            }
        }

        val srcset = attr("abs:srcset")
            .substringBefore(",")
            .substringBefore(" ")
            .trim()

        return srcset
    }

    private fun String.getIframe(): String? {
        val raw = trim()

        if (raw.isBlank()) return null

        val iframeSrc = Jsoup.parse(raw)
            .selectFirst("iframe")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }

        val candidate = iframeSrc
            ?: raw.takeIf {
                it.startsWith("http", true) ||
                    it.startsWith("//") ||
                    it.startsWith("/")
            }
            ?: return null

        return resolveUrl(mainUrl, candidate)
            .takeIf { it.startsWith("http", true) }
    }

    data class LinkData(
        val type: String? = null,
        val post: String? = null,
        val nume: String? = null
    )

    data class ResponseHash(
        @JsonProperty("embed_url")
        val embedUrl: String? = null,
        val type: String? = null
    )
}
