package com.sturdyfishstick.brazzpw

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.subtitles.GoogleTranslate
import java.net.URLEncoder

class BrazzPW : MainAPI() {
    override var mainUrl             = "https://brazzpw.xyz"
    override var name                = "BrazzPW"
    override val hasMainPage         = true
    override var lang                = "en"
    override val hasQuickSearch      = false
    override val supportedTypes      = setOf(TvType.NSFW)
    override val vpnStatus           = VPNStatus.MightBeNeeded
    override val instantLinkLoading  = true

    override val mainPage = mainPageOf(
        "$mainUrl/videos/newest/"                    to "Newest Videos",
        "$mainUrl/videos/sortby/beingwatched/"       to "Being Watched",
        "$mainUrl/videos/sortby/rating/"             to "Top Rated",
        "$mainUrl/videos/sortby/views/"              to "Most Viewed",
        "$mainUrl/videos/tags/112/black/"            to "Black",
        "$mainUrl/videos/tags/448/anal/"             to "Anal",
        "$mainUrl/videos/tags/79/milf/"              to "Milf",
        "$mainUrl/videos/tags/49/big-ass/"           to "Big Ass",
        "$mainUrl/videos/tags/220/squirt/"           to "Squirt",
        "$mainUrl/videos/tags/92/bbw/"               to "BBW",
        "$mainUrl/videos/tags/181/stepmom/"          to "Stepmom",
        "$mainUrl/videos/tags/211/massage/"          to "Massage",
        "$mainUrl/videos/tags/511/parody/"           to "Parody",
        "$mainUrl/videos/tags/192/yoga/"             to "Yoga",
        "$mainUrl/videos/tags/473/double-penetration/" to "Double Penetration",
        "$mainUrl/videos/site/65/big-wet-butts/"     to "Big Wet Butts",
        "$mainUrl/videos/site/66/baby-got-boobs/"    to "Baby Got Boobs",
        "$mainUrl/videos/site/62/doctor-adventures/" to "Doctor Adventures",
        "$mainUrl/videos/site/67/mommy-got-boobs/"   to "Mommy Got Boobs",
        "$mainUrl/videos/site/71/big-tits-at-work/"  to "Big Tits At Work",
        "$mainUrl/videos/site/75/big-tits-at-school/" to "Big Tits At School",
        "$mainUrl/videos/site/99/mom-in-control/"    to "Mom in control",
        "$mainUrl/videos/site/98/cfnm/"              to "Cfnm",
        "$mainUrl/videos/site/96/brazzers-exxtra/"   to "Brazzers Exxtra",
        "$mainUrl/videos/site/94/dirty-masseur/"     to "Dirty masseur",
        "$mainUrl/videos/site/92/zz-series/"         to "Zz Series",
        "$mainUrl/videos/site/90/hot-and-mean/"      to "Hot and mean",
        "$mainUrl/videos/site/89/big-tits-in-uniform/" to "Big tits in uniform",
        "$mainUrl/videos/site/87/day-with-pornstar/" to "Day with pornstar",
        "$mainUrl/videos/site/83/big-tits-in-sports/" to "Big tits in sports",
        "$mainUrl/videos/site/82/big-butts-like-it-big/" to "Big butts like it big",
        "$mainUrl/videos/site/81/real-wife-stories/" to "Real wife Stories",
        "$mainUrl/videos/site/80/teen-like-it-big/"  to "Teen like it big",
        "$mainUrl/videos/site/78/milfs-like-it-big/" to "Milfs like it big",
        "$mainUrl/videos/site/77/pornstar-like-it-big/" to "Pornstar like it big"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl  = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(pageUrl).document
        val home     = document.select("article.loop-video").mapNotNull { it.toCardResult() }

        return newHomePageResponse(
            HomePageList(request.name, home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toCardResult(): SearchResponse? {
        val link      = selectFirst("a") ?: return null
        val href      = fixUrlNull(link.attr("href").ifEmpty { return null }) ?: return null
        val header    = selectFirst("header.entry-header span")?.text()?.substringAfter(":")?.trim()
        val title     = header?.ifEmpty { null } ?: link.attr("title").ifEmpty { null } ?: selectFirst("img")?.attr("alt")?.ifEmpty { null } ?: return null
        val img       = selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("src")?.ifEmpty { null })

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded  = URLEncoder.encode(query, "UTF-8")
        val url      = if (page <= 1) "$mainUrl/search/?s=$encoded" else "$mainUrl/search/page/$page/?s=$encoded"
        val document = app.get(url).document
        val results  = document.select("article.loop-video").mapNotNull { it.toCardResult() }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title         = document.selectFirst("h1.entry-title")?.text()?.trim()?.ifEmpty { null } ?: return null
        val poster        = fixUrlNull(document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.ifEmpty { null })
        val description   = document.selectFirst("div.video-description p")?.text()?.trim()?.ifEmpty { null }
        val year          = document.selectFirst("meta[itemprop=uploadDate]")?.attr("content")?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val tags          = document.select("div.tags-list a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val scoreVal      = document.selectFirst("div.percentage")?.text()?.trim()?.removeSuffix("%")?.toDoubleOrNull()
        val duration      = document.selectFirst("meta[itemprop=duration]")?.attr("content")?.let { Regex("""(\d+)M""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val recommendations = document.select("div.under-video-block article.loop-video").mapNotNull { it.toCardResult() }
        val actors        = document.select("div#video-actors a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val iframe        = fixUrlNull(document.selectFirst("iframe[name=player]")?.attr("src")?.ifEmpty { null })

        return newMovieLoadResponse(title, url, TvType.NSFW, iframe ?: url) {
            this.posterUrl       = poster
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.score           = Score.from100(scoreVal?.toInt())
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = if (data.contains("/player/")) data else fixUrlNull(
            app.get(data).document.selectFirst("iframe[name=player]")?.attr("src")?.ifEmpty { null }
        ) ?: return false
        val pageText  = app.get(playerUrl, referer = "$mainUrl/").text
        
        // Extract Subtitles from Player JavaScript if present
        val subRegex = Regex("""file:\s*"([^"]+\.(?:vtt|srt))"""")
        subRegex.findAll(pageText).forEach { match ->
            val subUrl = fixUrlNull(match.groupValues[1])
            if (subUrl != null) {
                // Primary Subtitle (English/Default)
                subtitleCallback.invoke(
                    SubtitleFile("English", subUrl)
                )
                
                // Add Google Translate Subtitle Option (Hindi / Targeted Language)
                try {
                    val translatedUrl = GoogleTranslate.getTranslateUrl(subUrl, "hi") // "hi" = Hindi, change to "en" / target lang
                    subtitleCallback.invoke(
                        SubtitleFile("Google Translate (Hindi)", translatedUrl)
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val m3u8Path  = Regex("""src:\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)?.ifEmpty { null } ?: return false
        val m3u8Url   = when {
            m3u8Path.startsWith("http") -> m3u8Path
            m3u8Path.startsWith("/")    -> "$mainUrl$m3u8Path"
            else                        -> "${playerUrl.substringBeforeLast("/")}/$m3u8Path"
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = name,
                url    = m3u8Url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = playerUrl
            }
        )
        return true
    }
}
