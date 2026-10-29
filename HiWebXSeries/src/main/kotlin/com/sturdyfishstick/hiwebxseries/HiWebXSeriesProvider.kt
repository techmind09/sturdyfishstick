package com.sturdyfishstick.hiwebxseries

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HiWebXSeriesProvider : MainAPI() {
    override var mainUrl = "https://hiwebxseries.hot"
    override var name = "HiWebXSeries"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Latest",
        "short-films" to "Short Films",
        "series" to "Series",
        "category/9redmovies" to "9redmovies",
        "category/addatv" to "Addatv",
        "category/akkuott" to "Akkuott",
        "category/altt" to "ALTT",
        "category/bablutv" to "BabluTv",
        "category/bade-achhe-lagte-hai" to "Bade Achhe Lagte Hai",
        "category/balam-pichkari" to "Balam Pichkari",
        "category/besharam" to "Besharam",
        "category/bindastimes" to "BindasTimes",
        "category/biwi-ka-khat" to "Biwi Ka Khat",
        "category/blackmail-palang-tod" to "Blackmail Palang Tod",
        "category/boom-entertainment" to "Boom Entertainment",
        "category/boomex" to "Boomex",
        "category/caretaker" to "Caretaker",
        "category/chaashni" to "Chaashni",
        "category/charmsukh-yeh-kaisa-rishta" to "Charmsukh Yeh Kaisa Rishta",
        "category/chatpatamovies" to "Chatpatamovies",
        "category/chidiya" to "Chidiya",
        "category/chuskitv" to "Chuskitv",
        "category/cinemagicx" to "Cinemagicx",
        "category/crfstudioz" to "Crfstudioz",
        "category/cukkuboo" to "Cukkuboo",
        "category/daal-chawal" to "Daal Chawal",
        "category/damaad-ji" to "Damaad Ji",
        "category/de-de-pyaar-de" to "De De Pyaar De",
        "category/dekho-magar-pyar-se" to "Dekho Magar Pyar Se",
        "category/desiprime" to "Desiprime",
        "category/digimovieplex" to "DigimoviePlex",
        "category/dosttana" to "Dosttana",
        "category/dramazone" to "DramaZone",
        "category/dugru" to "Dugru",
        "category/dzyreplay" to "Dzyreplay",
        "category/feelapp" to "FeelApp",
        "category/feniapp-malayalam-short-film" to "Feniapp Malayalam Short Film",
        "category/feniseries" to "Feniseries",
        "category/flamemovies" to "FlameMovies",
        "category/foxxprime" to "FoxxPrime",
        "category/fridayseries" to "FridaySeries",
        "category/fugi" to "Fugi",
        "category/funterspro" to "FuntersPro",
        "category/funtyy" to "Funtyy",
        "category/games-of-karma" to "Games Of Karma",
        "category/gettempt" to "Gettempt",
        "category/goddesmahi" to "GoddesMahi",
        "category/gore-gore-gaal" to "Gore Gore Gaal",
        "category/gupchup" to "GupChup",
        "category/habbitmovies" to "Habbitmovies",
        "category/happy-ending" to "Happy Ending",
        "category/haseena-maan-jayegi" to "Haseena Maan Jayegi",
        "category/hasratey" to "Hasratey",
        "category/hindi-hot-cartoon-video" to "Hindi Hot Cartoon Video"
    )

    private fun Element.toVideoResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val url = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = this.selectFirst("h2.vtitle")?.text()
            ?: anchor.selectFirst("img")?.attr("alt") ?: ""
        if (title.isBlank()) return null
        val posterUrl = anchor.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    private fun Element.toSeriesResult(): SearchResponse? {
        val url = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = this.selectFirst("span.name")?.text() ?: ""
        if (title.isBlank()) return null
        val style = this.attr("style")
        val posterUrl = if (style.contains("url(")) style.substringAfter("url(").substringBefore(")").trim('\'', '"') else null

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.removePrefix("/").removeSuffix("/")
        val url = when {
            path.isEmpty() && page == 1 -> "$mainUrl/"
            path.isEmpty() -> "$mainUrl/page/$page/"
            page == 1 -> "$mainUrl/$path/"
            else -> "$mainUrl/$path/page/$page/"
        }
        val document = app.get(url).document

        val home = when {
            request.data.isEmpty() -> document.select("li.godx-box").mapNotNull { it.toVideoResult() }
            request.data.startsWith("category/") -> document.select("li.godx-box").mapNotNull { it.toVideoResult() }
            else -> document.select("a.video.blk").mapNotNull { it.toSeriesResult() }
        }
        val hasNext = document.select("link[rel=next]").isNotEmpty() && home.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, home, true), hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/search/$query").document
        val searchResult: List<SearchResponse> =
            document.select("li.godx-box").mapNotNull { it.toVideoResult() }
        return searchResult.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        if (url.contains("/series/") || url.contains("/playlist/") ||
            url.contains("/short-film/") || url.contains("/category/")
        ) {
            val title = doc.selectFirst("h1.relt")?.text()?.removePrefix("All ")?.removeSuffix(" Videos")
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "$name"
            val episodes = doc.select("li.godx-box").mapNotNull { box ->
                val anchor = box.selectFirst("a") ?: return@mapNotNull null
                val epUrl = anchor.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epTitle = box.selectFirst("h2.vtitle")?.text() ?: ""
                val epPoster = anchor.selectFirst("img")?.attr("src")
                val epNum = Regex("""Episode\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.posterUrl = fixUrlNull(epPoster)
                    this.episode = epNum ?: 0
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                    this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
                }
            }
        }

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("»") ?: "$name"
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title.trim(), url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("video source").mapNotNull { source ->
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val quality = source.attr("size").toIntOrNull() ?: Qualities.Unknown.value

            callback.invoke(
                newExtractorLink(name, "$name ${source.attr("size")}p", src) {
                    this.referer = data
                    this.quality = quality
                    this.type = ExtractorLinkType.VIDEO
                }
            )
        }
        return true
    }
}
