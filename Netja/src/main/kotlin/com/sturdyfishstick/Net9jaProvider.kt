package com.sturdyfishstick.netja

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Net9jaProvider : MainAPI() {
    override var mainUrl = "https://www.net9ja.tv/adult"
    override var name = "Net9ja"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/category/movies/" to "18+ Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val home = document.select("div.dp-grid article.dp-card").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = document.selectFirst("a.next.page-numbers") != null
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.dp-card__title a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a.dp-card__thumb img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.dp-grid article.dp-card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .dp-single__title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".dp-single__poster img, article img")?.attr("src")
        )
        val description = document.selectFirst(".dp-single__content p, .entry-content p")?.text()?.trim()
        val tags = document.select("a[rel=tag]").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val movieDoc = app.get(data).document

        // 1. Movie page se dldownload.com.ng / sdm_downloads / xxxmoviesdl link nikalna
        val dlPageUrl = movieDoc.select("a[href*='dldownload.com.ng'], a[href*='sdm_downloads'], a.button, a.sdm_download, a[href*='xxxmoviesdl.net']")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .firstOrNull {
                it.contains("dldownload", ignoreCase = true) ||
                    it.contains("sdm_downloads", ignoreCase = true) ||
                    it.contains("xxxmoviesdl", ignoreCase = true)
            }
            ?: return false

        // 2. Intermediate page se xxxmoviesdl.net/dl/... link nikalna
        val finalPageUrl = if (dlPageUrl.contains("dldownload", ignoreCase = true) || dlPageUrl.contains("sdm_downloads", ignoreCase = true)) {
            val dlDoc = app.get(dlPageUrl, referer = data).document
            dlDoc.select("a.sdm_download, a[href*='xxxmoviesdl.net/dl/'], a[href*='xxxmoviesdl.com']")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .firstOrNull { it.contains("xxxmoviesdl", ignoreCase = true) }
                ?: return false
        } else {
            dlPageUrl
        }

        // 3. xxxmoviesdl page se "?download=1" button aur label nikalna
        val xxxDoc = app.get(finalPageUrl, referer = dlPageUrl).document
        val downloadBtn = xxxDoc.selectFirst("a.btn.btn-primary[href*='download=1'], a[href*='download=1']")
        val downloadTriggerUrl = downloadBtn?.attr("href")?.let { fixUrl(it) }
            ?: if (finalPageUrl.contains("download=1")) finalPageUrl else "$finalPageUrl?download=1"
        val btnText = downloadBtn?.text()?.trim().orEmpty()

        // 4. Redirect chain follow karke direct dl.xxxmoviesdl.com MP4/media URL resolve karna
        val directResponse = app.get(
            downloadTriggerUrl,
            referer = finalPageUrl,
            allowRedirects = true
        )

        var streamUrl = directResponse.url

        if (!streamUrl.contains("dl.xxxmoviesdl.com", ignoreCase = true) && !streamUrl.contains(".mp4", ignoreCase = true)) {
            val html = directResponse.text
            val mp4Regex = Regex("""https?://[^\s"'<>]+?\.mp4(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            val matchedUrl = mp4Regex.find(html)?.value
                ?: directResponse.document.selectFirst("video source, video, a[href*='.mp4']")
                    ?.let { it.attr("src").ifBlank { it.attr("href") } }

            if (!matchedUrl.isNullOrBlank()) {
                streamUrl = fixUrl(matchedUrl)
            }
        }

        if (streamUrl.isNotBlank()) {
            val linkType = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (btnText.isNotBlank()) "$name - $btnText" else name,
                    url = streamUrl,
                    type = linkType
                ) {
                    this.referer = finalPageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }
}
