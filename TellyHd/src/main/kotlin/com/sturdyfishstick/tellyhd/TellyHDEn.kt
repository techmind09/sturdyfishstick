Package com.sturdyfishstick.tellyhd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLEncoder

class TellyHDEn : MainAPI() {

    Override var mainUrl = "https://tellyhd.eu"
    Override var name = "TellyHD Other"
    Override val hasMainPage = true
    Override var lang = "hi"

    Override val supportedTypes = setOf(
        TvType.NSFW,
        TvType.TvSeries,
        TvType.Movie
    )

    Override val vpnStatus = VPNStatus.MightBeNeeded

    Private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    Private val playerHeaders = mapOf(
        "User-Agent" to headers["User-Agent"].orEmpty(),
        "Accept" to "*/*"
    )

    Override val mainPage = mainPageOf(
        "genre/adventure" to "Adventure",
        "genre/attackers" to "Attackers",
        "genre/brazzers" to "Brazzers",
        "genre/chinese" to "Chinese",
        "genre/deeper" to "Deeper",
        "genre/digital-playground" to "Digital Playground",
        "genre/documentary" to "Documentary", 
        "genre/eng-subtitle" to "Eng Subtitle", 
        "genre/english" to "English", 
        "genre/france" to "France", 
        "genre/hong-kong" to "Hong Kong", 
        "genre/japan" to "Japan",
        "genre/jav" to "JAV",
        "genre/korean" to "Korean", 
        "genre/marc-dorcel" to "Marc Dorcel", 
        "genre/onlyfans" to "OnlyFans", 
        "genre/philippine" to "Philippine", 
        "genre/private" to "Private", 
        "genre/science-fiction" to "Science Fiction", 
        "genre/sexart" to "SexArt", 
        "genre/sexmex" to "Sexmex",
        "genre/sunny-leone" to "Sunny Leone", 
        "genre/tbonx" to "TbonX", 
        "genre/usa" to "USA", 
        "genre/vintage" to "Vintage", 
        "genre/vivamax" to "VivaMax",
        "genre/vixen" to "Vixen", 
        "genre/wicked" to "Wicked"
    )

    Private fun getProperLink(uri: String): String {
        Val path = uri.substringBefore("?")

        Return when {
            Path.contains("/episodes/") -> {
                Val slug = path.substringAfter("/episodes/")
                    .substringBefore("/")

                Val showSlug = slug.substringBefore("-season")
                    .ifBlank { slug }

                "$mainUrl/tvshows/$showSlug/"
            }

            Path.contains("/seasons/") -> {
                Val slug = path.substringAfter("/seasons/")
                    .substringBefore("/")

                Val showSlug = slug.substringBefore("-season")
                    .ifBlank { slug }

                "$mainUrl/tvshows/$showSlug/"
            }

            Else -> uri
        }
    }

    Private fun resolveUrl(baseUrl: String, url: String): String {
        Val value = url.trim()

        Return when {
            Value.startsWith("https://", true) ||
                Value.startsWith("http://", true) -> value

            Value.startsWith("//") -> "https:$value"

            Else -> runCatching {
                URL(URL(baseUrl), value).toString()
            }.getOrDefault(value)
        }
    }

    Override suspend fun getMainPage(
        Page: Int,
        Request: MainPageRequest
    ): HomePageResponse {
        Val url = if (page <= 1) {
            "$mainUrl/${request.data}/"
        } else {
            "$mainUrl/${request.data}/page/$page/"
        }

        Val document = app.get(
            Url = url,
            Headers = headers
        ).document

        Val home = document.select(
            "div.items.normal article, " +
                "div#archive-content article, " +
                "div.items.full article"
        ).mapNotNull {
            It.toSearchResult()
        }

        Val hasNext = document.selectFirst(
            "a.next, a[rel=next], " +
                ".pagination a.next, " +
                "a.page-numbers.next"
        ) != null

        Return newHomePageResponse(
            Request.name,
            Home,
            HasNext = hasNext
        )
    }

    Private fun Element.toSearchResult(): SearchResponse? {
        Val anchor = selectFirst("h3 > a") ?: return null

        Val title = anchor.text().trim()
            .takeIf { it.isNotBlank() } ?: return null

        Val rawUrl = fixUrlNull(anchor.attr("href"))
            ?: return null

        Val href = getProperLink(rawUrl)

        Val image = select("div.poster img").lastOrNull()

        Var posterUrl = image?.getImageAttr()

        If (posterUrl?.contains(".gif", true) == true) {
            PosterUrl = image
                ?.attr("abs:data-wpfc-original-src")
                ?.takeIf { it.isNotBlank() }
                ?: posterUrl
        }

        PosterUrl = fixUrlNull(posterUrl)

        Val quality = getQualityFromString(
            Select("span.quality").text()
        )

        Return newMovieSearchResponse(
            Title,
            Href,
            TvType.NSFW
        ) {
            This.posterUrl = posterUrl
            This.quality = quality
        }
    }

