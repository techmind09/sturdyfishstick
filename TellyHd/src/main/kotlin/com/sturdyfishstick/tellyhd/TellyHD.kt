package com.sturdyfishstick.tellyhd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.FileMoon
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

class TellyHD : MainAPI() {

    override var mainUrl = "https://tellyhd.eu"
    override var name = "TellyHD Indian"
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
        "User-Agent" to headers["User-Agent"]!!,
        "Accept" to "*/*"
    )

    override val mainPage = mainPageOf(
        "release/${Calendar.getInstance().get(Calendar.YEAR)}" to "Latest",
        "genre/18plus" to "18+",
        "genre/9redmovie" to "9redmovie",
        "genre/addatv" to "Addatv",
        "genre/aahaflix" to "AahaFlix",
        "genre/altbalaji" to "Alt Balaji",
        "genre/atrangii" to "Atrangii",
        "genre/bindastimes" to "Bindastimes",
        "genre/bongonaari" to "BongoNaari", 
        "genre/boomex" to "BoomEX", 
        "genre/bulbulplay" to "Bulbulplay", 
        "genre/bullap" to "bullap", 
        "genre/cineon" to "CineOn", 
        "genre/crf-studioz" to "CRF Studioz",
        "genre/cineprime" to "CinePrime",
        "genre/digimovieplex" to "Digimovieplex", 
        "genre/dugru" to "Dugru", 
        "genre/dyzreplay" to "DyzrePlay", 
        "genre/eortv" to "Eortv", 
        "genre/feelapp" to "Feelapp", 
        "genre/feneo" to "Feneo", 
        "genre/feniapo" to "Feniapp", 
        "genre/foxxprime" to "foxxprime",
        "genre/fugi" to "Fugi", 
        "genre/fukrey" to "Fukrey", 
        "genre/fun2sh" to "fun2sh", 
        "genre/funtyy" to "funtyy", 
        "genre/goddesmahi" to "GoddesMahi",
        "genre/goodflixmovies" to "goodflixmovies", 
        "genre/gupchup" to "GupChup", 
        "genre/habbitmovies" to "HabbitMovies", 
        "genre/hindi-dubbed" to "Hindi Dubbed", 
        "genre/hindi" to "Hindi",
        "genre/hitprime" to "Hitprime", 
        "genre/hotfm" to "HotFM", 
        "genre/hotshot" to "HotShot", 
        "genre/hotx" to "Hotx", 
        "genre/hulchul" to "HulChul", 
        "genre/hunters" to "Hunters", 
        "genre/ibamovies" to "IBAMovies", 
        "genre/indian" to "INDIAN", 
        "genre/jalva" to "Jalva", 
        "genre/jugnu" to "Jugnu", 
        "genre/jugnutv" to "JugnuTV", 
        "genre/lookentertainment" to "Lookentertainment", 
        "genre/makhan" to "Makhan",
        "genre/meetx" to "Meetx",
        "genre/moodx" to "MoodX",
        "genre/neonx" to "NeonX",
        "genre/niksindian" to "Niksindian", 
        "genre/primeplay" to "PrimePlay",
        "genre/rabbit" to "Rabbit",
        "genre/rasminair" to "RasmiNair"
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
            else -> runCatching { URL(URL(baseUrl), value).toString() }.getOrDefault(value)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url, headers = headers).document

        val home = document.select("div.items.normal article, div#archive-content article, div.items.full article").mapNotNull {
            it.toSearchResult()
        }

        val hasNext = document.selectFirst("a.next, a[rel=next], .pagination a.next, a.page-numbers.next") != null

        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h3 > a") ?: return null
        val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return null
        val rawUrl = fixUrlNull(anchor.attr("href")) ?: return null
        val href = getProperLink(rawUrl)

        val image = select("div.poster img").lastOrNull()
        var posterUrl = image?.getImageAttr()

        if (posterUrl?.contains(".gif", true) == true) {
            posterUrl = image?.attr("abs:data-wpfc-original-src")?.takeIf { it.isNotBlank() } ?: posterUrl
        }
        posterUrl = fixUrlNull(posterUrl)

        val quality = getQualityFromString(select("span.quality").text())

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    private suspend fun getSearchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$mainUrl/search/$encodedQuery/" else "$mainUrl/search/$encodedQuery/page/$page/"

        val document = app.get(url, headers = headers).document

        val results = document.select("div.result-item").mapNotNull { element ->
            val anchor = element.selectFirst("div.title > a") ?: return@mapNotNull null
            val title = anchor.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            if (title.isBlank()) return@mapNotNull null

            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val posterUrl = element.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

            newMovieSearchResponse(title, getProperLink(href), TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }

        val hasNext = document.selectFirst("a.next, a[rel=next], .pagination a.next, a.page-numbers.next") != null
        return results to hasNext
    }

    override suspend fun search(query: String): List<SearchResponse> = getSearchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = getSearchPage(query, page)
        return newSearchResponseList(results, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("div.data > h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null

        var posterUrl = document.selectFirst("div.poster img")?.getImageAttr()?.let { fixUrlNull(it) }
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        }

        val description = document.selectFirst("div[itemprop=description] p, div.wp-content p")?.text()?.trim()

        val year = document.selectFirst("span.date")?.text()?.substringAfterLast(", ")?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = document.select("div.sgeneros a").map { it.text() }.filter { it.isNotBlank() }

        val recommendations = document.select("div#single_relacionados article").mapNotNull { element ->
            val anchor = element.selectFirst("a") ?: return@mapNotNull null
            val recTitle = element.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() } ?: anchor.text().trim()
            if (recTitle.isBlank()) return@mapNotNull null

            val recUrl = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val recPoster = element.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

            newMovieSearchResponse(recTitle, getProperLink(recUrl), TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        val episodes = document.select(
            "ul#playeroptionsul > li, div.play-options li, .sources-box li, .dooplay_player_option, .options li"
        ).mapNotNull { element ->
            val name = element.selectFirst("span.title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: element.select("span").text().trim().ifBlank { element.text().trim() }

            val type = element.attr("data-type").ifBlank { element.attr("data-film") }.ifBlank { "movie" }
            val post = element.attr("data-post").ifBlank { element.attr("data-id") }.ifBlank { element.attr("data-movie") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val nume = element.attr("data-nume").ifBlank { element.attr("data-episode") }.ifBlank { element.attr("data-seq") }.ifBlank { "1" }

            if (name.contains("trailer", true)) return@mapNotNull null

            val json = LinkData(type = type, post = post, nume = nume).toJson()

            newEpisode(json) {
                this.name = name.ifBlank { "Server" }
            }
        }.distinctBy { it.name }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        var isSuccessful = false

        try {
            val loadData = tryParseJson<LinkData>(data) ?: return false
            val post = loadData.post?.takeIf { it.isNotBlank() } ?: return false
            val type = loadData.type?.takeIf { it.isNotBlank() } ?: "movie"
            val nume = loadData.nume?.takeIf { it.isNotBlank() } ?: "1"

            val apiUrls = listOf(
                "$mainUrl/wp-json/dooplayer/v2/$post/$type/$nume",
                "$mainUrl/wp-json/dooplayer/v1/$post/$type/$nume",
                "$mainUrl/wp-admin/admin-ajax.php?action=doo_player_ajax&post=$post&type=$type&nume=$nume"
            )

            var iframe: String? = null

            for (apiUrl in apiUrls) {
                val response = withContext(Dispatchers.IO) {
                    try {
                        app.get(
                            url = apiUrl,
                            referer = mainUrl,
                            headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
                        ).parsedSafe<ResponseHash>()
                    } catch (e: Exception) {
                        null
                    }
                }

                iframe = response?.embedUrl?.getIframe()
                if (!iframe.isNullOrBlank()) break
            }

            if (iframe.isNullOrBlank()) return false

            if (iframe.contains("telly", ignoreCase = true) || iframe.contains("telly.xin", ignoreCase = true) || iframe.contains("cdntelly", ignoreCase = true)) {
                val playerDoc = withContext(Dispatchers.IO) {
                    try {
                        app.get(url = iframe, referer = mainUrl, headers = headers).document
                    } catch (e: Exception) {
                        null
                    }
                } ?: return false

                val rawStreamUrl = playerDoc.selectFirst("script#player-loader")?.attr("data-stream-url")?.takeIf { it.isNotBlank() } 
                    ?: playerDoc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }

                if (!rawStreamUrl.isNullOrBlank()) {
                    val streamUrl = resolveUrl(iframe, rawStreamUrl)
                    if (streamUrl.startsWith("http", true)) {
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
                        isSuccessful = true
                    }
                }
            } 
            
            if (!isSuccessful && !iframe.contains("youtube", ignoreCase = true)) {
                try {
                    isSuccessful = loadExtractor(
                        iframe,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return isSuccessful
    }

    private fun Element.getImageAttr(): String {
        val candidates = listOf("abs:data-wpfc-original-src", "abs:data-src", "abs:data-lazy-src", "abs:src")
        for (attribute in candidates) {
            val value = attr(attribute).trim()
            if (value.isNotBlank() && !value.startsWith("data:image", true)) {
                return value
            }
        }
        return attr("abs:srcset").substringBefore(",").substringBefore(" ").trim()
    }

    private fun String.getIframe(): String? {
        val raw = trim()
        if (raw.isBlank()) return null

        val iframeSrc = Jsoup.parse(raw).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
        val candidate = iframeSrc ?: raw.takeIf { it.startsWith("http", true) || it.startsWith("//") || it.startsWith("/") } ?: return null

        return resolveUrl(mainUrl, candidate).takeIf { it.startsWith("http", true) }
    }

    data class LinkData(
        val type: String? = null,
        val post: String? = null,
        val nume: String? = null
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embedUrl: String? = null,
        val type: String? = null
    )
}

// Support Extractors Classes
class Vidsp : VidhideExtractor() {
    override var mainUrl = "https://vidsp.lol"
}

class VidHideplus : VidhideExtractor() {
    override var mainUrl = "https://vidhideplus.com"
}

class VidHidedht : VidhideExtractor() {
    override var mainUrl = "https://dhtpre.com"
}

class Vidhidehub : VidhideExtractor() {
    override var mainUrl = "https://vidhidehub.com"
}

class Vidhidetoul : VidhideExtractor() {
    override var mainUrl = "https://toul.hair"
}

class Xtapes : VidhideExtractor() {
    override var mainUrl = "https://xtapes.porn"
    override var name = "Xtapes"
}

class Luluvid : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://luluvid.com"
}

class Filemoon : FileMoon() {
    override var mainUrl = "https://filemoon.sx"
    override var name = "Filemoon"
}

open class Ds2play : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://ds2play.com"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String {
        return "https://dood.wf/d/$id"
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response0 = app.get(url).text
        val md5 = mainUrl + (Regex("/pass_md5/[^']*").find(response0)?.value ?: return null)
        val trueUrl = app.get(md5, referer = url).text + "zUEJeL3mUN?token=" + md5.substringAfterLast("/")
        val quality = Regex("\\d{3,4}p").find(response0.substringAfter("<title>").substringBefore("</title>"))?.groupValues?.get(0)
        return listOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = trueUrl
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(quality)
            }
        )
    }
}

open class Bigwarp : ExtractorApi() {
    override val name = "Bigwarp"
    override val mainUrl = "https://bigwarp.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
    ): List<ExtractorLink>? {
        val response = app.get(url).document
        val script = response.selectFirst("script:containsData(sources)")?.data().toString()
        Regex("sources:\\s*\\[\\s*\\{\\s*file:\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)?.let { link ->
            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    INFER_TYPE
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.P1080.value
                }
            )
        }
        return null
    }
}
