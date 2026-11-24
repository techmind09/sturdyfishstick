package com.sturdyfishstick.watchomoviesindian

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class WatchOMoviesProvider : MainAPI() {
    override var mainUrl              = "https://watchomovies.social"
    override var name                 = "WatchOMovies"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val userAgent = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val cfMutexMap = ConcurrentHashMap<String, Mutex>()
    private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()

    private fun mutexFor(url: String): Mutex =
        cfMutexMap.getOrPut(try { Uri.parse(url).host ?: mainUrl } catch (e: Exception) { mainUrl }) { Mutex() }

    private fun killerFor(url: String): CloudflareKiller =
        cfKillerMap.getOrPut(try { Uri.parse(url).host ?: mainUrl } catch (e: Exception) { mainUrl }) { CloudflareKiller() }

    private fun isCloudflarePage(response: NiceResponse): Boolean {
        return response.code in listOf(403, 503)
    }

    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "movies" to "Movies",
        "genre/brazzers" to "Brazzers",
        "genre/bangbros" to "Bangbros",
        "genre/naughty-america" to "Naughty America",
        "genre/reality-kings" to "Reality Kings",
        "genre/vixen" to "Vixen",
        "genre/wicked-pictures" to "Wicked Pictures",
        "genre/evil-angel" to "Evil Angel",
        "genre/teamskeet" to "Teamskeet",
        "genre/digital-playground" to "Digital Playground",
        "genre/twistys" to "Twistys",
        "genre/marc-dorcel" to "Marc Dorcel",
        "genre/jules-jordan-video" to "Jules Jordan Video",
        "genre/new-sensations" to "New Sensations",
        "genre/ddf-network" to "DDF Network",
        "genre/mofos" to "Mofos",
        "genre/elegant-angel" to "Elegant Angel",
        "genre/fakehub" to "FakeHub",
        "genre/faketaxi" to "FakeTaxi",
        "genre/girlsway" to "GirlsWay",
        "genre/mylf" to "MYLF",
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

    private suspend fun getPage(url: String, referer: String? = null): NiceResponse? {
        return try {
            val response = app.get(url, headers = browserHeaders, referer = referer)
            if (!isCloudflarePage(response)) {
                response
            } else {
                mutexFor(url).withLock {
                    val cfKiller = killerFor(url)
                    val retryResponse = app.get(url, headers = browserHeaders, interceptor = cfKiller, referer = referer)
                    if (isCloudflarePage(retryResponse)) {
                        cfKiller.savedCookies.clear()
                        app.get(url, headers = browserHeaders, interceptor = cfKiller, referer = referer)
                    } else {
                        retryResponse
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractItems(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        // Primary PsyPlay / Prmovies style cards (.ml-item)
        for (card in doc.select(".ml-item")) {
            val a = card.selectFirst("a.ml-mask") ?: card.selectFirst("a[href]") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue
            val rawTitle = (a.attr("oldtitle").takeIf { it.isNotBlank() }
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: card.selectFirst(".mli-info h2, h2, h3")?.text()
                ?: card.selectFirst("img")?.attr("alt"))
                ?.trim() ?: ""
            if (rawTitle.isBlank()) continue
            val title = fixTitle(rawTitle).trim()

            val thumb = card.selectFirst("img.mli-thumb") ?: card.selectFirst("img")
            val poster = fixUrlNull(
                thumb?.attr("data-original")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("src")?.takeIf { it.isNotBlank() }
            )

            val isSeries = url.contains("/series/", true) ||
                Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(title)

            val response = if (isSeries) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            items.add(response)
        }

        // Fallback if theme uses article / .item blocks
        if (items.isEmpty()) {
            for (card in doc.select("article, div.item, div.post, .result-item")) {
                val a = card.selectFirst("a[href]") ?: continue
                val url = fixUrlNull(a.attr("href")) ?: continue
                val rawTitle = (card.selectFirst(".title, h2, h3, h4, .name")?.text()
                    ?: a.attr("title").takeIf { it.isNotBlank() }
                    ?: a.text()).trim()
                if (rawTitle.isBlank()) continue
                val title = fixTitle(rawTitle).trim()

                val img = card.selectFirst("img")
                val poster = fixUrlNull(
                    img?.attr("data-original")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                )

                val isSeries = url.contains("/series/", true) ||
                    Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(title)

                items.add(
                    if (isSeries) {
                        newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
                    } else {
                        newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
                    }
                )
            }
        }

        return items.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = when {
            request.data.isBlank() && page <= 1 -> "$mainUrl/"
            request.data.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/${request.data}/"
            else -> "$mainUrl/${request.data}/page/$page/"
        }

        val html = getPage(pageUrl)?.text ?: return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        val items = extractItems(html)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val html = getPage("$mainUrl/?s=$encoded")?.text ?: return emptyList()
        return extractItems(html)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val html = getPage("$mainUrl/page/$page/?s=$encoded")?.text ?: return newSearchResponseList(emptyList(), false)
        val results = extractItems(html)
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load page")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h3[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?: doc.selectFirst("div.mvic-thumb")?.attr("style")?.let { style ->
                    Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
                }
                ?: doc.selectFirst(".poster img, .dt_poster img")?.attr("src")
        )

        val plot = doc.selectFirst("p.f-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst(".wp-content p, .entry-content p")?.text()?.trim()
            ?: ""

        val year = doc.select("a[href*=/release-year/]").firstOrNull()?.text()?.trim()
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.groupValues?.get(0)?.toIntOrNull()

        val tags = doc.select(".mvici-left a[href*=/genre/], .tags a, .genres a, a[href*='/tag/'], a[href*='/director/']")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .distinct()

        val duration = doc.selectFirst(".mvici-right span[itemprop=duration]")?.text()?.trim()
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val recommendations = extractItems(html).filter { it.url != url }

        if (url.contains("/series/", true) || doc.select("#seasons .tvseason, .les-content a").isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            for (tvseason in doc.select("#seasons .tvseason")) {
                val seasonTitle = tvseason.selectFirst(".les-title strong")?.text() ?: "Season 1"
                val season = Regex("""(?i)\bseason\s*(\d+)""").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                for (a in tvseason.select(".les-content a[href]")) {
                    val href = fixUrlNull(a.attr("href")) ?: continue
                    val label = a.text().trim()
                    val epNum = Regex("""(?i)\bepisode\s*[-:]?\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(
                        newEpisode(href) {
                            this.name = label.ifBlank { "Episode ${episodes.size + 1}" }
                            this.season = season
                            this.episode = epNum ?: (episodes.size + 1)
                        }
                    )
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.duration = duration
                    this.recommendations = recommendations
                }
            }
        }

        val isSeries = Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(title)
        if (isSeries) {
            val episodes = listOf(
                newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            )
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val pageUrl = data.substringBefore("#").substringBefore("?")
            val html = getPage(pageUrl)?.text ?: return false
            val doc = Jsoup.parse(html)

            found = if (pageUrl.contains("/episode/", true)) {
                resolveEpisodePage(doc, pageUrl, subtitleCallback, callback)
            } else {
                resolvePagePlayer(doc, pageUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }

    private suspend fun resolvePagePlayer(
        doc: Element,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val tabLabels = mutableMapOf<String, String>()
        for (li in doc.select(".player_nav .idTabs li")) {
            val anchor = li.selectFirst("a[href^=#]") ?: continue
            val tabId = anchor.attr("href").removePrefix("#")
            val label = li.selectFirst(".les-content")?.text()?.trim()
            if (tabId.isNotBlank() && !label.isNullOrBlank()) tabLabels[tabId] = label
        }

        for (iframe in doc.select("#player2 iframe[src], #content-embed iframe[src], div.movieplay iframe[src], iframe[src]")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isBlank() || src.contains("ads", true)) continue
            val tabId = iframe.parents().firstOrNull { it.id().startsWith("tab") }?.id()
            val label = tabLabels[tabId] ?: name

            when {
                src.contains("speedostream", true) && src.contains("embed-") -> {
                    if (resolveSpeedostream(src, label, callback)) found = true
                }
                src.contains("vidsrc", true) -> {
                    if (resolveVidsrc(src, pageUrl, callback)) found = true
                }
                else -> {
                    loadExtractor(fixUrl(src), pageUrl, subtitleCallback, callback)
                }
            }
        }

        if (!found) {
            for (a in doc.select("#list-downloads a[href*=speedostream], .links_table a[href*=speedostream]")) {
                val href = a.attr("href")
                if (href.isBlank() || href.contains("embed-")) continue
                val label = a.selectFirst(".serv_tit")?.text()?.ifBlank { null } ?: name
                if (resolveSpeedostream(href, "$label • Download", callback)) found = true
            }
        }
        return found
    }

    private suspend fun resolveEpisodePage(
        doc: Element,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        for (iframe in doc.select("#player2 iframe[src], div.movieplay iframe[src], iframe[src]")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isBlank() || src.contains("ads", true)) continue

            when {
                src.contains("vidsrc", true) -> {
                    if (resolveVidsrc(src, pageUrl, callback)) found = true
                }
                src.contains("speedostream", true) && src.contains("embed-") -> {
                    if (resolveSpeedostream(src, name, callback)) found = true
                }
                else -> {
                    loadExtractor(fixUrl(src), pageUrl, subtitleCallback, callback)
                }
            }
        }
        return found
    }

    private suspend fun resolveSpeedostream(embedUrl: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val clean = fixUrlNull(embedUrl) ?: return false
            val host = try { Uri.parse(clean).host ?: "speedostream1.com" } catch (e: Exception) { "speedostream1.com" }
            val r = getPage(clean, referer = "https://$host/") ?: return false
            if (!r.isSuccessful) return false
            val text = r.text
            if (text.contains("Embeds disabled", true)) return false

            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) return false

            callback(
                newExtractorLink(
                    source = name,
                    name = label.take(40).ifBlank { name },
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://$host/"
                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://$host/")
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun resolveVidsrc(embedUrl: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val clean = fixUrlNull(embedUrl) ?: return false
            val r1 = getPage(clean, referer = referer) ?: return false
            val d1 = Jsoup.parse(r1.text)

            var target: String? = null
            d1.select("iframe[src]").forEach {
                val s = it.attr("src")
                if (s.isNotBlank()) { target = if (s.startsWith("http")) s else fixUrlNull(s); return@forEach }
            }
            val playerUrl = target ?: return false
            if (!playerUrl.startsWith("http")) return false

            val r2 = getPage(playerUrl, referer = clean) ?: return false
            val text = r2.text

            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""file["']?\s*["']?["']?([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""https:[^"' ]+\.m3u8[^"' ]*""").find(text)?.groupValues?.get(0)
            if (m3u8.isNullOrBlank()) return false

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = playerUrl
                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to playerUrl)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
