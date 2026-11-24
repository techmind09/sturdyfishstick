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

    /**
     * Valid https:// URL route so CloudStream's fixUrl() NEVER prepends mainUrl!
     */
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
    // Dynamic MainPage Categories Across All 9 Enabled Servers
    // =========================================================================
    private val serverCategoryMap: Map<String, List<Pair<String, String>>> by lazy {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        mapOf(
            // 1. WatchOMovies (55 Categories)
            "watchomovies" to listOf(
                "" to "Latest Updates",
                "movies" to "Movies",
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
                "director/worldprime" to "Worldprime",
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
                "genre/mylf" to "MYLF"
            ),

            // 2. TellyHD Indian (53 Categories)
            "tellyhd" to listOf(
                "release/$currentYear" to "Latest ($currentYear)",
                "genre/18plus" to "18+",
                "genre/altbalaji" to "Alt Balaji",
                "genre/atrangii" to "Atrangii",
                "genre/primeplay" to "PrimePlay",
                "genre/moodx" to "MoodX",
                "genre/neonx" to "NeonX",
                "genre/hunters" to "Hunters",
                "genre/rabbit" to "Rabbit",
                "genre/hotshot" to "HotShot",
                "genre/gupchup" to "GupChup",
                "genre/hindi" to "Hindi",
                "genre/hindi-dubbed" to "Hindi Dubbed",
                "genre/indian" to "INDIAN",
                "genre/niksindian" to "Niksindian",
                "genre/rasminair" to "RasmiNair",
                "genre/9redmovie" to "9redmovie",
                "genre/addatv" to "Addatv",
                "genre/aahaflix" to "AahaFlix",
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
                "genre/habbitmovies" to "HabbitMovies",
                "genre/hitprime" to "Hitprime",
                "genre/hotfm" to "HotFM",
                "genre/hotx" to "Hotx",
                "genre/hulchul" to "HulChul",
                "genre/ibamovies" to "IBAMovies",
                "genre/jalva" to "Jalva",
                "genre/jugnu" to "Jugnu",
                "genre/jugnutv" to "JugnuTV",
                "genre/lookentertainment" to "Lookentertainment",
                "genre/makhan" to "Makhan",
                "genre/meetx" to "Meetx"
            ),

            // 3. BrazzPW (35 Categories)
            "brazzpw" to listOf(
                "/videos/newest/" to "Newest Videos",
                "/videos/sortby/beingwatched/" to "Being Watched",
                "/videos/sortby/rating/" to "Top Rated",
                "/videos/sortby/views/" to "Most Viewed",
                "/videos/tags/112/black/" to "Black",
                "/videos/tags/448/anal/" to "Anal",
                "/videos/tags/79/milf/" to "Milf",
                "/videos/tags/49/big-ass/" to "Big Ass",
                "/videos/tags/220/squirt/" to "Squirt",
                "/videos/tags/92/bbw/" to "BBW",
                "/videos/tags/181/stepmom/" to "Stepmom",
                "/videos/tags/211/massage/" to "Massage",
                "/videos/tags/511/parody/" to "Parody",
                "/videos/tags/192/yoga/" to "Yoga",
                "/videos/tags/473/double-penetration/" to "Double Penetration",
                "/videos/site/65/big-wet-butts/" to "Big Wet Butts",
                "/videos/site/66/baby-got-boobs/" to "Baby Got Boobs",
                "/videos/site/62/doctor-adventures/" to "Doctor Adventures",
                "/videos/site/67/mommy-got-boobs/" to "Mommy Got Boobs",
                "/videos/site/71/big-tits-at-work/" to "Big Tits At Work",
                "/videos/site/75/big-tits-at-school/" to "Big Tits At School",
                "/videos/site/99/mom-in-control/" to "Mom in control",
                "/videos/site/98/cfnm/" to "Cfnm",
                "/videos/site/96/brazzers-exxtra/" to "Brazzers Exxtra",
                "/videos/site/94/dirty-masseur/" to "Dirty masseur",
                "/videos/site/92/zz-series/" to "Zz Series",
                "/videos/site/90/hot-and-mean/" to "Hot and mean",
                "/videos/site/89/big-tits-in-uniform/" to "Big tits in uniform",
                "/videos/site/87/day-with-pornstar/" to "Day with pornstar",
                "/videos/site/83/big-tits-in-sports/" to "Big tits in sports",
                "/videos/site/82/big-butts-like-it-big/" to "Big butts like it big",
                "/videos/site/81/real-wife-stories/" to "Real wife Stories",
                "/videos/site/80/teen-like-it-big/" to "Teen like it big",
                "/videos/site/78/milfs-like-it-big/" to "Milfs like it big",
                "/videos/site/77/pornstar-like-it-big/" to "Pornstar like it big"
            ),

            // 4. XMoviesForYou (53 Studios)
            "xmoviesforyou" to listOf(
                "/studio/assparade" to "Studio: AssParade",
                "/studio/bangbus" to "Studio: BangBus",
                "/studio/bjraw" to "Studio: BJRaw",
                "/studio/blacked" to "Studio: Blacked",
                "/studio/blackedraw" to "Studio: BlackedRaw",
                "/studio/blacksonblondes" to "Studio: BlacksOnBlondes",
                "/studio/brazzersexxtra" to "Studio: BrazzersExxtra",
                "/studio/cherrypimps" to "Studio: CherryPimps",
                "/studio/dadcrush" to "Studio: DadCrush",
                "/studio/deeplush" to "Studio: DeepLush",
                "/studio/deeper" to "Studio: Deeper",
                "/studio/devilsfilm" to "Studio: DevilsFilm",
                "/studio/dickdrainers" to "Studio: DickDrainers",
                "/studio/dirtyauditions" to "Studio: DirtyAuditions",
                "/studio/dorcelclub" to "Studio: DorcelClub",
                "/studio/elegantangel" to "Studio: ElegantAngel",
                "/studio/evilangel" to "Studio: EvilAngel",
                "/studio/familystrokes" to "Studio: FamilyStrokes",
                "/studio/gangbangcreampie" to "Studio: GangbangCreampie",
                "/studio/girlsway" to "Studio: GirlsWay",
                "/studio/hotwifexxx" to "Studio: HotwifeXXX",
                "/studio/hussiepass" to "Studio: HussiePass",
                "/studio/hustler" to "Studio: Hustler",
                "/studio/japanhdv" to "Studio: JapanHDV",
                "/studio/jayspov" to "Studio: JaysPOV",
                "/studio/julesjordan" to "Studio: JulesJordan",
                "/studio/loveherfeet" to "Studio: LoveHerFeet",
                "/studio/mylifeinmiami" to "Studio: MyLifeInMiami",
                "/studio/mypervyfamily" to "Studio: MyPervyFamily",
                "/studio/mysistershotfriend" to "Studio: MySistersHotFriend",
                "/studio/newsensations" to "Studio: NewSensations",
                "/studio/penthousegold" to "Studio: PenthouseGold",
                "/studio/pervmom" to "Studio: PervMom",
                "/studio/porndudecasting" to "Studio: PornDudeCasting",
                "/studio/pornworld" to "Studio: PornWorld",
                "/studio/private" to "Studio: Private",
                "/studio/puretaboo" to "Studio: PureTaboo",
                "/studio/roccosiffredi" to "Studio: RoccoSiffredi",
                "/studio/seehimfuck" to "Studio: SeeHimFuck",
                "/studio/sexmex" to "Studio: SexMex",
                "/studio/shoplyfter" to "Studio: Shoplyfter",
                "/studio/sislovesme" to "Studio: SisLovesMe",
                "/studio/spankmonster" to "Studio: SpankMonster",
                "/studio/sweetsinner" to "Studio: SweetSinner",
                "/studio/tabooheat" to "Studio: TabooHeat",
                "/studio/teamskeetselects" to "Studio: TeamSkeetSelects",
                "/studio/teenfidelity" to "Studio: TeenFidelity",
                "/studio/throated" to "Studio: Throated",
                "/studio/touchmywife" to "Studio: TouchMyWife",
                "/studio/tushy" to "Studio: Tushy",
                "/studio/tushyraw" to "Studio: TushyRaw",
                "/studio/vixen" to "Studio: Vixen",
                "/studio/wicked" to "Studio: Wicked"
            ),

            // 5. XXXTime (48 Categories & Models)
            "xxxtime" to listOf(
                "/channels.php?ch=Teamskeet" to "Teamskeet",
                "/channels.php?ch=Cum4k" to "Cum4k",
                "/channels.php?ch=Brazzers" to "Brazzers",
                "/channels.php?ch=Mature4k" to "Mature4k",
                "/channels.php?ch=Mom4k" to "Mom4k",
                "/categories.php?c=Big+Ass" to "Big Ass",
                "/categories.php?c=Redhead" to "Redhead",
                "/model.php?p=Abigail-Morris" to "Abigail Morris",
                "/model.php?p=Alanna-pow" to "Alanna Pow",
                "/model.php?p=Aleksa-Mink" to "Aleksa Mink",
                "/model.php?p=Alexa-Chains" to "Alexa Chains",
                "/model.php?p=Ana-Khalifa" to "Ana Khalifa",
                "/model.php?p=Angel-Youngs" to "Angel Youngs",
                "/model.php?p=Angela-White" to "Angela White",
                "/model.php?p=aspen-reign" to "Aspen Reign",
                "/model.php?p=Comatozze" to "Comatozze",
                "/model.php?p=Carolina-Guerrero" to "Carolina Guerrero",
                "/model.php?p=Coco-Lovelock" to "Coco Lovelock",
                "/model.php?p=Dee-Williams" to "Dee Williams",
                "/model.php?p=ellie-nova" to "Ellie Nova",
                "/model.php?p=Elly-Clutch" to "Elly Clutch",
                "/model.php?p=Eve-Sweet" to "Eve Sweet",
                "/model.php?p=Hailey-Rose" to "Hailey Rose",
                "/model.php?p=Isa-Bella" to "Isa Bella",
                "/model.php?p=jasmine-sherni" to "Jasmine Sherni",
                "/model.php?p=Juniper-Ren" to "Juniper Ren",
                "/model.php?p=Koda-Monroe" to "Koda Monroe",
                "/model.php?p=Kali-Roses" to "Kali Roses",
                "/model.php?p=Kayley-Gunner" to "Kayley Gunner",
                "/model.php?p=Lauren-Phillips" to "Lauren Phillips",
                "/model.php?p=Lena-The-Plug" to "Lena The Plug",
                "/model.php?p=LuSirena69" to "LuSirena69",
                "/model.php?p=Lucy-Mochi" to "Lucy Mochi",
                "/model.php?p=Lula-Stocch" to "Lula Stocch",
                "/model.php?p=Luna-Doll" to "Luna Doll",
                "/model.php?p=Melissa-Stratton" to "Melissa Stratton",
                "/model.php?p=Mia-Khalifa" to "Mia Khalifa",
                "/model.php?p=Nia-Bleu" to "Nia Bleu",
                "/model.php?p=Octokuro" to "Octokuro",
                "/model.php?p=Payton-Preslee" to "Payton Preslee",
                "/model.php?p=Raissa-Bellini" to "Raissa Bellini",
                "/model.php?p=Rossa-Vaxx" to "Rossa Vaxx",
                "/model.php?p=Roxie-Sinner" to "Roxie Sinner",
                "/model.php?p=Savannah-Bond" to "Savannah Bond",
                "/model.php?p=Vivienne-Vo" to "Vivienne Vo",
                "/model.php?p=Willow-Ryder" to "Willow Ryder",
                "/model.php?p=Yasmina-Khan" to "Yasmina Khan",
                "/model.php?p=Zoey-Uso" to "Zoey Uso"
            ),

            // 6. WatchPorn (16 Categories)
            "watchporn" to listOf(
                "/top-rated/" to "Top Rated",
                "/most-popular/" to "Most Popular",
                "/categories/primalfetish/" to "PrimalFetish",
                "/categories/brazzersexxtra/" to "BrazzersExxtra",
                "/categories/julesjordan/" to "JulesJordan",
                "/categories/tabooheat/" to "TabooHeat",
                "/categories/outofthefamily/" to "OutOfTheFamily",
                "/categories/missax/" to "MissaX",
                "/categories/blackedraw/" to "BlackedRaw",
                "/categories/vixen/" to "Vixen",
                "/categories/rkprime/" to "RKPrime",
                "/categories/puretaboo/" to "PureTaboo",
                "/categories/deeper/" to "Deeper",
                "/categories/tushy/" to "Tushy",
                "/categories/blacked/" to "Blacked",
                "/categories/bangbus/" to "BangBus"
            ),

            // 7. Taboodaddy (23 Categories)
            "taboodaddy" to listOf(
                "" to "Latest",
                "category/analmom" to "Anal Mom",
                "category/brazzers" to "Brazzers",
                "category/bangbros" to "BangBros",
                "category/bdsm" to "BDSM",
                "category/best" to "Best",
                "category/brother-sister-porn" to "Brother Sister Porn",
                "category/big-ass" to "Big Ass",
                "category/big-dick" to "Big Dick",
                "category/blacked" to "Blacked",
                "category/christmas" to "Christmas",
                "category/deep-throat" to "Deep Throat",
                "category/deeper" to "Deeper",
                "category/digital-playground" to "Digital Playground",
                "category/familystrokes" to "FamilyStrokes",
                "category/incent" to "Incent",
                "category/milf" to "Milf",
                "category/missax" to "Missax",
                "category/mom-son-porn" to "Mom Son Porn",
                "category/mommysboy" to "MommysBoy",
                "category/puretaboo" to "Pure Taboo",
                "category/realitykings" to "Realitykings",
                "category/taboo" to "Taboo"
            ),

            // 8. Pvzija / BluMovies (42 Categories)
            "pvzija" to listOf(
                "page/%d/" to "New Movies",
                "page/%d/?filter=most-viewed" to "Popular Movies",
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
            ),

            // 9. Free Porn Videos (25 Categories)
            "freepornvideos" to listOf(
                "/trending-porn-videos" to "Trending Porn Videos",
                "/most-popular/week" to "Most Popular",
                "/top-rated" to "Top Rated",
                "/new-porn-videos" to "New Porn Videos",
                "/categories/ball-licking" to "Ball Licking",
                "/categories/ball-play" to "Ball Play",
                "/categories/ball-sucking" to "Ball Sucking",
                "/categories/ballerina" to "Ballerina",
                "/categories/bathroom" to "Bathroom",
                "/categories/bbc-big-black-cock" to "BBC (Big Black Cock)",
                "/categories/beautiful" to "Beautiful",
                "/categories/bbw" to "BBW",
                "/categories/big-areolas" to "Big Areolas",
                "/categories/cfnm" to "CFNM",
                "/categories/cum-in-mouth" to "Cum in Mouth",
                "/categories/doctor" to "Doctor",
                "/categories/ebony" to "Ebony",
                "/categories/freeuse" to "FreeUse",
                "/categories/high-heels" to "High Heels",
                "/categories/mature" to "Mature",
                "/categories/massage" to "Massage",
                "/categories/milf" to "MILF",
                "/categories/mom" to "Mom",
                "/categories/mexican" to "Mexican",
                "/categories/pantyhose" to "Pantyhose"
            )
        )
    }

    override val mainPage: List<MainPageData>
        get() {
            val enabledServers = WatchOMoviesSettingsDialog.getOrderedEnabledServers()
                .ifEmpty { listOf(WatchOMoviesSettingsDialog.SERVERS.first()) }
            val limit = WatchOMoviesSettingsDialog.getRowsPerServer()

            val rows = mutableListOf<MainPageData>()
            enabledServers.forEach { srv ->
                val allCats = serverCategoryMap[srv.key].orEmpty()
                val cats = if (limit >= 60) allCats else allCats.take(limit)
                cats.forEach { (path, label) ->
                    rows.add(
                        MainPageData(
                            name = "${srv.name} • $label",
                            data = encodeRoute(srv.key, path)
                        )
                    )
                }
            }
            return if (WatchOMoviesSettingsDialog.isShuffleHome() && rows.size > 1) {
                listOf(rows.first()) + rows.drop(1).shuffled()
            } else {
                rows
            }
        }

    // =========================================================================
    // Unified getMainPage Router
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
                val items = extractWatchOMoviesItems(html, base)
                newHomePageResponse(HomePageList(request.name, items, false), hasNext = items.isNotEmpty())
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
    // Multi-Server Concurrent Search
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
                extractWatchOMoviesItems(html, base)
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
            "watchomovies" -> loadWatchOMovies(url, realRaw, base)
            "tellyhd" -> loadTellyHd(url, realRaw, base)
            "brazzpw" -> loadBrazzPw(url, realRaw, base)
            "xmoviesforyou" -> loadXMoviesForYou(url, realRaw, base)
            "xxxtime" -> loadXXXTime(url, realRaw, base)
            "watchporn" -> loadWatchPorn(url, realRaw, base)
            "taboodaddy" -> loadTabooDaddy(url, realRaw, base)
            "pvzija" -> loadPvzija(url, realRaw, base)
            "freepornvideos" -> loadFreePornVideos(url, realRaw, base)
            else -> loadWatchOMovies(url, realRaw, base)
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
            "watchomovies" -> loadLinksWatchOMovies(realData, base, subtitleCallback, callback)
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

    private fun extractWatchOMoviesItems(html: String, base: String): List<SearchResponse> {
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
            val title = fixTitle(rawTitle).trim()

            val thumb = card.selectFirst("img.mli-thumb") ?: card.selectFirst("img")
            val poster = resolveFullUrl(
                base,
                thumb?.attr("data-original")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                    ?: thumb?.attr("src")?.takeIf { it.isNotBlank() }
            )

            val isSeries = url.contains("/series/", true) ||
                Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(title)

            val route = encodeRoute("watchomovies", url)
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
                val title = fixTitle(rawTitle).trim()
                val img = card.selectFirst("img")
                val poster = resolveFullUrl(
                    base,
                    img?.attr("data-original")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                )
                val route = encodeRoute("watchomovies", url)
                items.add(newMovieSearchResponse(title, route, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return items.distinctBy { it.url }
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
    private suspend fun loadWatchOMovies(routeUrl: String, url: String, base: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load WatchOMovies page")
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
        val recommendations = extractWatchOMoviesItems(html, base).filter { it.url != routeUrl }

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
                        newEpisode(encodeRoute("watchomovies", href)) {
                            this.name = label.ifBlank { "Episode ${episodes.size + 1}" }
                            this.season = season
                            this.episode = epNum ?: (episodes.size + 1)
                        }
                    )
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, routeUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags; this.recommendations = recommendations
                }
            }
        }

        return newMovieLoadResponse(title, routeUrl, TvType.Movie, routeUrl) {
            this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags; this.recommendations = recommendations
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
    private suspend fun loadLinksWatchOMovies(
        data: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val pageUrl = data.substringBefore("#").substringBefore("?")
        val html = getPage(pageUrl)?.text ?: return false
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
            val label = tabLabels[tabId] ?: "WatchOMovies"

            when {
                src.contains("speedostream", true) && src.contains("embed-") -> {
                    if (resolveSpeedostream(src, base, label, callback)) found = true
                }
                src.contains("vidsrc", true) -> {
                    if (resolveVidsrc(src, base, pageUrl, callback)) found = true
                }
                else -> {
                    if (loadExtractor(resolveFullUrl(base, src) ?: src, pageUrl, subtitleCallback, callback)) found = true
                }
            }
        }
        return found
    }

    private suspend fun resolveSpeedostream(embedUrl: String, base: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("speedostream")) return false
        return try {
            val clean = resolveFullUrl(base, embedUrl) ?: return false
            val host = try { Uri.parse(clean).host ?: "speedostream1.com" } catch (e: Exception) { "speedostream1.com" }
            val r = getPage(clean, referer = "https://$host/") ?: return false
            val text = r.text
            if (text.contains("Embeds disabled", true)) return false
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
                ?: return false

            callback(
                newExtractorLink("WatchOMovies", label.take(40), m3u8, ExtractorLinkType.M3U8) {
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

    private suspend fun resolveVidsrc(embedUrl: String, base: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("speedostream")) return false
        return try {
            val clean = resolveFullUrl(base, embedUrl) ?: return false
            val r1 = getPage(clean, referer = referer) ?: return false
            val target = Jsoup.parse(r1.text).selectFirst("iframe[src]")?.attr("src")?.let { resolveFullUrl(clean, it) } ?: return false
            val r2 = getPage(target, referer = clean) ?: return false
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(r2.text)?.groupValues?.get(1)
                ?: Regex("""https:[^"' ]+\.m3u8[^"' ]*""").find(r2.text)?.groupValues?.get(0) ?: return false

            callback(
                newExtractorLink("WatchOMovies", "WatchOMovies • VidSrc", m3u8, ExtractorLinkType.M3U8) {
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
