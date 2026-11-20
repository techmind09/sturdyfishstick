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

        // Step 1: Movie page se dldownload.com.ng ya download button link nikalna
        val dlPageUrl = movieDoc.select("a[href*='dldownload.com.ng'], a.button, a.sdm_download, a[href*='xxxmoviesdl.net']")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .firstOrNull { it.contains("dldownload") || it.contains("xxxmoviesdl") || it.contains("sdm_downloads") }
            ?: return false

        // Step 2: Agar link dldownload.com.ng ka hai toh wahan se xxxmoviesdl.net ka link nikalna
        val finalPageUrl = if (dlPageUrl.contains("dldownload.com.ng") || dlPageUrl.contains("sdm_downloads")) {
            val dlDoc = app.get(dlPageUrl, referer = data).document
            dlDoc.select("a.sdm_download, a[href*='xxxmoviesdl.net/dl/']")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .firstOrNull { it.contains("xxxmoviesdl.net/dl/") }
                ?: return false
        } else {
            dlPageUrl
        }

        // Step 3: xxxmoviesdl.net page se "?download=1" (867 MB) link aur label nikalna
        val xxxDoc = app.get(finalPageUrl, referer = dlPageUrl).document
        val downloadBtn = xxxDoc.selectFirst("a.btn.btn-primary[href*='download=1'], a[href*='?download=1']")
            ?: return false

        val downloadTriggerUrl = fixUrl(downloadBtn.attr("href"))
        val btnText = downloadBtn.text().trim() // e.g., "Download (867 MB)"

        // Step 4: Final stream/download URL resolve karna (redirect follow karke)
        val response = app.get(
            downloadTriggerUrl,
            referer = finalPageUrl,
            allowRedirects = false
        )

        val streamUrl = response.headers["Location"]?.let { fixUrl(it) }
            ?: app.get(downloadTriggerUrl, referer = finalPageUrl).url

        if (streamUrl.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (btnText.isNotBlank()) "$name - $btnText" else name,
                    url = streamUrl,
                    isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
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
