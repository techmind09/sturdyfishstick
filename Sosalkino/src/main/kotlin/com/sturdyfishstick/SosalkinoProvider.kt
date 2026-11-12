package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SosalkinoProvider : MainAPI() {
    override var mainUrl = "https://m1.sosalkino.app"
    override var name = "Sosalkino"
    override val hasMainPage = true
    override var lang = "ru"
    override val supportedTypes = setOf(TvType.NSFW)

    // User session cookies captured from browser/app session
    private val standardHeaders = mapOf(
        "Cookie" to "PHPSESSID=f2f88ec80921b98fc6a8f2a98d7eed66; kt_acctoken=MjIzLjE4OC4xMzAuMTUz; kt_tcookie=1; tz_offset=-330; fp_hash=43186443fedd9ab11dcc962f1256e834; kt_is_visited=1; sos_video_cdn=primary; _ym_uid=1794454832459961649; _ym_d=1794454832; _ym_isad=2; userToken=dc71cd6d-ac35-4301-9094-61523d2c0907; kt_qparams=dir%3Dou-drochit-chlen-muja-i-trahatsya-s-otchimom"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "New videos",
        "$mainUrl/studios/sosalkino-exclusive/" to "Exclusives",
        "$mainUrl/top-rated/" to "The best (All time)",
        "$mainUrl/top-rated/month/" to "The best (In a month)",
        "$mainUrl/top-rated/week/" to "The best (In a week)",
        "$mainUrl/top-rated/today/" to "The best (For today)",
        "$mainUrl/most-popular/" to "Viewed (All time)",
        "$mainUrl/most-popular/month/" to "Viewed (In a month)",
        "$mainUrl/most-popular/week/" to "Viewed (In a week)",
        "$mainUrl/most-popular/today/" to "Viewed (For today)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val url = if (page > 1) "$baseUrl/$page/" else "$baseUrl/"
        
        val document = app.get(url, headers = standardHeaders).document
        val items = document.select("div.video_list div.items-list div.item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.link") ?: return null
        val href = fixUrl(linkElement.attr("href"))

        val imgElement = this.selectFirst("img.thumb")
        val title = this.selectFirst("div.title-item p.title")?.text()?.trim()
            ?: imgElement?.attr("alt")?.trim()
            ?: return null

        // Full robust image handling covering data-src, data-webp, and fallback src
        val posterUrl = imgElement?.let { img ->
            val src = img.attr("data-src").ifEmpty {
                img.attr("data-webp").ifEmpty {
                    img.attr("src")
                }
            }
            if (src.startsWith("data:image")) {
                img.attr("data-webp").ifEmpty { img.attr("data-src") }
            } else {
                src
            }
        }?.let { fixUrlNull(it) }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/$encodedQuery/"
        val document = app.get(url, headers = standardHeaders).document

        return document.select("div.video_list div.items-list div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = standardHeaders).document

        val title = document.selectFirst("h1.title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("video")?.attr("poster")

        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags = document.select("a[href*='/categories/'], a[href*='/tags/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val recommendations = document.select("div.video_list div.items-list div.item").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
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
        val response = app.get(data, headers = standardHeaders)
        val document = response.document
        val html = response.text

        // 1. Direct <video> / <source> tags
        document.select("video source, video").forEach { element ->
            val src = element.attr("src").ifEmpty { element.attr("data-src") }
            if (src.isNotEmpty() && (src.contains(".mp4") || src.contains("get_file"))) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(src),
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = src.contains(".m3u8")
                    )
                )
            }
        }

        // 2. KVS Player script pattern matching
        val videoUrlRegex = Regex("""(?:video_url|video_alt_url\d*)\s*:\s*['"]((?:function/0/)?https?://[^'"]+\.mp4/?[^'"]*)['"]""")
        val qualityRegex = Regex("""(?:video_url_text|video_alt_url\d*_text)\s*:\s*['"]([^'"]+)['"]""")

        val urls = videoUrlRegex.findAll(html).map { it.groupValues[1].removePrefix("function/0/") }.toList()
        val qualities = qualityRegex.findAll(html).map { it.groupValues[1] }.toList()

        urls.forEachIndexed { index, streamUrl ->
            val qualityStr = qualities.getOrNull(index) ?: ""
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name $qualityStr".trim(),
                    url = fixUrl(streamUrl),
                    referer = data,
                    quality = getQualityFromName(qualityStr),
                    isM3u8 = streamUrl.contains(".m3u8")
                )
            )
        }

        return true
    }
}
