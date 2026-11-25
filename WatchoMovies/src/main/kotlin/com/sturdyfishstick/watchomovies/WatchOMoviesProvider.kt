package com.sturdyfishstick.watchomovies

import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WatchOMoviesProvider : MainAPI() {
    override var mainUrl              = "https://watchomovies.social"
    override var name                 = "WatchOMovies"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val userAgent = WatchOMoviesSettingsDialog.CF_BYPASS_UA

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val cfMutexMap = ConcurrentHashMap<String, Mutex>()
    private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()
    private val hostFailCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val hostDeadUntil = ConcurrentHashMap<String, Long>()

    data class CatEntry(
        val path: String,
        val label: String,
        val group: String // "Indian OTT", "Studios 18+", "Tubes"
    )

    fun applySavedSettings() {
        mainUrl = WatchOMoviesSettingsDialog.getServerDomain("watchomovies")
    }

    fun clearCfCache() {
        cfKillerMap.values.forEach { runCatching { it.savedCookies.clear() } }
        cfKillerMap.clear()
        hostFailCounts.clear()
        hostDeadUntil.clear()
    }

    private fun mutexFor(url: String): Mutex =
        cfMutexMap.getOrPut(try { Uri.parse(url).host ?: mainUrl } catch (e: Exception) { mainUrl }) { Mutex() }

    private fun killerFor(url: String): CloudflareKiller =
        cfKillerMap.getOrPut(try { Uri.parse(url).host ?: mainUrl } catch (e: Exception) { mainUrl }) { CloudflareKiller() }

    private fun isCloudflarePage(response: NiceResponse): Boolean =
        response.code in listOf(403, 503)

    private fun srvBase(serverKey: String): String =
        WatchOMoviesSettingsDialog.getServerDomain(serverKey)

    private fun encodeRoute(serverKey: String, payload: String): String {
        val enc = URLEncoder.encode(payload, "UTF-8")
        return "https://wom.internal/$serverKey?u=$enc"
    }

    private fun decodeRoute(raw: String): Pair<String, String> {
        val cleaned = raw.substringAfter("watchomovies.social/").takeIf {
            raw.contains("watchomovies.social/srv:") || raw.contains("wom.internal/")
        } ?: raw

        if (cleaned.contains("wom.internal/")) {
            val uri = Uri.parse(cleaned)
            val key = uri.pathSegments.firstOrNull().orEmpty()
            val payload = uri.getQueryParameter("u").orEmpty()
            if (key.isNotBlank()) return key to payload
        }

        if (cleaned.startsWith("srv:")) {
            val rest = cleaned.removePrefix("srv:")
            val key = rest.substringBefore("|")
            val payload = rest.substringAfter("|", "")
            if (key.isNotBlank()) return key to payload
        }

        val low = raw.lowercase()
        val matched = WatchOMoviesSettingsDialog.SERVERS.firstOrNull { srv ->
            val domHost = srvBase(srv.key).substringAfter("://").substringBefore("/").removePrefix("www.").lowercase()
            low.contains(domHost)
        }?.key ?: "watchomovies"
        return matched to raw
    }

    private suspend fun getPage(url: String, referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): NiceResponse? {
        val host = try { Uri.parse(url).host?.lowercase().orEmpty() } catch (e: Exception) { "" }
        val now = System.currentTimeMillis()
        val deadUntil = hostDeadUntil[host] ?: 0L
        if (host.isNotBlank() && now < deadUntil) {
            return null
        }

        return try {
            val headers = browserHeaders.toMutableMap()
            headers.putAll(extraHeaders)
            WatchOMoviesSettingsDialog.getCookieForDomain(url)?.let {
                headers["Cookie"] = it
            }

            val timeoutSec = WatchOMoviesSettingsDialog.getTimeoutSeconds().toLong()
            val response = app.get(url, headers = headers, referer = referer, timeout = timeoutSec)
            if (host.isNotBlank()) {
                hostFailCounts[host]?.set(0)
                hostDeadUntil.remove(host)
            }

            if (!isCloudflarePage(response)) {
                response
            } else if (WatchOMoviesSettingsDialog.isCfAutoRetry()) {
                mutexFor(url).withLock {
                    val cfKiller = killerFor(url)
                    val retryResponse = app.get(url, headers = headers, interceptor = cfKiller, referer = referer, timeout = timeoutSec)
                    if (isCloudflarePage(retryResponse)) {
                        cfKiller.savedCookies.clear()
                        app.get(url, headers = headers, interceptor = cfKiller, referer = referer, timeout = timeoutSec)
                    } else {
                        retryResponse
                    }
                }
            } else {
                WatchOMoviesSettingsDialog.logDiag("CF", "Blocked (${response.code}): $url")
                response
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fails = if (host.isNotBlank()) hostFailCounts.getOrPut(host) { AtomicInteger(0) }.incrementAndGet() else 1
            if (fails == 2 && host.isNotBlank()) {
                hostDeadUntil[host] = System.currentTimeMillis() + 60_000L
                WatchOMoviesSettingsDialog.logDiag("TIMEOUT", "$host unreachable — paused 60s (${e.message})")
            } else if (fails < 2) {
                WatchOMoviesSettingsDialog.logDiag("ERROR", "$url -> ${e.message}")
            }
            null
        }
    }

    // =========================================================================
    // ALL 407 CATEGORIES CLASSIFIED IN THEIR EXACT GROUPS (ZERO MISSING)
    // =========================================================================
    private val serverCategoryMap: Map<String, List<CatEntry>> by lazy {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        mapOf(
            // 1. WatchOMovies (34 Indian OTT + 20 International Studios + 2 Tubes = 56)
            "watchomovies" to listOf(
                // Indian OTT (34)
                CatEntry("director/ullu-originals", "Ullu Originals", "Indian OTT"),
                CatEntry("director/fliz-movies", "Fliz Movies", "Indian OTT"),
                CatEntry("director/nuefliks", "Nuefliks", "Indian OTT"),
                CatEntry("director/hotshots", "Hotshots", "Indian OTT"),
                CatEntry("director/hunters", "Hunters", "Indian OTT"),
                CatEntry("director/moodx-originals", "MoodX", "Indian OTT"),
                CatEntry("director/voovi", "VooVi", "Indian OTT"),
                CatEntry("director/primeplay", "PrimePlay", "Indian OTT"),
                CatEntry("director/besharams", "Besharams", "Indian OTT"),
                CatEntry("director/kooku", "Kooku", "Indian OTT"),
                CatEntry("director/gupchup", "Gupchup", "Indian OTT"),
                CatEntry("director/feneomovies", "Feneomovies", "Indian OTT"),
                CatEntry("director/cinemadosti", "Cinemadosti", "Indian OTT"),
                CatEntry("director/primeflix", "Primeflix", "Indian OTT"),
                CatEntry("director/gemplex", "Gemplex", "Indian OTT"),
                CatEntry("director/rabbit", "Rabbit", "Indian OTT"),
                CatEntry("director/hotmasti", "HotMasti", "Indian OTT"),
                CatEntry("director/mastiprime", "MastiPrime", "Indian OTT"),
                CatEntry("director/ek-night-show", "Ek Night Show", "Indian OTT"),
                CatEntry("director/flixskmovies", "Flixskmovies", "Indian OTT"),
                CatEntry("director/lootlo", "Lootlo", "Indian OTT"),
                CatEntry("director/hootzy", "Hootzy", "Indian OTT"),
                CatEntry("director/balloons", "Balloons", "Indian OTT"),
                CatEntry("director/big-movie-zoo", "Big Movie Zoo", "Indian OTT"),
                CatEntry("director/bambooflix", "Bambooflix", "Indian OTT"),
                CatEntry("director/piliflix", "Piliflix", "Indian OTT"),
                CatEntry("director/11upmovies", "11upmovies", "Indian OTT"),
                CatEntry("director/eightshots", "Eightshots", "Indian OTT"),
                CatEntry("director/i-entertainment", "I-Entertainment", "Indian OTT"),
                CatEntry("director/hotprime", "Hotprime", "Indian OTT"),
                CatEntry("director/bananaprime", "BananaPrime", "Indian OTT"),
                CatEntry("director/chikooflix", "Chikooflix", "Indian OTT"),
                CatEntry("director/glamheart", "Glamheart", "Indian OTT"),
                CatEntry("director/worldprime", "Worldprime", "Indian OTT"),

                // Studios 18+ (20)
                CatEntry("genre/brazzers", "Brazzers", "Studios 18+"),
                CatEntry("genre/bangbros", "Bangbros", "Studios 18+"),
                CatEntry("genre/naughty-america", "Naughty America", "Studios 18+"),
                CatEntry("genre/reality-kings", "Reality Kings", "Studios 18+"),
                CatEntry("genre/vixen", "Vixen", "Studios 18+"),
                CatEntry("genre/wicked-pictures", "Wicked Pictures", "Studios 18+"),
                CatEntry("genre/evil-angel", "Evil Angel", "Studios 18+"),
                CatEntry("genre/teamskeet", "Teamskeet", "Studios 18+"),
                CatEntry("genre/digital-playground", "Digital Playground", "Studios 18+"),
                CatEntry("genre/twistys", "Twistys", "Studios 18+"),
                CatEntry("genre/marc-dorcel", "Marc Dorcel", "Studios 18+"),
                CatEntry("genre/jules-jordan-video", "Jules Jordan Video", "Studios 18+"),
                CatEntry("genre/new-sensations", "New Sensations", "Studios 18+"),
                CatEntry("genre/ddf-network", "DDF Network", "Studios 18+"),
                CatEntry("genre/mofos", "Mofos", "Studios 18+"),
                CatEntry("genre/elegant-angel", "Elegant Angel", "Studios 18+"),
                CatEntry("genre/fakehub", "FakeHub", "Studios 18+"),
                CatEntry("genre/faketaxi", "FakeTaxi", "Studios 18+"),
                CatEntry("genre/girlsway", "GirlsWay", "Studios 18+"),
                CatEntry("genre/mylf", "MYLF", "Studios 18+"),

                // Tubes (2)
                CatEntry("", "Latest Updates", "Tubes"),
                CatEntry("movies", "Movies", "Tubes")
            ),

            // 2. TellyHD Indian (All 53 -> Indian OTT)
            "tellyhd" to listOf(
                CatEntry("release/$currentYear", "Latest ($currentYear)", "Indian OTT"),
                CatEntry("genre/18plus", "18+", "Indian OTT"),
                CatEntry("genre/altbalaji", "Alt Balaji", "Indian OTT"),
                CatEntry("genre/atrangii", "Atrangii", "Indian OTT"),
                CatEntry("genre/primeplay", "PrimePlay", "Indian OTT"),
                CatEntry("genre/moodx", "MoodX", "Indian OTT"),
                CatEntry("genre/neonx", "NeonX", "Indian OTT"),
                CatEntry("genre/hunters", "Hunters", "Indian OTT"),
                CatEntry("genre/rabbit", "Rabbit", "Indian OTT"),
                CatEntry("genre/hotshot", "HotShot", "Indian OTT"),
                CatEntry("genre/gupchup", "GupChup", "Indian OTT"),
                CatEntry("genre/hindi", "Hindi", "Indian OTT"),
                CatEntry("genre/hindi-dubbed", "Hindi Dubbed", "Indian OTT"),
                CatEntry("genre/indian", "INDIAN", "Indian OTT"),
                CatEntry("genre/niksindian", "Niksindian", "Indian OTT"),
                CatEntry("genre/rasminair", "RasmiNair", "Indian OTT"),
                CatEntry("genre/9redmovie", "9redmovie", "Indian OTT"),
                CatEntry("genre/addatv", "Addatv", "Indian OTT"),
                CatEntry("genre/aahaflix", "AahaFlix", "Indian OTT"),
                CatEntry("genre/bindastimes", "Bindastimes", "Indian OTT"),
                CatEntry("genre/bongonaari", "BongoNaari", "Indian OTT"),
                CatEntry("genre/boomex", "BoomEX", "Indian OTT"),
                CatEntry("genre/bulbulplay", "Bulbulplay", "Indian OTT"),
                CatEntry("genre/bullap", "bullap", "Indian OTT"),
                CatEntry("genre/cineon", "CineOn", "Indian OTT"),
                CatEntry("genre/crf-studioz", "CRF Studioz", "Indian OTT"),
                CatEntry("genre/cineprime", "CinePrime", "Indian OTT"),
                CatEntry("genre/digimovieplex", "Digimovieplex", "Indian OTT"),
                CatEntry("genre/dugru", "Dugru", "Indian OTT"),
                CatEntry("genre/dyzreplay", "DyzrePlay", "Indian OTT"),
                CatEntry("genre/eortv", "Eortv", "Indian OTT"),
                CatEntry("genre/feelapp", "Feelapp", "Indian OTT"),
                CatEntry("genre/feneo", "Feneo", "Indian OTT"),
                CatEntry("genre/feniapo", "Feniapp", "Indian OTT"),
                CatEntry("genre/foxxprime", "foxxprime", "Indian OTT"),
                CatEntry("genre/fugi", "Fugi", "Indian OTT"),
                CatEntry("genre/fukrey", "Fukrey", "Indian OTT"),
                CatEntry("genre/fun2sh", "fun2sh", "Indian OTT"),
                CatEntry("genre/funtyy", "funtyy", "Indian OTT"),
                CatEntry("genre/goddesmahi", "GoddesMahi", "Indian OTT"),
                CatEntry("genre/goodflixmovies", "goodflixmovies", "Indian OTT"),
                CatEntry("genre/habbitmovies", "HabbitMovies", "Indian OTT"),
                CatEntry("genre/hitprime", "Hitprime", "Indian OTT"),
                CatEntry("genre/hotfm", "HotFM", "Indian OTT"),
                CatEntry("genre/hotx", "Hotx", "Indian OTT"),
                CatEntry("genre/hulchul", "HulChul", "Indian OTT"),
                CatEntry("genre/ibamovies", "IBAMovies", "Indian OTT"),
                CatEntry("genre/jalva", "Jalva", "Indian OTT"),
                CatEntry("genre/jugnu", "Jugnu", "Indian OTT"),
                CatEntry("genre/jugnutv", "JugnuTV", "Indian OTT"),
                CatEntry("genre/lookentertainment", "Lookentertainment", "Indian OTT"),
                CatEntry("genre/makhan", "Makhan", "Indian OTT"),
                CatEntry("genre/meetx", "Meetx", "Indian OTT")
            ),

            // 3. PrmoviesIndian (All 36 -> Indian OTT)
            "prmovies" to listOf(
                CatEntry("director/ullu-originals", "Ullu Originals", "Indian OTT"),
                CatEntry("director/uncut", "Uncut", "Indian OTT"),
                CatEntry("director/fliz-movies", "Fliz Movies", "Indian OTT"),
                CatEntry("director/nuefliks", "Nuefliks", "Indian OTT"),
                CatEntry("director/hotshots", "Hotshots", "Indian OTT"),
                CatEntry("director/hunters", "Hunters", "Indian OTT"),
                CatEntry("director/moodx", "MoodX", "Indian OTT"),
                CatEntry("director/voovi", "VooVi", "Indian OTT"),
                CatEntry("director/primeplay", "PrimePlay", "Indian OTT"),
                CatEntry("director/besharams", "Besharams", "Indian OTT"),
                CatEntry("director/kooku", "Kooku", "Indian OTT"),
                CatEntry("director/gupchup", "Gupchup", "Indian OTT"),
                CatEntry("director/feneomovies", "Feneomovies", "Indian OTT"),
                CatEntry("director/cinemadosti", "Cinemadosti", "Indian OTT"),
                CatEntry("director/primeflix", "Primeflix", "Indian OTT"),
                CatEntry("director/gemplex", "Gemplex", "Indian OTT"),
                CatEntry("director/rabbit", "Rabbit", "Indian OTT"),
                CatEntry("director/hotmasti", "HotMasti", "Indian OTT"),
                CatEntry("director/boommovies", "BoomMovies", "Indian OTT"),
                CatEntry("director/cliffmovies", "CliffMovies", "Indian OTT"),
                CatEntry("director/mastiprime", "MastiPrime", "Indian OTT"),
                CatEntry("director/ek-night-show", "Ek Night Show", "Indian OTT"),
                CatEntry("director/flixskmovies", "Flixskmovies", "Indian OTT"),
                CatEntry("director/lootlo", "Lootlo", "Indian OTT"),
                CatEntry("director/hootzy", "Hootzy", "Indian OTT"),
                CatEntry("director/balloons", "Balloons", "Indian OTT"),
                CatEntry("director/big-movie-zoo", "Big Movie Zoo", "Indian OTT"),
                CatEntry("director/bambooflix", "Bambooflix", "Indian OTT"),
                CatEntry("director/piliflix", "Piliflix", "Indian OTT"),
                CatEntry("director/11upmovies", "11upmovies", "Indian OTT"),
                CatEntry("director/eightshots", "Eightshots", "Indian OTT"),
                CatEntry("director/i-entertainment", "I-Entertainment", "Indian OTT"),
                CatEntry("director/hothitfilms", "HotHitFilms", "Indian OTT"),
                CatEntry("director/worldprime", "Worldprime", "Indian OTT"),
                CatEntry("director/chikooflix", "Chikooflix", "Indian OTT"),
                CatEntry("director/glamheart", "Glamheart", "Indian OTT")
            ),

            // 4. Aagmaal (All 20 -> Indian OTT)
            "aagmaal" to listOf(
                CatEntry("", "Latest", "Indian OTT"),
                CatEntry("category/ullu-originals-web-series", "Ullu", "Indian OTT"),
                CatEntry("tag/moodx", "MoodX", "Indian OTT"),
                CatEntry("tag/hunters", "Hunters", "Indian OTT"),
                CatEntry("tag/primeplay", "PrimePlay", "Indian OTT"),
                CatEntry("tag/kooku", "Kooku", "Indian OTT"),
                CatEntry("tag/besharams", "Besharams", "Indian OTT"),
                CatEntry("tag/neonx", "NeonX", "Indian OTT"),
                CatEntry("category/aagmaal", "AAGmaal", "Indian OTT"),
                CatEntry("category/best-videos", "Best videos", "Indian OTT"),
                CatEntry("category/bindas-times", "Bindas Times", "Indian OTT"),
                CatEntry("category/fukrey-app", "Fukrey.App", "Indian OTT"),
                CatEntry("category/hindi-hot-web", "Hindi Hot Web", "Indian OTT"),
                CatEntry("category/hot-hindi-web", "Hot Hindi Web", "Indian OTT"),
                CatEntry("category/hotx", "HotX", "Indian OTT"),
                CatEntry("category/indian-uncut-web", "Indian Uncut Web", "Indian OTT"),
                CatEntry("category/mojflix", "MojFlix", "Indian OTT"),
                CatEntry("category/moodx-uncut", "MoodX Uncut", "Indian OTT"),
                CatEntry("category/neonx-uncut", "NeonX Uncut", "Indian OTT"),
                CatEntry("category/xprime", "XPrime", "Indian OTT")
            ),

            // 5. BrazzPW (20 Studio Sites -> Studios 18+, 15 Feeds/Tags -> Tubes = 35)
            "brazzpw" to listOf(
                // Studios 18+ (20)
                CatEntry("/videos/site/65/big-wet-butts/", "Big Wet Butts", "Studios 18+"),
                CatEntry("/videos/site/66/baby-got-boobs/", "Baby Got Boobs", "Studios 18+"),
                CatEntry("/videos/site/62/doctor-adventures/", "Doctor Adventures", "Studios 18+"),
                CatEntry("/videos/site/67/mommy-got-boobs/", "Mommy Got Boobs", "Studios 18+"),
                CatEntry("/videos/site/71/big-tits-at-work/", "Big Tits At Work", "Studios 18+"),
                CatEntry("/videos/site/75/big-tits-at-school/", "Big Tits At School", "Studios 18+"),
                CatEntry("/videos/site/99/mom-in-control/", "Mom in control", "Studios 18+"),
                CatEntry("/videos/site/98/cfnm/", "Cfnm", "Studios 18+"),
                CatEntry("/videos/site/96/brazzers-exxtra/", "Brazzers Exxtra", "Studios 18+"),
                CatEntry("/videos/site/94/dirty-masseur/", "Dirty masseur", "Studios 18+"),
                CatEntry("/videos/site/92/zz-series/", "Zz Series", "Studios 18+"),
                CatEntry("/videos/site/90/hot-and-mean/", "Hot and mean", "Studios 18+"),
                CatEntry("/videos/site/89/big-tits-in-uniform/", "Big tits in uniform", "Studios 18+"),
                CatEntry("/videos/site/87/day-with-pornstar/", "Day with pornstar", "Studios 18+"),
                CatEntry("/videos/site/83/big-tits-in-sports/", "Big tits in sports", "Studios 18+"),
                CatEntry("/videos/site/82/big-butts-like-it-big/", "Big butts like it big", "Studios 18+"),
                CatEntry("/videos/site/81/real-wife-stories/", "Real wife Stories", "Studios 18+"),
                CatEntry("/videos/site/80/teen-like-it-big/", "Teen like it big", "Studios 18+"),
                CatEntry("/videos/site/78/milfs-like-it-big/", "Milfs like it big", "Studios 18+"),
                CatEntry("/videos/site/77/pornstar-like-it-big/", "Pornstar like it big", "Studios 18+"),

                // Tubes (15)
                CatEntry("/videos/newest/", "Newest Videos", "Tubes"),
                CatEntry("/videos/sortby/beingwatched/", "Being Watched", "Tubes"),
                CatEntry("/videos/sortby/rating/", "Top Rated", "Tubes"),
                CatEntry("/videos/sortby/views/", "Most Viewed", "Tubes"),
                CatEntry("/videos/tags/112/black/", "Black", "Tubes"),
                CatEntry("/videos/tags/448/anal/", "Anal", "Tubes"),
                CatEntry("/videos/tags/79/milf/", "Milf", "Tubes"),
                CatEntry("/videos/tags/49/big-ass/", "Big Ass", "Tubes"),
                CatEntry("/videos/tags/220/squirt/", "Squirt", "Tubes"),
                CatEntry("/videos/tags/92/bbw/", "BBW", "Tubes"),
                CatEntry("/videos/tags/181/stepmom/", "Stepmom", "Tubes"),
                CatEntry("/videos/tags/211/massage/", "Massage", "Tubes"),
                CatEntry("/videos/tags/511/parody/", "Parody", "Tubes"),
                CatEntry("/videos/tags/192/yoga/", "Yoga", "Tubes"),
                CatEntry("/videos/tags/473/double-penetration/", "Double Penetration", "Tubes")
            ),

            // 6. XMoviesForYou (All 53 -> Studios 18+)
            "xmoviesforyou" to listOf(
                CatEntry("/studio/assparade", "Studio: AssParade", "Studios 18+"),
                CatEntry("/studio/bangbus", "Studio: BangBus", "Studios 18+"),
                CatEntry("/studio/bjraw", "Studio: BJRaw", "Studios 18+"),
                CatEntry("/studio/blacked", "Studio: Blacked", "Studios 18+"),
                CatEntry("/studio/blackedraw", "Studio: BlackedRaw", "Studios 18+"),
                CatEntry("/studio/blacksonblondes", "Studio: BlacksOnBlondes", "Studios 18+"),
                CatEntry("/studio/brazzersexxtra", "Studio: BrazzersExxtra", "Studios 18+"),
                CatEntry("/studio/cherrypimps", "Studio: CherryPimps", "Studios 18+"),
                CatEntry("/studio/dadcrush", "Studio: DadCrush", "Studios 18+"),
                CatEntry("/studio/deeplush", "Studio: DeepLush", "Studios 18+"),
                CatEntry("/studio/deeper", "Studio: Deeper", "Studios 18+"),
                CatEntry("/studio/devilsfilm", "Studio: DevilsFilm", "Studios 18+"),
                CatEntry("/studio/dickdrainers", "Studio: DickDrainers", "Studios 18+"),
                CatEntry("/studio/dirtyauditions", "Studio: DirtyAuditions", "Studios 18+"),
                CatEntry("/studio/dorcelclub", "Studio: DorcelClub", "Studios 18+"),
                CatEntry("/studio/elegantangel", "Studio: ElegantAngel", "Studios 18+"),
                CatEntry("/studio/evilangel", "Studio: EvilAngel", "Studios 18+"),
                CatEntry("/studio/familystrokes", "Studio: FamilyStrokes", "Studios 18+"),
                CatEntry("/studio/gangbangcreampie", "Studio: GangbangCreampie", "Studios 18+"),
                CatEntry("/studio/girlsway", "Studio: GirlsWay", "Studios 18+"),
                CatEntry("/studio/hotwifexxx", "Studio: HotwifeXXX", "Studios 18+"),
                CatEntry("/studio/hussiepass", "Studio: HussiePass", "Studios 18+"),
                CatEntry("/studio/hustler", "Studio: Hustler", "Studios 18+"),
                CatEntry("/studio/japanhdv", "Studio: JapanHDV", "Studios 18+"),
                CatEntry("/studio/jayspov", "Studio: JaysPOV", "Studios 18+"),
                CatEntry("/studio/julesjordan", "Studio: JulesJordan", "Studios 18+"),
                CatEntry("/studio/loveherfeet", "Studio: LoveHerFeet", "Studios 18+"),
                CatEntry("/studio/mylifeinmiami", "Studio: MyLifeInMiami", "Studios 18+"),
                CatEntry("/studio/mypervyfamily", "Studio: MyPervyFamily", "Studios 18+"),
                CatEntry("/studio/mysistershotfriend", "Studio: MySistersHotFriend", "Studios 18+"),
                CatEntry("/studio/newsensations", "Studio: NewSensations", "Studios 18+"),
                CatEntry("/studio/penthousegold", "Studio: PenthouseGold", "Studios 18+"),
                CatEntry("/studio/pervmom", "Studio: PervMom", "Studios 18+"),
                CatEntry("/studio/porndudecasting", "Studio: PornDudeCasting", "Studios 18+"),
                CatEntry("/studio/pornworld", "Studio: PornWorld", "Studios 18+"),
                CatEntry("/studio/private", "Studio: Private", "Studios 18+"),
                CatEntry("/studio/puretaboo", "Studio: PureTaboo", "Studios 18+"),
                CatEntry("/studio/roccosiffredi", "Studio: RoccoSiffredi", "Studios 18+"),
                CatEntry("/studio/seehimfuck", "Studio: SeeHimFuck", "Studios 18+"),
                CatEntry("/studio/sexmex", "Studio: SexMex", "Studios 18+"),
                CatEntry("/studio/shoplyfter", "Studio: Shoplyfter", "Studios 18+"),
                CatEntry("/studio/sislovesme", "Studio: SisLovesMe", "Studios 18+"),
                CatEntry("/studio/spankmonster", "Studio: SpankMonster", "Studios 18+"),
                CatEntry("/studio/sweetsinner", "Studio: SweetSinner", "Studios 18+"),
                CatEntry("/studio/tabooheat", "Studio: TabooHeat", "Studios 18+"),
                CatEntry("/studio/teamskeetselects", "Studio: TeamSkeetSelects", "Studios 18+"),
                CatEntry("/studio/teenfidelity", "Studio: TeenFidelity", "Studios 18+"),
                CatEntry("/studio/throated", "Studio: Throated", "Studios 18+"),
                CatEntry("/studio/touchmywife", "Studio: TouchMyWife", "Studios 18+"),
                CatEntry("/studio/tushy", "Studio: Tushy", "Studios 18+"),
                CatEntry("/studio/tushyraw", "Studio: TushyRaw", "Studios 18+"),
                CatEntry("/studio/vixen", "Studio: Vixen", "Studios 18+"),
                CatEntry("/studio/wicked", "Studio: Wicked", "Studios 18+")
            ),

            // 7. XXXTime (5 Studio Channels -> Studios 18+, 43 Categories/Models -> Tubes = 48)
            "xxxtime" to listOf(
                // Studios 18+ (5)
                CatEntry("/channels.php?ch=Teamskeet", "Teamskeet", "Studios 18+"),
                CatEntry("/channels.php?ch=Cum4k", "Cum4k", "Studios 18+"),
                CatEntry("/channels.php?ch=Brazzers", "Brazzers", "Studios 18+"),
                CatEntry("/channels.php?ch=Mature4k", "Mature4k", "Studios 18+"),
                CatEntry("/channels.php?ch=Mom4k", "Mom4k", "Studios 18+"),

                // Tubes & Models (43)
                CatEntry("/categories.php?c=Big+Ass", "Big Ass", "Tubes"),
                CatEntry("/categories.php?c=Redhead", "Redhead", "Tubes"),
                CatEntry("/model.php?p=Abigail-Morris", "Abigail Morris", "Tubes"),
                CatEntry("/model.php?p=Alanna-pow", "Alanna Pow", "Tubes"),
                CatEntry("/model.php?p=Aleksa-Mink", "Aleksa Mink", "Tubes"),
                CatEntry("/model.php?p=Alexa-Chains", "Alexa Chains", "Tubes"),
                CatEntry("/model.php?p=Ana-Khalifa", "Ana Khalifa", "Tubes"),
                CatEntry("/model.php?p=Angel-Youngs", "Angel Youngs", "Tubes"),
                CatEntry("/model.php?p=Angela-White", "Angela White", "Tubes"),
                CatEntry("/model.php?p=aspen-reign", "Aspen Reign", "Tubes"),
                CatEntry("/model.php?p=Comatozze", "Comatozze", "Tubes"),
                CatEntry("/model.php?p=Carolina-Guerrero", "Carolina Guerrero", "Tubes"),
                CatEntry("/model.php?p=Coco-Lovelock", "Coco Lovelock", "Tubes"),
                CatEntry("/model.php?p=Dee-Williams", "Dee Williams", "Tubes"),
                CatEntry("/model.php?p=ellie-nova", "Ellie Nova", "Tubes"),
                CatEntry("/model.php?p=Elly-Clutch", "Elly Clutch", "Tubes"),
                CatEntry("/model.php?p=Eve-Sweet", "Eve Sweet", "Tubes"),
                CatEntry("/model.php?p=Hailey-Rose", "Hailey Rose", "Tubes"),
                CatEntry("/model.php?p=Isa-Bella", "Isa Bella", "Tubes"),
                CatEntry("/model.php?p=jasmine-sherni", "Jasmine Sherni", "Tubes"),
                CatEntry("/model.php?p=Juniper-Ren", "Juniper Ren", "Tubes"),
                CatEntry("/model.php?p=Koda-Monroe", "Koda Monroe", "Tubes"),
                CatEntry("/model.php?p=Kali-Roses", "Kali Roses", "Tubes"),
                CatEntry("/model.php?p=Kayley-Gunner", "Kayley Gunner", "Tubes"),
                CatEntry("/model.php?p=Lauren-Phillips", "Lauren Phillips", "Tubes"),
                CatEntry("/model.php?p=Lena-The-Plug", "Lena The Plug", "Tubes"),
                CatEntry("/model.php?p=LuSirena69", "LuSirena69", "Tubes"),
                CatEntry("/model.php?p=Lucy-Mochi", "Lucy Mochi", "Tubes"),
                CatEntry("/model.php?p=Lula-Stocch", "Lula Stocch", "Tubes"),
                CatEntry("/model.php?p=Luna-Doll", "Luna Doll", "Tubes"),
                CatEntry("/model.php?p=Melissa-Stratton", "Melissa Stratton", "Tubes"),
                CatEntry("/model.php?p=Mia-Khalifa", "Mia Khalifa", "Tubes"),
                CatEntry("/model.php?p=Nia-Bleu", "Nia Bleu", "Tubes"),
                CatEntry("/model.php?p=Octokuro", "Octokuro", "Tubes"),
                CatEntry("/model.php?p=Payton-Preslee", "Payton Preslee", "Tubes"),
                CatEntry("/model.php?p=Raissa-Bellini", "Raissa Bellini", "Tubes"),
                CatEntry("/model.php?p=Rossa-Vaxx", "Rossa Vaxx", "Tubes"),
                CatEntry("/model.php?p=Roxie-Sinner", "Roxie Sinner", "Tubes"),
                CatEntry("/model.php?p=Savannah-Bond", "Savannah Bond", "Tubes"),
                CatEntry("/model.php?p=Vivienne-Vo", "Vivienne Vo", "Tubes"),
                CatEntry("/model.php?p=Willow-Ryder", "Willow Ryder", "Tubes"),
                CatEntry("/model.php?p=Yasmina-Khan", "Yasmina Khan", "Tubes"),
                CatEntry("/model.php?p=Zoey-Uso", "Zoey Uso", "Tubes")
            ),

            // 8. WatchPorn (14 Studios -> Studios 18+, 2 Feeds -> Tubes = 16)
            "watchporn" to listOf(
                // Studios 18+ (14)
                CatEntry("/categories/primalfetish/", "PrimalFetish", "Studios 18+"),
                CatEntry("/categories/brazzersexxtra/", "BrazzersExxtra", "Studios 18+"),
                CatEntry("/categories/julesjordan/", "JulesJordan", "Studios 18+"),
                CatEntry("/categories/tabooheat/", "TabooHeat", "Studios 18+"),
                CatEntry("/categories/outofthefamily/", "OutOfTheFamily", "Studios 18+"),
                CatEntry("/categories/missax/", "MissaX", "Studios 18+"),
                CatEntry("/categories/blackedraw/", "BlackedRaw", "Studios 18+"),
                CatEntry("/categories/vixen/", "Vixen", "Studios 18+"),
                CatEntry("/categories/rkprime/", "RKPrime", "Studios 18+"),
                CatEntry("/categories/puretaboo/", "PureTaboo", "Studios 18+"),
                CatEntry("/categories/deeper/", "Deeper", "Studios 18+"),
                CatEntry("/categories/tushy/", "Tushy", "Studios 18+"),
                CatEntry("/categories/blacked/", "Blacked", "Studios 18+"),
                CatEntry("/categories/bangbus/", "BangBus", "Studios 18+"),

                // Tubes (2)
                CatEntry("/top-rated/", "Top Rated", "Tubes"),
                CatEntry("/most-popular/", "Most Popular", "Tubes")
            ),

            // 9. Taboodaddy (10 Studios -> Studios 18+, 13 Tubes -> Tubes = 23)
            "taboodaddy" to listOf(
                // Studios 18+ (10)
                CatEntry("category/brazzers", "Brazzers", "Studios 18+"),
                CatEntry("category/bangbros", "BangBros", "Studios 18+"),
                CatEntry("category/blacked", "Blacked", "Studios 18+"),
                CatEntry("category/deeper", "Deeper", "Studios 18+"),
                CatEntry("category/digital-playground", "Digital Playground", "Studios 18+"),
                CatEntry("category/familystrokes", "FamilyStrokes", "Studios 18+"),
                CatEntry("category/missax", "Missax", "Studios 18+"),
                CatEntry("category/mommysboy", "MommysBoy", "Studios 18+"),
                CatEntry("category/puretaboo", "Pure Taboo", "Studios 18+"),
                CatEntry("category/realitykings", "Realitykings", "Studios 18+"),

                // Tubes (13)
                CatEntry("", "Latest", "Tubes"),
                CatEntry("category/analmom", "Anal Mom", "Tubes"),
                CatEntry("category/bdsm", "BDSM", "Tubes"),
                CatEntry("category/best", "Best", "Tubes"),
                CatEntry("category/brother-sister-porn", "Brother Sister Porn", "Tubes"),
                CatEntry("category/big-ass", "Big Ass", "Tubes"),
                CatEntry("category/big-dick", "Big Dick", "Tubes"),
                CatEntry("category/christmas", "Christmas", "Tubes"),
                CatEntry("category/deep-throat", "Deep Throat", "Tubes"),
                CatEntry("category/incent", "Incent", "Tubes"),
                CatEntry("category/milf", "Milf", "Tubes"),
                CatEntry("category/mom-son-porn", "Mom Son Porn", "Tubes"),
                CatEntry("category/taboo", "Taboo", "Tubes")
            ),

            // 10. Pvzija / BluMovies (1 Indian OTT + 39 Studios 18+ + 2 Tubes = 42)
            "pvzija" to listOf(
                // Indian OTT (1)
                CatEntry("director/indian-x/page/%d/", "Indian X", "Indian OTT"),

                // Studios 18+ (39)
                CatEntry("director/21-sextury-video-pulse/page/%d/", "21 Sextury Video Pulse", "Studios 18+"),
                CatEntry("director/adult-source-media/page/%d/", "Adult Source Media", "Studios 18+"),
                CatEntry("director/bang/page/%d/", "Bang", "Studios 18+"),
                CatEntry("director/blacked/page/%d/", "Blacked", "Studios 18+"),
                CatEntry("director/brazzers/page/%d/", "Brazzers", "Studios 18+"),
                CatEntry("director/cutie-pie/page/%d/", "Cutie Pie", "Studios 18+"),
                CatEntry("director/ddf-network/page/%d/", "DDF Network", "Studios 18+"),
                CatEntry("director/devils-film/page/%d/", "Devils Film", "Studios 18+"),
                CatEntry("director/digital-playground/page/%d/", "Digital Playground", "Studios 18+"),
                CatEntry("director/digital-sin/page/%d/", "Digital Sin", "Studios 18+"),
                CatEntry("director/doghouse-digital/page/%d/", "Doghouse Digital", "Studios 18+"),
                CatEntry("director/dorcel-english/page/%d/", "Dorcel English", "Studios 18+"),
                CatEntry("director/elegant-angel/page/%d/", "Elegant Angel", "Studios 18+"),
                CatEntry("director/erotic-planet/page/%d/", "Erotic Planet", "Studios 18+"),
                CatEntry("director/fantasy-massage/page/%d/", "Fantasy Massage", "Studios 18+"),
                CatEntry("director/filthy-kings-clips/page/%d/", "Filthy Kings Clips", "Studios 18+"),
                CatEntry("director/house-of-fyre/page/%d/", "House Of Fyre", "Studios 18+"),
                CatEntry("director/jules-jordan-video/page/%d/", "Jules Jordan Video", "Studios 18+"),
                CatEntry("director/mario-salieri-productions/page/%d/", "Mario Salieri Productions", "Studios 18+"),
                CatEntry("director/mariskax-productions/page/%d/", "Mariskax Productions", "Studios 18+"),
                CatEntry("director/mile-high-xtreme/page/%d/", "Mile High Xtreme", "Studios 18+"),
                CatEntry("director/my-pervy-family/page/%d/", "My Pervy Family", "Studios 18+"),
                CatEntry("director/naughty-america/page/%d/", "Naughty America", "Studios 18+"),
                CatEntry("director/new-sensations/page/%d/", "New Sensations", "Studios 18+"),
                CatEntry("director/penthouse/page/%d/", "Penthouse", "Studios 18+"),
                CatEntry("director/pornfidelity/page/%d/", "Pornfidelity", "Studios 18+"),
                CatEntry("director/private/page/%d/", "Private", "Studios 18+"),
                CatEntry("director/pure-taboo/page/%d/", "Pure Taboo", "Studios 18+"),
                CatEntry("director/reality-junkies/page/%d/", "Reality Junkies", "Studios 18+"),
                CatEntry("director/reality-kings/page/%d/", "Reality Kings", "Studios 18+"),
                CatEntry("director/sexmex/page/%d/", "Sexmex", "Studios 18+"),
                CatEntry("director/sinful-xxx/page/%d/", "Sinful XXX", "Studios 18+"),
                CatEntry("director/sinner-xxx/page/%d/", "Sinner XXX", "Studios 18+"),
                CatEntry("director/sweet-sinner/page/%d/", "Sweet Sinner", "Studios 18+"),
                CatEntry("director/taboo-heat/page/%d/", "Taboo Heat", "Studios 18+"),
                CatEntry("director/team-skeet/page/%d/", "Team Skeet", "Studios 18+"),
                CatEntry("director/wicked-pictures/page/%d/", "Wicked Pictures", "Studios 18+"),
                CatEntry("director/will-tile-xxx/page/%d/", "Will Tile XXX", "Studios 18+"),
                CatEntry("director/xprime/page/%d/", "Xprime", "Studios 18+"),

                // Tubes (2)
                CatEntry("page/%d/", "New Movies", "Tubes"),
                CatEntry("page/%d/?filter=most-viewed", "Popular Movies", "Tubes")
            ),

            // 11. Free Porn Videos (All 25 -> Tubes)
            "freepornvideos" to listOf(
                CatEntry("/trending-porn-videos", "Trending Porn Videos", "Tubes"),
                CatEntry("/most-popular/week", "Most Popular", "Tubes"),
                CatEntry("/top-rated", "Top Rated", "Tubes"),
                CatEntry("/new-porn-videos", "New Porn Videos", "Tubes"),
                CatEntry("/categories/ball-licking", "Ball Licking", "Tubes"),
                CatEntry("/categories/ball-play", "Ball Play", "Tubes"),
                CatEntry("/categories/ball-sucking", "Ball Sucking", "Tubes"),
                CatEntry("/categories/ballerina", "Ballerina", "Tubes"),
                CatEntry("/categories/bathroom", "Bathroom", "Tubes"),
                CatEntry("/categories/bbc-big-black-cock", "BBC (Big Black Cock)", "Tubes"),
                CatEntry("/categories/beautiful", "Beautiful", "Tubes"),
                CatEntry("/categories/bbw", "BBW", "Tubes"),
                CatEntry("/categories/big-areolas", "Big Areolas", "Tubes"),
                CatEntry("/categories/cfnm", "CFNM", "Tubes"),
                CatEntry("/categories/cum-in-mouth", "Cum in Mouth", "Tubes"),
                CatEntry("/categories/doctor", "Doctor", "Tubes"),
                CatEntry("/categories/ebony", "Ebony", "Tubes"),
                CatEntry("/categories/freeuse", "FreeUse", "Tubes"),
                CatEntry("/categories/high-heels", "High Heels", "Tubes"),
                CatEntry("/categories/mature", "Mature", "Tubes"),
                CatEntry("/categories/massage", "Massage", "Tubes"),
                CatEntry("/categories/milf", "MILF", "Tubes"),
                CatEntry("/categories/mom", "Mom", "Tubes"),
                CatEntry("/categories/mexican", "Mexican", "Tubes"),
                CatEntry("/categories/pantyhose", "Pantyhose", "Tubes")
            )
        )
    }

    /**
     * Strictly separated into 3 sequential sections:
     * 1. Indian OTT (144 categories — ONLY Indian)
     * 2. International Studios 18+ (161 categories — ONLY Studios)
     * 3. Tubes & Models (102 categories — ONLY Tubes)
     */
    override val mainPage: List<MainPageData>
        get() {
            val enabledServers = WatchOMoviesSettingsDialog.getOrderedEnabledServers()
                .ifEmpty { listOf(WatchOMoviesSettingsDialog.SERVERS.first()) }
            val limit = WatchOMoviesSettingsDialog.getRowsPerServer()
            val shuffle = WatchOMoviesSettingsDialog.isShuffleHome()

            val orderedGroups = listOf("Indian OTT", "Studios 18+", "Tubes")
            val finalRows = mutableListOf<MainPageData>()

            for (grp in orderedGroups) {
                if (!WatchOMoviesSettingsDialog.isGroupEnabled(grp)) continue
                val groupRows = mutableListOf<MainPageData>()
                val prefixIcon = when (grp) {
                    "Indian OTT" -> "🇮🇳"
                    "Studios 18+" -> "🎬"
                    else -> "📺"
                }

                enabledServers.forEach { srv ->
                    val matchingCats = serverCategoryMap[srv.key].orEmpty().filter { it.group == grp }
                    val sliced = if (limit >= 60) matchingCats else matchingCats.take(limit)
                    sliced.forEach { cat ->
                        groupRows.add(
                            MainPageData(
                                name = "$prefixIcon ${cat.label} • ${srv.name}",
                                data = encodeRoute(srv.key, cat.path)
                            )
                        )
                    }
                }

                if (shuffle && groupRows.size > 1) {
                    finalRows.add(groupRows.first())
                    finalRows.addAll(groupRows.drop(1).shuffled())
                } else {
                    finalRows.addAll(groupRows)
                }
            }

            return finalRows.ifEmpty {
                listOf(MainPageData("🇮🇳 Ullu Originals • WatchOMovies", encodeRoute("watchomovies", "director/ullu-originals")))
            }
        }

    // =========================================================================
    // Unified getMainPage Router (All 11 Servers)
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (srvKey, path) = decodeRoute(request.data)
        val base = srvBase(srvKey)

        return when (srvKey) {
            "watchomovies" -> {
                val pageUrl = when {
                    path.isBlank() && page <= 1 -> "$base/"
                    path.isBlank() -> "$base/page/$page/"
                    page <= 1 -> "$base/$path/"
                    else -> "$base/$path/page/$page/"
                }
                val html = getPage(pageUrl)?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val items = extractPsyPlayItems(html, base, "watchomovies")
                newHomePageResponse(HomePageList(request.name, items, false), hasNext = items.isNotEmpty())
            }

            "prmovies" -> {
                val cleanPath = path.trim('/')
                val pageUrl = if (page <= 1) "$base/$cleanPath/" else "$base/$cleanPath/page/$page/"
                val html = getPage(pageUrl, extraHeaders = prmoviesHeaders())?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val items = extractPsyPlayItems(html, base, "prmovies")
                newHomePageResponse(HomePageList(request.name, items, false), hasNext = items.isNotEmpty())
            }

            "aagmaal" -> {
                val cleanPath = path.trim('/')
                val pageUrl = when {
                    cleanPath.isEmpty() && page <= 1 -> "$base/"
                    cleanPath.isEmpty() -> "$base/page/$page/"
                    page <= 1 -> "$base/$cleanPath/"
                    else -> "$base/$cleanPath/page/$page/"
                }
                val html = getPage(pageUrl)?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("article.loop-video").mapNotNull { it.toAagmaalResult(base) }
                val hasNext = doc.select("link[rel=next]").isNotEmpty() && items.isNotEmpty()
                newHomePageResponse(HomePageList(request.name, items, true), hasNext = hasNext)
            }

            "tellyhd" -> {
                val url = if (page <= 1) "$base/$path/" else "$base/$path/page/$page/"
                val html = getPage(url, extraHeaders = tellyHeaders())?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("div.items.normal article, div#archive-content article, div.items.full article")
                    .mapNotNull { it.toTellyResult(base) }
                val hasNext = doc.selectFirst("a.next, a[rel=next], .pagination a.next, a.page-numbers.next") != null
                newHomePageResponse(request.name, items, hasNext = hasNext)
            }

            "brazzpw" -> {
                val full = "$base$path"
                val pageUrl = if (page <= 1) full else "${full}page/$page/"
                val html = getPage(pageUrl)?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("article.loop-video").mapNotNull { it.toBrazzPwResult(base) }
                newHomePageResponse(HomePageList(request.name, items, true), hasNext = items.isNotEmpty())
            }

            "xmoviesforyou" -> {
                val full = "$base$path"
                val link = if (page <= 1) full else "$full?page=$page"
                val html = getPage(link)?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("a.group.flex.flex-col").mapNotNull { it.toXMoviesResult(base) }
                newHomePageResponse(HomePageList(request.name, items, true), hasNext = items.isNotEmpty())
            }

            "xxxtime" -> {
                val link = "$base$path&page=$page"
                val html = getPage(link)?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("li.pure-u-1-3").mapNotNull { it.toXXXTimeResult(base) }
                newHomePageResponse(HomePageList(request.name, items, true), hasNext = items.isNotEmpty())
            }

            "watchporn" -> {
                val full = "$base$path"
                val url = if (page <= 1) full else "$full$page/"
                val html = getPage(url)?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("div.wp-card").mapNotNull { it.toWatchPornResult(base) }
                newHomePageResponse(HomePageList(request.name, items, true), hasNext = items.isNotEmpty())
            }

            "taboodaddy" -> {
                val cleanBase = "$base/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
                val url = if (page > 1) "${cleanBase}page/$page/" else cleanBase
                val html = getPage(url, referer = "$base/")?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("div.video-block, div.thumb-block, article.loop-video")
                    .mapNotNull { it.toTabooDaddyResult(base) }.distinctBy { it.url }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }

            "pvzija" -> {
                val url = "$base/${path.format(page)}"
                val html = getPage(url, referer = "$base/")?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("article.card").mapNotNull { it.toPvzijaResult(base) }.distinctBy { it.url }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }

            "freepornvideos" -> {
                val url = "$base$path/$page/"
                val html = getPage(url)?.text ?: return newHomePageResponse(request.name, emptyList(), false)
                val doc = Jsoup.parse(html)
                val items = doc.select("div.item").mapNotNull { runCatching { it.toFreePornResult(base) }.getOrNull() }
                newHomePageResponse(HomePageList(request.name, items, true), hasNext = items.isNotEmpty())
            }

            else -> newHomePageResponse(request.name, emptyList(), false)
        }
    }

    // =========================================================================
    // Multi-Server Concurrent Search (All 11 Servers)
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val enabledServers = WatchOMoviesSettingsDialog.getOrderedEnabledServers()
        if (enabledServers.isEmpty()) return newSearchResponseList(emptyList(), false)

        val sem = Semaphore(WatchOMoviesSettingsDialog.getConcurrency())
        val lists = kotlinx.coroutines.coroutineScope {
            enabledServers.map { srv ->
                async {
                    sem.withPermit {
                        try {
                            searchSingleServer(srv.key, query.trim(), page)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
            }.awaitAll()
        }

        val merged = mutableListOf<SearchResponse>()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (list in lists) {
                if (i < list.size) merged.add(list[i])
            }
        }
        val unique = merged.distinctBy { it.url }
        return newSearchResponseList(unique, hasNext = unique.isNotEmpty())
    }

    private suspend fun searchSingleServer(srvKey: String, query: String, page: Int): List<SearchResponse> {
        val base = srvBase(srvKey)
        val encoded = URLEncoder.encode(query, "UTF-8")

        return when (srvKey) {
            "watchomovies" -> {
                val url = if (page <= 1) "$base/?s=$encoded" else "$base/page/$page/?s=$encoded"
                val html = getPage(url)?.text ?: return emptyList()
                extractPsyPlayItems(html, base, "watchomovies")
            }
            "prmovies" -> {
                val qPlus = query.replace(" ", "+")
                val url = if (page <= 1) "$base/?s=$qPlus" else "$base/page/$page/?s=$qPlus"
                val html = getPage(url, extraHeaders = prmoviesHeaders())?.text ?: return emptyList()
                extractPsyPlayItems(html, base, "prmovies", prefixTag = "[Prmovies]")
            }
            "aagmaal" -> {
                val url = "$base/page/$page/?s=$encoded"
                val html = getPage(url)?.text ?: return emptyList()
                Jsoup.parse(html).select("article.loop-video").mapNotNull { it.toAagmaalResult(base, prefixTag = true) }
            }
            "tellyhd" -> {
                val qPlus = encoded.replace("+", "%20")
                val url = if (page <= 1) "$base/search/$qPlus/" else "$base/search/$qPlus/page/$page/"
                val html = getPage(url, extraHeaders = tellyHeaders())?.text ?: return emptyList()
                val doc = Jsoup.parse(html)
                doc.select("div.result-item").mapNotNull { el ->
                    val a = el.selectFirst("div.title > a") ?: return@mapNotNull null
                    val title = a.text().replace(Regex("\\(\\d{4}\\)"), "").trim().ifBlank { return@mapNotNull null }
                    val href = resolveFullUrl(base, a.attr("href")) ?: return@mapNotNull null
                    val poster = el.selectFirst("img")?.let { resolveFullUrl(base, it.attr("data-src").ifBlank { it.attr("src") }) }
                    newMovieSearchResponse("[TellyHD] $title", encodeRoute("tellyhd", getTellyProperLink(base, href)), TvType.NSFW) {
                        this.posterUrl = poster
                    }
                }
            }
            "brazzpw" -> {
                val url = if (page <= 1) "$base/search/?s=$encoded" else "$base/search/page/$page/?s=$encoded"
                val html = getPage(url)?.text ?: return emptyList()
                Jsoup.parse(html).select("article.loop-video").mapNotNull { it.toBrazzPwResult(base, prefixTag = true) }
            }
            "xmoviesforyou" -> {
                val url = if (page <= 1) "$base/search?q=$encoded" else "$base/search?q=$encoded&page=$page"
                val html = getPage(url)?.text ?: return emptyList()
                Jsoup.parse(html).select("a.group.flex.flex-col").mapNotNull { it.toXMoviesResult(base, prefixTag = true) }
            }
            "xxxtime" -> {
                val url = "$base/search.php?s=$encoded&page=$page"
                val html = getPage(url)?.text ?: return emptyList()
                Jsoup.parse(html).select("li.pure-u-1-3").mapNotNull { it.toXXXTimeResult(base, prefixTag = true) }
            }
            "watchporn" -> {
                val url = "$base/search/?q=$encoded&from_videos=$page"
                val html = getPage(url)?.text ?: return emptyList()
                Jsoup.parse(html).select("div.wp-card").mapNotNull { it.toWatchPornResult(base, prefixTag = true) }
            }
            "taboodaddy" -> {
                val qPlus = encoded.replace("+", "%20")
                val url = if (page <= 1) "$base/?s=$qPlus" else "$base/page/$page/?s=$qPlus"
                val html = getPage(url, referer = "$base/")?.text ?: return emptyList()
                Jsoup.parse(html).select("div.video-block, div.thumb-block, article.loop-video")
                    .mapNotNull { it.toTabooDaddyResult(base, prefixTag = true) }.distinctBy { it.url }
            }
            "pvzija" -> {
                val url = if (page <= 1) "$base/?s=$encoded" else "$base/page/$page/?s=$encoded"
                val html = getPage(url, referer = "$base/")?.text ?: return emptyList()
                Jsoup.parse(html).select("article.card").mapNotNull { it.toPvzijaResult(base, prefixTag = true) }
            }
            "freepornvideos" -> {
                val slug = query.filter { it.isWhitespace() || it.isLetterOrDigit() }
                    .trim().replace("\\s+".toRegex(), "-").lowercase()
                val url = "$base/search/$slug/$page/"
                val html = getPage(url)?.text ?: return emptyList()
                Jsoup.parse(html).select("#custom_list_videos_videos_list_search_result_items > div.item, div.item")
                    .mapNotNull { runCatching { it.toFreePornResult(base, prefixTag = true) }.getOrNull() }
            }
            else -> emptyList()
        }
    }

    // =========================================================================
    // Unified Detail Load Router
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        val (srvKey, realRaw) = decodeRoute(url)
        val base = srvBase(srvKey)

        return when (srvKey) {
            "watchomovies" -> loadPsyPlay(url, realRaw, base, "watchomovies", emptyMap())
            "prmovies" -> loadPsyPlay(url, realRaw, base, "prmovies", prmoviesHeaders())
            "aagmaal" -> loadAagmaal(url, realRaw, base)
            "tellyhd" -> loadTellyHd(url, realRaw, base)
            "brazzpw" -> loadBrazzPw(url, realRaw, base)
            "xmoviesforyou" -> loadXMoviesForYou(url, realRaw, base)
            "xxxtime" -> loadXXXTime(url, realRaw, base)
            "watchporn" -> loadWatchPorn(url, realRaw, base)
            "taboodaddy" -> loadTabooDaddy(url, realRaw, base)
            "pvzija" -> loadPvzija(url, realRaw, base)
            "freepornvideos" -> loadFreePornVideos(url, realRaw, base)
            else -> loadPsyPlay(url, realRaw, base, "watchomovies", emptyMap())
        }
    }

    // =========================================================================
    // Unified Link Extraction Router
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (srvKey, realData) = decodeRoute(data)
        val base = srvBase(srvKey)

        return when (srvKey) {
            "watchomovies" -> loadLinksPsyPlay(realData, base, "WatchOMovies", emptyMap(), subtitleCallback, callback)
            "prmovies" -> loadLinksPsyPlay(realData, base, "Prmovies", prmoviesHeaders(), subtitleCallback, callback)
            "aagmaal" -> loadLinksAagmaal(realData, subtitleCallback, callback)
            "tellyhd" -> loadLinksTellyHd(realData, base, subtitleCallback, callback)
            "brazzpw" -> loadLinksBrazzPw(realData, base, subtitleCallback, callback)
            "xmoviesforyou" -> loadLinksXMoviesForYou(realData, base, subtitleCallback, callback)
            "xxxtime" -> loadLinksXXXTime(realData, base, subtitleCallback, callback)
            "watchporn" -> loadLinksWatchPorn(realData, base, subtitleCallback, callback)
            "taboodaddy" -> loadLinksTabooDaddy(realData, base, subtitleCallback, callback)
            "pvzija" -> loadLinksPvzija(realData, base, subtitleCallback, callback)
            "freepornvideos" -> loadLinksFreePornVideos(realData, base, subtitleCallback, callback)
            else -> false
        }
    }

    // =========================================================================
    // URL & Card Helpers Per Server
    // =========================================================================
    private fun resolveFullUrl(base: String, raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank() || s.startsWith("data:", true)) return null
        return when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "${base.trimEnd('/')}$s"
            else -> "${base.trimEnd('/')}/$s"
        }
    }

    private fun prmoviesHeaders(): Map<String, String> = mapOf(
        "Cookie" to "_ga=GA1.1.1629448397.1794059276; domain-alert=1; fpestid=qKEJFcR_c3RLFa6Kn--eeRQW2dShUUkgk5lB1Hx76DaGCwYwnn5wDmCy7qWmMUlFPnD5Zw; _ga_7BWGJ9MXSS=GS2.1.s1794059275\$o1\$g1\$t1794059680\$j60\$l0\$h0"
    )

    private fun extractPsyPlayItems(html: String, base: String, srvKey: String, prefixTag: String? = null): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        for (card in doc.select(".ml-item")) {
            val a = card.selectFirst("a.ml-mask") ?: card.selectFirst("a[href]") ?: continue
            val url = resolveFullUrl(base, a.attr("href")) ?: continue
            val rawTitle = (a.attr("oldtitle").takeIf { it.isNotBlank() }
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: card.selectFirst(".mli-info h2, h2, h3")?.text()
                ?: card.selectFirst("img")?.attr("alt"))?.trim() ?: ""
            if (rawTitle.isBlank()) continue
            val cleanTitle = fixTitle(rawTitle).trim()
            val title = if (prefixTag != null) "$prefixTag $cleanTitle" else cleanTitle

            val thumb = card.selectFirst("img.mli-thumb") ?: card.selectFirst("img")
            val poster = resolveFullUrl(
                base,
                thumb?.attr("data-original")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("src")?.takeIf { it.isNotBlank() }
            )

            val isSeries = url.contains("/series/", true) ||
                Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(cleanTitle)

            val route = encodeRoute(srvKey, url)
            items.add(
                if (isSeries) newTvSeriesSearchResponse(title, route, TvType.TvSeries) { this.posterUrl = poster }
                else newMovieSearchResponse(title, route, TvType.Movie) { this.posterUrl = poster }
            )
        }

        if (items.isEmpty()) {
            for (card in doc.select("article, div.item, div.post, .result-item")) {
                val a = card.selectFirst("a[href]") ?: continue
                val url = resolveFullUrl(base, a.attr("href")) ?: continue
                val rawTitle = (card.selectFirst(".title, h2, h3, h4, .name")?.text()
                    ?: a.attr("title").takeIf { it.isNotBlank() } ?: a.text()).trim()
                if (rawTitle.isBlank()) continue
                val cleanTitle = fixTitle(rawTitle).trim()
                val title = if (prefixTag != null) "$prefixTag $cleanTitle" else cleanTitle
                val img = card.selectFirst("img")
                val poster = resolveFullUrl(
                    base,
                    img?.attr("data-original")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                )
                val route = encodeRoute(srvKey, url)
                items.add(newMovieSearchResponse(title, route, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return items.distinctBy { it.url }
    }

    private fun Element.toAagmaalResult(base: String, prefixTag: Boolean = false): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val url = resolveFullUrl(base, anchor.attr("href")) ?: return null
        val rawTitle = anchor.attr("title").takeIf { it.isNotBlank() } ?: anchor.text()
        if (rawTitle.isBlank()) return null
        val clean = fixTitle(rawTitle).trim()
        val title = if (prefixTag) "[Aagmaal] $clean" else clean
        val img = selectFirst("img")
        val imageUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        return newMovieSearchResponse(title, encodeRoute("aagmaal", url), TvType.NSFW) {
            this.posterUrl = resolveFullUrl(base, imageUrl)
        }
    }

    private fun tellyHeaders(): Map<String, String> = mapOf(
        "Cookie" to "starstruck_4289ff7218d92faf1c44febfd9fb83db=870407aa807d1633f1b22a1e380ec2e2"
    )

    private fun getTellyProperLink(base: String, uri: String): String {
        val path = uri.substringBefore("?")
        return when {
            path.contains("/episodes/") -> {
                val slug = path.substringAfter("/episodes/").substringBefore("/")
                val showSlug = slug.substringBefore("-season").ifBlank { slug }
                "$base/tvshows/$showSlug/"
            }
            path.contains("/seasons/") -> {
                val slug = path.substringAfter("/seasons/").substringBefore("/")
                val showSlug = slug.substringBefore("-season").ifBlank { slug }
                "$base/tvshows/$showSlug/"
            }
            else -> uri
        }
    }

    private fun Element.toTellyResult(base: String): SearchResponse? {
        val anchor = selectFirst("h3 > a") ?: return null
        val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return null
        val rawUrl = resolveFullUrl(base, anchor.attr("href")) ?: return null
        val href = getTellyProperLink(base, rawUrl)

        val image = select("div.poster img").lastOrNull()
        var posterUrl = listOf("data-wpfc-original-src", "data-src", "data-lazy-src", "src")
            .mapNotNull { image?.attr(it)?.trim()?.takeIf { v -> v.isNotBlank() && !v.startsWith("data:image", true) } }
            .firstOrNull()
        if (posterUrl?.contains(".gif", true) == true) {
            posterUrl = image?.attr("data-wpfc-original-src")?.takeIf { it.isNotBlank() } ?: posterUrl
        }
        posterUrl = resolveFullUrl(base, posterUrl)
        val quality = getQualityFromString(select("span.quality").text())

        return newMovieSearchResponse(title, encodeRoute("tellyhd", href), TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    private fun Element.toBrazzPwResult(base: String, prefixTag: Boolean = false): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val href = resolveFullUrl(base, link.attr("href")) ?: return null
        val header = selectFirst("header.entry-header span")?.text()?.substringAfter(":")?.trim()
        val rawTitle = header?.ifEmpty { null } ?: link.attr("title").ifEmpty { null } ?: selectFirst("img")?.attr("alt")?.ifEmpty { null } ?: return null
        val title = if (prefixTag) "[BrazzPW] $rawTitle" else rawTitle
        val img = selectFirst("img")
        val posterUrl = resolveFullUrl(base, img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("src")?.ifEmpty { null })

        return newMovieSearchResponse(title, encodeRoute("brazzpw", href), TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toXMoviesResult(base: String, prefixTag: Boolean = false): SearchResponse? {
        val rawTitle = selectFirst("h3")?.text()?.trim() ?: return null
        val cleanTitle = rawTitle.replace(Regex("""\[.*?\]"""), "").trim()
        val title = if (prefixTag) "[XMovies] $cleanTitle" else cleanTitle
        val adres = resolveFullUrl(base, attr("href")) ?: return null
        val afis = resolveFullUrl(base, selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, encodeRoute("xmoviesforyou", adres), TvType.NSFW) {
            this.posterUrl = afis
        }
    }

    private fun Element.toXXXTimeResult(base: String, prefixTag: Boolean = false): SearchResponse? {
        val rawTitle = selectFirst("h3")?.text()?.trim() ?: return null
        val title = if (prefixTag) "[XXXTime] $rawTitle" else rawTitle
        val href = resolveFullUrl(base, selectFirst("a.video-thumb")?.attr("href")) ?: return null
        val img = selectFirst("a.video-thumb img")
        val poster = resolveFullUrl(base, img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))

        return newMovieSearchResponse(title, encodeRoute("xxxtime", href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun Element.toWatchPornResult(base: String, prefixTag: Boolean = false): SearchResponse? {
        val rawTitle = selectFirst("a.wp-card__link")?.attr("title")?.trim()?.ifEmpty { null } ?: return null
        val title = if (prefixTag) "[WatchPorn] $rawTitle" else rawTitle
        val href = resolveFullUrl(base, selectFirst("a.wp-card__link")?.attr("href")) ?: return null
        val img = selectFirst("img.thumb")
        val posterUrl = resolveFullUrl(base, img?.attr("data-webp")?.ifEmpty { null } ?: img?.attr("src"))
        val rating = selectFirst("span.wp-r")?.text()?.replace("%", "")?.trim()

        return newMovieSearchResponse(title, encodeRoute("watchporn", "$href|$posterUrl"), TvType.NSFW) {
            this.posterUrl = posterUrl
            this.score = Score.from100(rating)
        }
    }

    private fun Element.toTabooDaddyResult(base: String, prefixTag: Boolean = false): SearchResponse? {
        val a = selectFirst("a.thumb[href], a[href]") ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank() || href.contains("/category/") || href.contains("/page/") || href.contains("/tag/") || href.contains("?s=") || href.contains("/feed")) return null
        val rawTitle = selectFirst(".title, h2, h3, .entry-title")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null } ?: return null
        val title = if (prefixTag) "[TabooDaddy] $rawTitle" else rawTitle
        val fullUrl = resolveFullUrl(base, href) ?: return null
        val img = selectFirst("img[data-src], img[src], img[data-lazy-src]")
        val poster = resolveFullUrl(base, img?.attr("data-src")?.ifBlank { null } ?: img?.attr("data-lazy-src")?.ifBlank { null } ?: img?.attr("src"))

        return newMovieSearchResponse(title, encodeRoute("taboodaddy", fullUrl), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun Element.toPvzijaResult(base: String, prefixTag: Boolean = false): SearchResponse? {
        val href = resolveFullUrl(base, selectFirst("a[href]")?.attr("href")) ?: return null
        val rawTitle = selectFirst("div.card__b h2")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("a img")?.attr("alt")?.trim()?.ifBlank { null } ?: return null
        val title = if (prefixTag) "[Pvzija] $rawTitle" else rawTitle
        val i = selectFirst("a img") ?: selectFirst("img")
        val src = listOf("data-lazy-src", "data-src", "src").mapNotNull { i?.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        val poster = resolveFullUrl(base, src)

        return newMovieSearchResponse(title, encodeRoute("pvzija", href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun Element.toFreePornResult(base: String, prefixTag: Boolean = false): SearchResponse? {
        if (selectFirst("div.img iframe") != null) return null
        val linkElement = selectFirst("a.thumb_title") ?: return null
        val href = resolveFullUrl(base, linkElement.attr("href")) ?: return null
        val rawTitle = linkElement.selectFirst("strong.title")?.text()?.takeIf { it.isNotBlank() } ?: return null
        val title = if (prefixTag) "[FPV] $rawTitle" else rawTitle

        var posterUrl: String? = null
        for (img in select("img.thumb")) {
            img.attr("data-src").takeIf { it.isNotBlank() }?.let { posterUrl = resolveFullUrl(base, it); break }
            img.attr("src").takeIf { it.isNotBlank() && !it.contains("data:image") }?.let { posterUrl = resolveFullUrl(base, it); break }
        }
        if (posterUrl == null) return null
        return newMovieSearchResponse(title, encodeRoute("freepornvideos", href), TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // =========================================================================
    // Individual Server Load Implementations
    // =========================================================================
    private suspend fun loadPsyPlay(
        routeUrl: String,
        url: String,
        base: String,
        srvKey: String,
        extraHeaders: Map<String, String>
    ): LoadResponse {
        val html = getPage(url, extraHeaders = extraHeaders)?.text ?: throw ErrorLoadingException("Failed to load $srvKey page")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h3[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        val poster = resolveFullUrl(
            base,
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?: doc.selectFirst("div.mvic-thumb")?.attr("style")?.let { style ->
                    Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
                }
                ?: doc.selectFirst(".poster img, .dt_poster img")?.attr("src")
        )
        val plot = doc.selectFirst("p.f-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""
        val year = doc.select("a[href*=/release-year/]").firstOrNull()?.text()?.trim()
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.groupValues?.get(0)?.toIntOrNull()
        val tags = doc.select(".mvici-left a[href*=/genre/], .tags a, .genres a, a[href*='/tag/'], a[href*='/director/']")
            .mapNotNull { it.text().trim().ifBlank { null } }.distinct()
        val duration = doc.selectFirst(".mvici-right span[itemprop=duration]")?.text()?.trim()
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val recommendations = extractPsyPlayItems(html, base, srvKey).filter { it.url != routeUrl }

        if (url.contains("/series/", true) || doc.select("#seasons .tvseason, .les-content a").isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            for (tvseason in doc.select("#seasons .tvseason")) {
                val seasonTitle = tvseason.selectFirst(".les-title strong")?.text() ?: "Season 1"
                val season = Regex("""(?i)\bseason\s*(\d+)""").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                for (a in tvseason.select(".les-content a[href]")) {
                    val href = resolveFullUrl(base, a.attr("href")) ?: continue
                    val label = a.text().trim()
                    val epNum = Regex("""(?i)\bepisode\s*[-:]?\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(
                        newEpisode(encodeRoute(srvKey, href)) {
                            this.name = label.ifBlank { "Episode ${episodes.size + 1}" }
                            this.season = season
                            this.episode = epNum ?: (episodes.size + 1)
                        }
                    )
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, routeUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags; this.duration = duration; this.recommendations = recommendations
                }
            }
        }

        val isSeries = Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(title)
        if (isSeries) {
            return newTvSeriesLoadResponse(
                title,
                routeUrl,
                TvType.TvSeries,
                listOf(newEpisode(routeUrl) { this.name = title; this.season = 1; this.episode = 1 })
            ) {
                this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags; this.duration = duration; this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, routeUrl, TvType.Movie, routeUrl) {
            this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags; this.duration = duration; this.recommendations = recommendations
        }
    }

    private suspend fun loadAagmaal(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load Aagmaal page")
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim() ?: "Aagmaal Video"
        val poster = resolveFullUrl(base, doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val streamLink = doc.selectFirst("iframe[data-litespeed-src]")?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: url

        return newMovieLoadResponse(title, routeUrl, TvType.NSFW, encodeRoute("aagmaal", streamLink)) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    data class TellyLinkData(val type: String? = null, val post: String? = null, val nume: String? = null)
    data class TellyResponseHash(@JsonProperty("embed_url") val embedUrl: String? = null, val type: String? = null)

    private suspend fun loadTellyHd(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url, extraHeaders = tellyHeaders())?.text ?: throw ErrorLoadingException("Failed to load TellyHD")
        val document = Jsoup.parse(html)

        val title = document.selectFirst("div.data > h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: "TellyHD Video"
        var posterUrl = document.selectFirst("div.poster img")?.let { resolveFullUrl(base, it.attr("data-src").ifBlank { it.attr("src") }) }
        if (posterUrl.isNullOrEmpty()) posterUrl = resolveFullUrl(base, document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("div[itemprop=description] p, div.wp-content p")?.text()?.trim()
        val year = document.selectFirst("span.date")?.text()?.substringAfterLast(", ")?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }.filter { it.isNotBlank() }

        val episodes = document.select(
            "ul#playeroptionsul > li, div.play-options li, .sources-box li, .dooplay_player_option, .options li"
        ).mapNotNull { element ->
            val epName = element.selectFirst("span.title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: element.select("span").text().trim().ifBlank { element.text().trim() }
            val type = element.attr("data-type").ifBlank { element.attr("data-film") }.ifBlank { "movie" }
            val post = element.attr("data-post").ifBlank { element.attr("data-id") }.ifBlank { element.attr("data-movie") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val nume = element.attr("data-nume").ifBlank { element.attr("data-episode") }.ifBlank { element.attr("data-seq") }.ifBlank { "1" }
            if (epName.contains("trailer", true)) return@mapNotNull null

            val json = TellyLinkData(type = type, post = post, nume = nume).toJson()
            newEpisode(encodeRoute("tellyhd", json)) {
                this.name = epName.ifBlank { "Server" }
            }
        }.distinctBy { it.name }

        return newTvSeriesLoadResponse(title, routeUrl, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl; this.plot = description; this.year = year; this.tags = tags
        }
    }

    private suspend fun loadBrazzPw(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load BrazzPW")
        val document = Jsoup.parse(html)
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "BrazzPW Video"
        val poster = resolveFullUrl(base, document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content"))
        val description = document.selectFirst("div.video-description p")?.text()?.trim()
        val tags = document.select("div.tags-list a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select("div#video-actors a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val iframe = resolveFullUrl(base, document.selectFirst("iframe[name=player]")?.attr("src"))

        return newMovieLoadResponse(title, routeUrl, TvType.NSFW, encodeRoute("brazzpw", iframe ?: url)) {
            this.posterUrl = poster; this.plot = description; this.tags = tags; addActors(actors)
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XMoviesRelatedData(val data: List<XMoviesRelatedItem>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XMoviesRelatedItem(val title: String? = null, val slug: String? = null, val thumbnail_url: String? = null)

    private suspend fun loadXMoviesForYou(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load XMoviesForYou")
        val sayfa = Jsoup.parse(html)
        val baslik = sayfa.selectFirst("h1")?.text()?.trim() ?: "XMoviesForYou Video"
        val afis = resolveFullUrl(base, sayfa.selectFirst("#player img")?.attr("src") ?: sayfa.selectFirst("meta[property='og:image']")?.attr("content"))
        val ozet = sayfa.selectFirst("div.prose p")?.text()?.trim()
        val etiketler = sayfa.select("a[href*='/category/'], a[href*='/studio/']").map {
            it.text().replace("category", "", true).replace("studio", "", true).trim()
        }
        val oyuncular = sayfa.select("a[href*='/pornstar/']").map { Actor(it.text().trim(), null) }

        val postId = html.substringAfter("const postId = \"", "").substringBefore("\"")
        val oneriler = if (postId.length > 3) {
            runCatching {
                val apiCevap = getPage("$base/api/related/$postId", referer = url)?.text.orEmpty()
                tryParseJson<XMoviesRelatedData>(apiCevap)?.data?.mapNotNull { item ->
                    val slugUrl = resolveFullUrl(base, item.slug) ?: return@mapNotNull null
                    newMovieSearchResponse(item.title ?: return@mapNotNull null, encodeRoute("xmoviesforyou", slugUrl), TvType.NSFW) {
                        this.posterUrl = item.thumbnail_url
                    }
                }
            }.getOrNull()
        } else null

        return newMovieLoadResponse(baslik, routeUrl, TvType.NSFW, routeUrl) {
            this.posterUrl = afis; this.plot = ozet; this.tags = etiketler; this.recommendations = oneriler; addActors(oyuncular)
        }
    }

    private suspend fun loadXXXTime(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load XXXTime")
        val document = Jsoup.parse(html)
        val title = document.selectFirst("h1")?.text()?.trim() ?: "XXXTime Video"
        val poster = resolveFullUrl(base, document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags = document.select("div.pure-u-1-5 p a[href*=categories]").map { it.text() }
        val actors = document.select("div.pure-u-1-5 p a[href*=acctress]").map { Actor(it.text()) }
        val trailer = Regex("""embed/(.*)\?rel""").find(html)?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        return newMovieLoadResponse(title, routeUrl, TvType.NSFW, routeUrl) {
            this.posterUrl = poster; this.plot = description; this.tags = tags; addActors(actors); addTrailer(trailer)
        }
    }

    private suspend fun loadWatchPorn(routeUrl: String, rawData: String, base: String): LoadResponse {
        val (url, storedPoster) = rawData.split("|").let { it[0] to it.getOrNull(1) }
        val resp = getPage(url) ?: throw ErrorLoadingException("Failed to load WatchPorn")
        val document = Jsoup.parse(resp.text)
        val title = document.selectFirst("h1.wp-vi__title")?.text()?.trim() ?: "WatchPorn Video"
        val poster = storedPoster ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.wp-chips a, div.wp-tags a").map { it.text().trim() }
        val actors = document.select("div.wp-castrow a.wp-cast span.wp-cast__n").map { Actor(it.text().trim()) }

        return newMovieLoadResponse(title, routeUrl, TvType.NSFW, encodeRoute("watchporn", url)) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("User-Agent" to userAgent, "Referer" to "$base/", "Cookie" to resp.cookies.toString())
            this.tags = tags
            addActors(actors)
        }
    }

    private suspend fun loadTabooDaddy(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url, referer = "$base/")?.text ?: throw ErrorLoadingException("Failed to load Taboodaddy")
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: "Taboodaddy Video"
        val poster = doc.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[itemprop=description]")?.attr("content")?.ifBlank { null }
        val tags = doc.select("a[rel=tag], a[href*=/tag/], [class*=categor] a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        return newMovieLoadResponse(title, routeUrl, TvType.NSFW, routeUrl) {
            this.posterUrl = poster; this.plot = plot; this.tags = tags
        }
    }

    private suspend fun loadPvzija(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url, referer = "$base/")?.text ?: throw ErrorLoadingException("Failed to load Pvzija")
        val d = Jsoup.parse(html)
        val title = d.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: d.selectFirst("h1")?.text()?.trim() ?: "Pvzija Video"
        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = d.selectFirst("div.desc>p")?.text()?.trim()
        fun Element.chips(label: String) =
            select("div:contains($label)>div.row__v>div.chips>a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val body = d.body()
        val tags = body.chips("Genres") + body.chips("Studio")
        val actors = body.chips("Pornstars").map { ActorData(Actor(it)) }

        return newMovieLoadResponse(title, routeUrl, TvType.NSFW, routeUrl) {
            this.posterUrl = poster; this.plot = plot; this.tags = tags; this.actors = actors
        }
    }

    private suspend fun loadFreePornVideos(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load FreePornVideos")
        val document = Jsoup.parse(html)
        val fullTitle = document.selectFirst("div.headline > h1")?.text()?.trim().orEmpty()
        val lastIdx = fullTitle.lastIndexOf(" - ")
        val title = (if (lastIdx != -1) fullTitle.substring(0, lastIdx) else fullTitle).removePrefix("- ").removeSuffix("-").trim().ifBlank { "FPV Video" }
        val poster = resolveFullUrl(base, document.selectFirst("[property='og:image']")?.attr("content"))
        val tags = document.selectXpath("//div[contains(text(), 'Categories:')]/a").map { it.text() }
        val description = document.selectXpath("//div[contains(text(), 'Description:')]/em").text().trim()
        val actors = document.selectXpath("//div[contains(text(), 'Models:')]/a").map { it.text() }

        return newMovieLoadResponse(title, routeUrl, TvType.NSFW, routeUrl) {
            this.posterUrl = poster; this.plot = description; this.tags = tags; addActors(actors)
        }
    }

    // =========================================================================
    // Individual Server LoadLinks Implementations
    // =========================================================================
    private suspend fun loadLinksPsyPlay(
        data: String,
        base: String,
        sourceLabel: String,
        extraHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val pageUrl = data.substringBefore("#").substringBefore("?")
        val html = getPage(pageUrl, extraHeaders = extraHeaders)?.text ?: return false
        val doc = Jsoup.parse(html)

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
            val label = tabLabels[tabId] ?: sourceLabel

            when {
                src.contains("speedostream", true) && src.contains("embed-") -> {
                    if (resolveSpeedostream(src, base, sourceLabel, label, extraHeaders, callback)) found = true
                }
                src.contains("vidsrc", true) -> {
                    if (resolveVidsrc(src, base, sourceLabel, pageUrl, extraHeaders, callback)) found = true
                }
                else -> {
                    if (loadExtractor(resolveFullUrl(base, src) ?: src, pageUrl, subtitleCallback, callback)) found = true
                }
            }
        }

        if (!found) {
            for (a in doc.select("#list-downloads a[href*=speedostream], .links_table a[href*=speedostream]")) {
                val href = a.attr("href")
                if (href.isBlank() || href.contains("embed-")) continue
                val label = a.selectFirst(".serv_tit")?.text()?.ifBlank { null } ?: sourceLabel
                if (resolveSpeedostream(href, base, sourceLabel, "$label • Download", extraHeaders, callback)) found = true
            }
        }
        return found
    }

    private suspend fun resolveSpeedostream(
        embedUrl: String,
        base: String,
        sourceLabel: String,
        label: String,
        extraHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("speedostream")) return false
        return try {
            val clean = resolveFullUrl(base, embedUrl) ?: return false
            val host = try { Uri.parse(clean).host ?: "speedostream1.com" } catch (e: Exception) { "speedostream1.com" }
            val r = getPage(clean, referer = "https://$host/", extraHeaders = extraHeaders) ?: return false
            val text = r.text
            if (text.contains("Embeds disabled", true)) return false
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
                ?: return false

            callback(
                newExtractorLink(sourceLabel, label.take(40), m3u8, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://$host/"
                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://$host/")
                }
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveVidsrc(
        embedUrl: String,
        base: String,
        sourceLabel: String,
        referer: String,
        extraHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("speedostream")) return false
        return try {
            val clean = resolveFullUrl(base, embedUrl) ?: return false
            val r1 = getPage(clean, referer = referer, extraHeaders = extraHeaders) ?: return false
            val target = Jsoup.parse(r1.text).selectFirst("iframe[src]")?.attr("src")?.let { resolveFullUrl(clean, it) } ?: return false
            val r2 = getPage(target, referer = clean, extraHeaders = extraHeaders) ?: return false
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(r2.text)?.groupValues?.get(1)
                ?: Regex("""https:[^"' ]+\.m3u8[^"' ]*""").find(r2.text)?.groupValues?.get(0) ?: return false

            callback(
                newExtractorLink(sourceLabel, "$sourceLabel • VidSrc", m3u8, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = target
                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to target)
                }
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadLinksAagmaal(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val host = data.substringAfter("//").substringBefore("/")
        if (host.contains("vidhost", true)) {
            if (VidhostExtractor.resolve("Aagmaal", data, callback)) return true
        }
        return loadExtractor(data, referer = data, subtitleCallback, callback)
    }

    private suspend fun loadLinksTellyHd(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<TellyLinkData>(data) ?: return false
        val post = loadData.post?.takeIf { it.isNotBlank() } ?: return false
        val type = loadData.type?.takeIf { it.isNotBlank() } ?: "movie"
        val nume = loadData.nume?.takeIf { it.isNotBlank() } ?: "1"

        val apiUrls = listOf(
            "$base/wp-json/dooplayer/v2/$post/$type/$nume",
            "$base/wp-json/dooplayer/v1/$post/$type/$nume",
            "$base/wp-admin/admin-ajax.php?action=doo_player_ajax&post=$post&type=$type&nume=$nume"
        )

        var iframe: String? = null
        val ajaxHeaders = tellyHeaders() + mapOf("X-Requested-With" to "XMLHttpRequest")
        for (apiUrl in apiUrls) {
            val resp = getPage(apiUrl, referer = "$base/", extraHeaders = ajaxHeaders)?.parsedSafe<TellyResponseHash>()
            val raw = resp?.embedUrl?.trim().orEmpty()
            if (raw.isNotBlank()) {
                val parsedSrc = Jsoup.parse(raw).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() } ?: raw
                iframe = resolveFullUrl(base, parsedSrc)
                if (!iframe.isNullOrBlank()) break
            }
        }
        if (iframe.isNullOrBlank()) return false

        if (iframe.contains("telly", true) || iframe.contains("cdntelly", true)) {
            val pHtml = getPage(iframe, referer = "$base/", extraHeaders = tellyHeaders())?.text ?: return false
            val pDoc = Jsoup.parse(pHtml)
            val rawStream = pDoc.selectFirst("script#player-loader")?.attr("data-stream-url")?.takeIf { it.isNotBlank() }
                ?: pDoc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
            if (!rawStream.isNullOrBlank()) {
                val streamUrl = runCatching { URL(URL(iframe), rawStream).toString() }.getOrDefault(rawStream)
                if (streamUrl.startsWith("http", true)) {
                    callback(
                        newExtractorLink("TellyHD", "TellyHD Indian", streamUrl, ExtractorLinkType.M3U8) {
                            this.referer = iframe
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to userAgent, "Accept" to "*/*", "Referer" to "$base/")
                        }
                    )
                    return true
                }
            }
        }
        return if (!iframe.contains("youtube", true)) {
            loadExtractor(iframe, "$base/", subtitleCallback, callback)
        } else false
    }

    private suspend fun loadLinksBrazzPw(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = if (data.contains("/player/")) data else resolveFullUrl(
            base,
            getPage(data)?.text?.let { Jsoup.parse(it).selectFirst("iframe[name=player]")?.attr("src") }
        ) ?: return false
        val pageText = getPage(playerUrl, referer = "$base/")?.text ?: return false

        Regex("""file:\s*"([^"]+\.(?:vtt|srt))"""").findAll(pageText).forEach { match ->
            resolveFullUrl(base, match.groupValues[1])?.let { subtitleCallback(SubtitleFile("English", it)) }
        }
        val m3u8Path = Regex("""src:\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)?.ifEmpty { null } ?: return false
        val m3u8Url = when {
            m3u8Path.startsWith("http") -> m3u8Path
            m3u8Path.startsWith("/") -> "$base$m3u8Path"
            else -> "${playerUrl.substringBeforeLast("/")}/$m3u8Path"
        }
        callback(
            newExtractorLink("BrazzPW", "BrazzPW", m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = playerUrl
            }
        )
        return true
    }

    private suspend fun loadLinksXMoviesForYou(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getPage(data)?.text ?: return false
        val sayfa = Jsoup.parse(html)
        var found = false
        sayfa.select("a[href*='streamtape.com'], a[href*='mixdrop'], a[href*='dood'], a[href*='bigwarp']").forEach { el ->
            val link = el.attr("href")
            if (link.isNotEmpty() && loadExtractor(link, data, subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun loadLinksXXXTime(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getPage(data)?.text ?: return false
        var found = false
        Jsoup.parse(html).select("div.playerplace iframe").forEach { video ->
            val link = video.attr("src")
            if (link.isNotBlank() && loadExtractor(link, "$base/", subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun loadLinksWatchPorn(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = getPage(data) ?: return false
        val pageHtml = resp.text
        Regex("""file:\s*"([^"]+\.(?:vtt|srt))"""").findAll(pageHtml).forEach { match ->
            resolveFullUrl(base, match.groupValues[1])?.let { subtitleCallback(SubtitleFile("English", it)) }
        }
        return KtPlayerExtractor.getLinks(
            sourceName = "WatchPorn",
            mainUrl = base,
            pageUrl = data,
            pageHtml = pageHtml,
            cookieHeaders = resp.cookies,
            callback = callback
        )
    }

    private suspend fun loadLinksTabooDaddy(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getPage(data, referer = "$base/")?.text ?: return false
        val doc = Jsoup.parse(html)
        val candidates = LinkedHashSet<String>()

        doc.selectFirst("meta[itemprop=contentUrl]")?.attr("content")?.replace("&amp;", "&")?.trim()
            ?.takeIf { it.startsWith("http") }?.let { candidates.add(it) }

        doc.select("iframe[src]").forEach { frame ->
            val src = frame.attr("src").replace("&amp;", "&")
            Regex("[?&]q=([^&]+)").find(src)?.groupValues?.get(1)?.let { q ->
                runCatching {
                    val decoded = URLDecoder.decode(q, "UTF-8").replace(' ', '+').replace('-', '+').replace('_', '/').trimEnd('=')
                    val padded = decoded + "=".repeat((4 - decoded.length % 4) % 4)
                    val tag = URLDecoder.decode(String(Base64.decode(padded, Base64.NO_WRAP), Charsets.UTF_8), "UTF-8")
                    Regex("""src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(tag)?.groupValues?.get(1)?.replace("&amp;", "&")
                }.getOrNull()?.let { candidates.add(it) }
            }
        }

        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .filter { !it.contains("doubleclick", true) && !it.contains("exoclick", true) }
            .forEach { candidates.add(it) }

        val links = candidates.filter { it.startsWith("http") }
        if (links.isEmpty()) return false

        links.forEachIndexed { idx, u ->
            callback(
                newExtractorLink(
                    source = "Taboodaddy",
                    name = if (idx == 0) "Taboodaddy (HLS)" else "Taboodaddy (${idx + 1})",
                    url = u,
                    type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to userAgent, "Origin" to base, "Accept" to "*/*")
                }
            )
        }
        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PvzijaServer(val u: String? = null, val l: String? = null)

    private suspend fun loadLinksPvzija(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getPage(data, referer = "$base/")?.text ?: return false
        val raw = Jsoup.parse(html).selectFirst("section.hlm[data-servers]")?.attr("data-servers") ?: return false
        val servers = runCatching { parseJson<List<PvzijaServer>>(raw) }.getOrNull().orEmpty()
            .mapNotNull { s -> s.u?.replace("\\/", "/")?.trim()?.takeIf { it.startsWith("http") }?.let { it to s.l } }
            .distinctBy { it.first }

        var found = false
        for ((url, label) in servers) {
            if (runCatching { loadExtractor(url, "$base/", subtitleCallback, callback) }.getOrDefault(false)) {
                found = true
                continue
            }
            val media = runCatching {
                app.get(
                    url,
                    referer = "$base/",
                    interceptor = WebViewResolver(Regex("""\.m3u8|\.mp4(\?|$)|/stream/""")),
                    timeout = 30_000L
                ).url
            }.getOrNull()
            if (media != null && media != url && (media.contains(".m3u8") || media.contains(".mp4") || media.contains("/stream/"))) {
                val host = label?.ifBlank { null } ?: url.substringAfter("://").substringBefore("/")
                callback(
                    newExtractorLink(
                        source = "Pvzija",
                        name = "Pvzija - $host",
                        url = media,
                        type = if (media.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/") + "/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }

    private suspend fun loadLinksFreePornVideos(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getPage(data)?.text ?: return false
        var found = false
        Jsoup.parse(html).select("video source").forEach { res ->
            val redirectUrl = res.attr("src")
            val quality = res.attr("label")
            runCatching {
                val finalUrl = app.get(redirectUrl, allowRedirects = true).url
                callback(
                    newExtractorLink(
                        source = "FPV",
                        name = "FreePornVideos",
                        url = finalUrl,
                        type = if (finalUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = getQualityFromName(quality)
                    }
                )
                found = true
            }
        }
        return found
    }
}