    Private suspend fun getSearchPage(
        Query: String,
        Page: Int
    ): Pair<List<SearchResponse>, Boolean> {
        Val encodedQuery = URLEncoder.encode(
            Query.trim(),
            "UTF-8"
        ).replace("+", "%20")

        Val url = if (page <= 1) {
            "$mainUrl/search/$encodedQuery/"
        } else {
            "$mainUrl/search/$encodedQuery/page/$page/"
        }

        Val document = app.get(
            Url = url,
            Headers = headers
        ).document

        Val results = document.select("div.result-item")
            .mapNotNull { element ->
                Val anchor = element.selectFirst("div.title > a")
                    ?: return@mapNotNull null

                Val title = anchor.text()
                    .replace(Regex("\\(\\d{4}\\)"), "")
                    .trim()

                If (title.isBlank()) {
                    Return@mapNotNull null
                }

                Val href = fixUrlNull(anchor.attr("href"))
                    ?: return@mapNotNull null

                Val posterUrl = element
                    .selectFirst("img")
                    ?.getImageAttr()
                    ?.let { fixUrlNull(it) }

                NewMovieSearchResponse(
                    Title,
                    GetProperLink(href),
                    TvType.NSFW
                ) {
                    This.posterUrl = posterUrl
                }
            }

        Val hasNext = document.selectFirst(
            "a.next, a[rel=next], " +
                ".pagination a.next, " +
                "a.page-numbers.next"
        ) != null

        Return results to hasNext
    }

    Override suspend fun search(
        Query: String
    ): List<SearchResponse> {
        Return getSearchPage(query, 1).first
    }

    Override suspend fun search(
        Query: String,
        Page: Int
    ): SearchResponseList {
        Val (results, hasNext) = getSearchPage(query, page)

        Return newSearchResponseList(
            Results,
            HasNext = hasNext
        )
    }

    Override suspend fun load(url: String): LoadResponse? {
        Val document = app.get(
            Url = url,
            Headers = headers
        ).document

        Val title = document
            .selectFirst("div.data > h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        Var posterUrl = document
            .selectFirst("div.poster img")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }

        If (posterUrl.isNullOrEmpty()) {
            PosterUrl = fixUrlNull(
                Document.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content")
            )
        }

        Val description = document.selectFirst(
            "div[itemprop=description] p, div.wp-content p"
        )?.text()?.trim()

        Val year = document.selectFirst("span.date")
            ?.text()
            ?.substringAfterLast(", ")
            ?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)")
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        Val tags = document.select("div.sgeneros a")
            .map { it.text() }
            .filter { it.isNotBlank() }

        Val recommendations = document
            .select("div#single_relacionados article")
            .mapNotNull { element ->
                Val anchor = element.selectFirst("a")
                    ?: return@mapNotNull null

                Val recTitle = element.selectFirst("img")
                    ?.attr("alt")
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.text().trim()

                If (recTitle.isBlank()) {
                    Return@mapNotNull null
                }

                Val recUrl = fixUrlNull(anchor.attr("href"))
                    ?: return@mapNotNull null

                Val recPoster = element
                    .selectFirst("img")
                    ?.getImageAttr()
                    ?.let { fixUrlNull(it) }

                NewMovieSearchResponse(
                    RecTitle,
                    GetProperLink(recUrl),
                    TvType.NSFW
                ) {
                    This.posterUrl = recPoster
                }
            }

        Val episodes = document.select(
            "ul#playeroptionsul > li, " +
                "div.play-options li, " +
                ".sources-box li, " +
                ".dooplay_player_option, " +
                ".options li"
        ).mapNotNull { element ->
            Val name = element.selectFirst("span.title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: element.select("span").text().trim()
                    .ifBlank { element.text().trim() }

            Val type = element.attr("data-type")
                .ifBlank { element.attr("data-film") }
                .ifBlank { "movie" }

            Val post = element.attr("data-post")
                .ifBlank { element.attr("data-id") }
                .ifBlank { element.attr("data-movie") }
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            Val nume = element.attr("data-nume")
                .ifBlank { element.attr("data-episode") }
                .ifBlank { element.attr("data-seq") }
                .ifBlank { "1" }

            If (name.contains("trailer", true)) {
                Return@mapNotNull null
            }

            Val json = LinkData(
                Type = type,
                Post = post,
                Nume = nume
            ).toJson()

            NewEpisode(json) {
                This.name = name.ifBlank { "Server" }
            }
        }.distinctBy { it.name }

        Return newTvSeriesLoadResponse(
            Title,
            Url,
            TvType.TvSeries,
            Episodes
        ) {
            This.posterUrl = posterUrl
            This.plot = description
            This.year = year
            This.tags = tags
            This.recommendations = recommendations
        }
    }

