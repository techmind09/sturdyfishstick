package com.sturdyfishstick

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * Blu Movies (Android app, AF 6.2) - rebuilt from the decompiled APK.
 * Updated with A-Z Director/Studio Categories.
 */
class BluMoviesProvider : MainAPI() {
    override var mainUrl = "https://xxxparodyhd.net"
    override var name = "Pvzija"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cf = CloudflareKiller()
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "page/%d/" to "New Movies",
        "page/%d/?filter=most-viewed" to "Popular Movies",
        
        // --- Studios / Directors (Sorted A to Z) ---
        "director/21-sextury-video-pulse/page/%d/" to "21 Sextury Video Pulse",
        "director/adult-source-media/page/%d/" to "Adult Source Media",
        "director/bang/page/%d/" to "Bang",
        "director/blacked/page/%d/" to "Blacked",
        "director/brazzers/page/%d/" to "Brazzers",
        "director/cutie-pie/page/%d/" to "Cutie Pie",
        "director/ddf-network/page/%d/" to "DDF Network",
        "director/devils-film/page/%d/" to "Devils Film",
        "director/digital-playground/page/%d/" to "Digital Playground",
        "director/digital-sin/page/%d/" to "Digital Sin",
        "director/doghouse-digital/page/%d/" to "Doghouse Digital",
        "director/dorcel-english/page/%d/" to "Dorcel English",
        "director/elegant-angel/page/%d/" to "Elegant Angel",
        "director/erotic-planet/page/%d/" to "Erotic Planet",
        "director/fantasy-massage/page/%d/" to "Fantasy Massage",
        "director/filthy-kings-clips/page/%d/" to "Filthy Kings Clips",
        "director/house-of-fyre/page/%d/" to "House Of Fyre",
        "director/indian-x/page/%d/" to "Indian X",
        "director/jules-jordan-video/page/%d/" to "Jules Jordan Video",
        "director/mario-salieri-productions/page/%d/" to "Mario Salieri Productions",
        "director/mariskax-productions/page/%d/" to "Mariskax Productions",
        "director/mile-high-xtreme/page/%d/" to "Mile High Xtreme",
        "director/my-pervy-family/page/%d/" to "My Pervy Family",
        "director/naughty-america/page/%d/" to "Naughty America",
        "director/new-sensations/page/%d/" to "New Sensations",
        "director/penthouse/page/%d/" to "Penthouse",
        "director/pornfidelity/page/%d/" to "Pornfidelity",
        "director/private/page/%d/" to "Private",
        "director/pure-taboo/page/%d/" to "Pure Taboo",
        "director/reality-junkies/page/%d/" to "Reality Junkies",
        "director/reality-kings/page/%d/" to "Reality Kings",
        "director/sexmex/page/%d/" to "Sexmex",
        "director/sinful-xxx/page/%d/" to "Sinful XXX",
        "director/sinner-xxx/page/%d/" to "Sinner XXX",
        "director/sweet-sinner/page/%d/" to "Sweet Sinner",
        "director/taboo-heat/page/%d/" to "Taboo Heat",
        "director/team-skeet/page/%d/" to "Team Skeet",
        "director/wicked-pictures/page/%d/" to "Wicked Pictures",
        "director/will-tile-xxx/page/%d/" to "Will Tile XXX",
        "director/xprime/page/%d/" to "Xprime"
    )

    private suspend fun doc(url: String) =
        app.get(url, referer = "$mainUrl/", headers = mapOf("User-Agent" to ua), interceptor = cf).document

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data.format(page)}"
        val items = doc(url)
            .select("article.card").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val out = LinkedHashMap<String, SearchResponse>()
        for (p in 1..3) {
            val url = if (p == 1) "$mainUrl/?s=$q" else "$mainUrl/page/$p/?s=$q"
            val found = runCatching { doc(url).select("article.card").mapNotNull { it.toSearchResult() } }.getOrNull().orEmpty()
            val fresh = found.filter { out[it.url] == null }
            if (fresh.isEmpty()) break
            fresh.forEach { out[it.url] = it }
        }
        return out.values.toList()
    }

    private fun Element.img(): String? {
        val i = selectFirst("a img") ?: selectFirst("img") ?: return null
        val src = listOf("data-lazy-src", "data-src", "src").map { i.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        return fixUrlNull(src)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val title = selectFirst("div.card__b h2")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("a img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null
        val poster = img()
        return newMovieSearchResponse(title, href, TvType.NSFW) { posterUrl = poster }
    }

    private fun Element.chips(label: String) =
        select("div:contains($label)>div.row__v>div.chips>a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

    override suspend fun load(url: String): LoadResponse {
        val d = doc(url)
        val title = d.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: d.selectFirst("h1")?.text()?.trim() ?: d.title()
        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = d.selectFirst("div.desc>p")?.text()
            ?.replace("Premium PPM allows us to bring you the best Adult content from the hottest studios.", "")
            ?.substringBefore("for every second watched.")?.trim()?.ifBlank { null }
        val body = d.body()
        val year = body.chips("Release Year").firstOrNull()?.toIntOrNull()
        val tags = body.chips("Genres") + body.chips("Studio")
        val actors = body.chips("Pornstars").map { ActorData(Actor(it)) }
        val recs = d.select("article.card").mapNotNull { it.toSearchResult() }.filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.actors = actors
            recommendations = recs
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Server(val u: String? = null, val l: String? = null)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = doc(data).selectFirst("section.hlm[data-servers]")?.attr("data-servers") ?: return false
        val servers = runCatching { parseJson<List<Server>>(raw) }.getOrNull().orEmpty()
            .mapNotNull { s -> s.u?.replace("\\/", "/")?.trim()?.takeIf { it.startsWith("http") }?.let { it to s.l } }
            .distinctBy { it.first }

        var found = false
        for ((url, label) in servers) {
            val ok = runCatching { loadExtractor(url, "$mainUrl/", subtitleCallback, callback) }.getOrDefault(false)
            if (ok) { found = true; continue }
            // hosts Cloudstream has no extractor for: let a WebView play the embed and catch the stream
            val media = runCatching {
                app.get(
                    url, referer = "$mainUrl/",
                    interceptor = WebViewResolver(Regex("""\.m3u8|\.mp4(\?|$)|/stream/""")),
                    timeout = 30_000L
                ).url
            }.getOrNull()
            if (media != null && media != url && (media.contains(".m3u8") || media.contains(".mp4") || media.contains("/stream/"))) {
                val host = label?.ifBlank { null } ?: url.substringAfter("://").substringBefore("/")
                callback(
                    newExtractorLink(name, "$name - $host", media,
                        if (media.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/") + "/"
                        quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}