    Override suspend fun loadLinks(
        Data: String,
        IsCasting: Boolean,
        SubtitleCallback: (SubtitleFile) -> Unit,
        Callback: (ExtractorLink) -> Unit
    ): Boolean {
        Var isSuccessful = false

        Try {
            Val loadData = tryParseJson<LinkData>(data) ?: return false
            Val post = loadData.post?.takeIf { it.isNotBlank() } ?: return false
            Val type = loadData.type?.takeIf { it.isNotBlank() } ?: "movie"
            Val nume = loadData.nume?.takeIf { it.isNotBlank() } ?: "1"

            Val apiUrls = listOf(
                "$mainUrl/wp-json/dooplayer/v2/$post/$type/$nume",
                "$mainUrl/wp-json/dooplayer/v1/$post/$type/$nume",
                "$mainUrl/wp-admin/admin-ajax.php?action=doo_player_ajax&post=$post&type=$type&nume=$nume"
            )

            Var iframe: String? = null

            For (apiUrl in apiUrls) {
                Val response = withContext(Dispatchers.IO) {
                    Try {
                        App.get(
                            Url = apiUrl,
                            Referer = mainUrl,
                            Headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
                        ).parsedSafe<ResponseHash>()
                    } catch (e: Exception) {
                        Null
                    }
                }

                Iframe = response?.embedUrl?.getIframe()
                If (!iframe.isNullOrBlank()) break
            }

            If (iframe.isNullOrBlank()) return false

            // Updated check to handle telly, telly.xin, and cdntelly / filemoon embeds seamlessly
            If (iframe.contains("telly", ignoreCase = true) || iframe.contains("telly.xin", ignoreCase = true) || iframe.contains("cdntelly", ignoreCase = true)) {
                Val playerDoc = withContext(Dispatchers.IO) {
                    Try {
                        App.get(url = iframe, referer = mainUrl, headers = headers).document
                    } catch (e: Exception) {
                        Null
                    }
                } ?: return false

                Val rawStreamUrl = playerDoc.selectFirst("script#player-loader")?.attr("data-stream-url")?.takeIf { it.isNotBlank() } 
                    ?: playerDoc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }

                If (!rawStreamUrl.isNullOrBlank()) {
                    Val streamUrl = resolveUrl(iframe, rawStreamUrl)
                    If (streamUrl.startsWith("http", true)) {
                        Val link = newExtractorLink(
                            Source = name,
                            Name = name,
                            Url = streamUrl,
                            Type = ExtractorLinkType.M3U8
                        ) {
                            This.referer = iframe
                            This.quality = Qualities.Unknown.value
                            This.headers = playerHeaders
                        }

                        Callback.invoke(link)
                        IsSuccessful = true
                    }
                }
            } 
            
            If (!isSuccessful && !iframe.contains("youtube", ignoreCase = true)) {
                Try {
                    IsSuccessful = loadExtractor(
                        Iframe,
                        MainUrl,
                        SubtitleCallback,
                        Callback
                    )
                } catch (e: Exception) {
                    E.printStackTrace()
                }
            }

        } catch (e: Exception) {
            E.printStackTrace()
        }

        Return isSuccessful
    }

    Private fun Element.getImageAttr(): String {
        Val candidates = listOf(
            "abs:data-wpfc-original-src",
            "abs:data-src",
            "abs:data-lazy-src",
            "abs:src"
        )

        For (attribute in candidates) {
            Val value = attr(attribute).trim()

            If (
                Value.isNotBlank() &&
                !value.startsWith("data:image", true)
            ) {
                Return value
            }
        }

        Val srcset = attr("abs:srcset")
            .substringBefore(",")
            .substringBefore(" ")
            .trim()

        Return srcset
    }

    Private fun String.getIframe(): String? {
        Val raw = trim()

        If (raw.isBlank()) return null

        Val iframeSrc = Jsoup.parse(raw)
            .selectFirst("iframe")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }

        Val candidate = iframeSrc
            ?: raw.takeIf {
                It.startsWith("http", true) ||
                    It.startsWith("//") ||
                    It.startsWith("/")
            }
            ?: return null

        Return resolveUrl(mainUrl, candidate)
            .takeIf { it.startsWith("http", true) }
    }

    Data class LinkData(
        Val type: String? = null,
        Val post: String? = null,
        Val nume: String? = null
    )

    Data class ResponseHash(
        @JsonProperty("embed_url")
        Val embedUrl: String? = null,

        Val type: String? = null
    )
}
