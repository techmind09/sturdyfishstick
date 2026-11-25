package com.sturdyfishstick.watchomovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import okhttp3.Request
import java.net.URI
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

// =========================================================================
// 0. Vidhost Stream API Extractor (Aagmaal)
// =========================================================================
object VidhostExtractor {
    data class StreamResponse(val streaming_url: String? = null)

    suspend fun resolve(
        sourceName: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("vidhost")) return false
        return try {
            val host = embedUrl.substringAfter("//").substringBefore("/")
            val filecode = embedUrl.trimEnd('/').substringAfterLast('/')
            if (host.isBlank() || filecode.isBlank()) return false

            val response = app.post(
                "https://$host/api/stream",
                json = mapOf(
                    "filecode" to filecode,
                    "device" to "web",
                    "codecs" to emptyList<String>()
                ),
                referer = embedUrl
            ).parsedSafe<StreamResponse>()

            val stream = response?.streaming_url?.takeIf { it.isNotBlank() } ?: return false
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = stream,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}

// =========================================================================
// 1. LuluStream / LuluVid / LuluVdo / LuluPvp Extractors (XXXTime)
// =========================================================================
open class LuluBase : ExtractorApi() {
    override val name = "LuluStream"
    override val mainUrl = "https://lulustream.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("lulustream")) return

        val embedUrl = url.replace("/d/", "/e/")
        val currentHost = try { URI(embedUrl).host ?: "lulustream.com" } catch (e: Exception) { "lulustream.com" }

        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://$currentHost/")
        )

        val response = app.get(embedUrl, headers = requestHeaders)
        val html = response.document
        val eval = html.selectFirst("script[type=text/javascript]:containsData(eval)")?.data() ?: ""
        val unpack = getAndUnpack(eval)

        val regex = Regex("""\{sources:\[\{file:"([^"]*)"""", setOf(RegexOption.IGNORE_CASE))
        val video = regex.find(unpack)?.groupValues?.getOrNull(1)
            ?: Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpack)?.groupValues?.getOrNull(1)
            ?: return

        if (video.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = video,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = requestHeaders
                    this.referer = "https://$currentHost/"
                }
            )
        }
    }
}

class LuluCustomStream : LuluBase() {
    override val name = "LuluStream"
    override val mainUrl = "https://lulustream.com"
}

class LuluVidCustom : LuluBase() {
    override val name = "LuluVid"
    override val mainUrl = "https://luluvid.com"
}

class LuluVdo : LuluBase() {
    override val name = "LuluVdo"
    override val mainUrl = "https://luluvdo.com"
}

class LuluPvp : LuluBase() {
    override val name = "LuluPvp"
    override val mainUrl = "https://lulupvp.com"
}

// =========================================================================
// 2. KtPlayer Extractor (WatchPorn Flashvars Deobfuscator + WebView)
// =========================================================================
data class KtPlayerSlot(
    @JsonProperty("key")   val key: String,
    @JsonProperty("url")   val url: String,
    @JsonProperty("label") val label: String? = null
)

object KtPlayerExtractor {

    private const val TAG         = "KtPlayerExtractor"
    private const val OBFUSCATED  = "function/0/"
    private const val HASH_LENGTH = 32

    private val FLASHVARS = Regex("""(video_(?:alt_)?url\d*(?:_text)?)\s*:\s*(['"])(.*?)\2""")
    private val LICENSE   = Regex("""license_code\s*:\s*(['"])([^'"]*)\1""")
    private val SLOT_KEY  = Regex("""^video_(?:alt_)?url\d*$""")
    private val MEDIA_URL = Regex("""/get_file/|remote_control\.php|\.(?:mp4|m4v|mov|mkv|webm|m3u8|mpd)(?:[/?#&]|$)""", RegexOption.IGNORE_CASE)
    private val ANY_URL   = Regex("""^https?://""")
    private val NEVER     = Regex("""(?!)""")

    private val DROPPED_HEADERS = setOf("host", "connection", "content-length", "accept-encoding", "range")

    private val SCRIPT = """
        (function () {
            try {
                var out = [];
                if (typeof flashvars !== 'undefined' && flashvars) {
                    for (var k in flashvars) {
                        if (!/^video_(alt_)?url\d*${'$'}/.test(k)) continue;
                        var v = flashvars[k];
                        if (typeof v !== 'string' || v.indexOf('http') !== 0) continue;
                        out.push({ key: k, url: v, label: flashvars[k + '_text'] || null });
                    }
                }
                var players = document.getElementsByTagName('video');
                for (var i = 0; i < players.length; i++) {
                    var src = players[i].currentSrc || players[i].src || '';
                    if (src.indexOf('http') === 0) out.push({ key: 'video_element', url: src, label: null });
                }
                return out.length ? JSON.stringify(out) : null;
            } catch (e) {
                return null;
            }
        })();
    """.trimIndent()

    private data class Resolved(val slots: List<KtPlayerSlot>, val request: Request?)

    suspend fun getLinks(
        sourceName: String,
        mainUrl: String,
        pageUrl: String,
        pageHtml: String,
        cookieHeaders: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("ktplayer")) return false

        val slots  = slotsFrom(pageHtml)
        val direct = slots.filter { isPlayable(it.url) }

        val resolved = if (direct.isEmpty()) resolveWithWebView(mainUrl, pageUrl, slots, cookieHeaders) else null
        val headers  = playbackHeaders(pageUrl, resolved?.request, cookieHeaders)

        val playable = (resolved?.slots?.filter { isPlayable(it.url) } ?: direct)
            .distinctBy { it.url }
            .sortedByDescending { getQualityFromName(it.label) }

        if (playable.isEmpty()) return false

        playable.forEach { slot ->
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name   = sourceName,
                    url    = slot.url,
                    type   = INFER_TYPE,
                    initializer = {
                        this.quality = getQualityFromName(slot.label)
                        this.referer = pageUrl
                        this.headers = headers
                    }
                )
            )
        }
        return true
    }

    private fun slotsFrom(html: String): List<KtPlayerSlot> {
        val vars = LinkedHashMap<String, String>()
        FLASHVARS.findAll(html).forEach { match ->
            vars.getOrPut(match.groupValues[1]) { match.groupValues[3].trim() }
        }
        val license = LICENSE.find(html)?.groupValues?.get(2)
        return vars.filterKeys { SLOT_KEY.matches(it) }
            .filterValues { it.isNotEmpty() }
            .mapNotNull { (key, url) ->
                val real = deobfuscate(url, license) ?: return@mapNotNull null
                KtPlayerSlot(key, real, vars["${key}_text"]?.ifEmpty { null })
            }
    }

    private fun deobfuscate(url: String, license: String?): String? {
        if (!url.startsWith(OBFUSCATED)) return url
        val raw   = url.removePrefix(OBFUSCATED)
        val token = licenseToken(license.orEmpty())
        if (token.size < HASH_LENGTH) return null

        val schemeEnd = raw.indexOf("://")
        val hostEnd   = if (schemeEnd < 0) -1 else raw.indexOf('/', schemeEnd + 3)
        if (hostEnd < 0) return null

        val rest  = raw.substring(hostEnd)
        val path  = rest.substringBefore("?")
        val parts = path.split("/").toMutableList()
        if (parts.size < 4 || parts[3].length < HASH_LENGTH) return null

        val hash    = parts[3].take(HASH_LENGTH)
        val indices = IntArray(HASH_LENGTH) { it }
        var accum   = 0

        for (source in HASH_LENGTH - 1 downTo 0) {
            accum += token[source]
            val target = (source + accum) % HASH_LENGTH
            val swap   = indices[source]
            indices[source] = indices[target]
            indices[target] = swap
        }

        parts[3] = indices.map { hash[it] }.joinToString("") + parts[3].substring(HASH_LENGTH)
        return raw.substring(0, hostEnd) + parts.joinToString("/") + rest.substring(path.length)
    }

    private fun licenseToken(licenseCode: String): List<Int> {
        val license = licenseCode.replace("$", "")
        if (license.isEmpty() || license.any { !it.isDigit() }) return emptyList()

        val digits = license.map { it - '0' }
        val masked = license.replace('0', '1')
        val center = masked.length / 2
        val front  = masked.take(center + 1).toLongOrNull() ?: return emptyList()
        val back   = masked.substring(center).toLongOrNull() ?: return emptyList()
        val scaled = (4 * abs(front - back)).toString().take(center + 1)

        val token = mutableListOf<Int>()
        scaled.forEachIndexed { index, char ->
            for (offset in 0 until 4) {
                val digit = digits.getOrNull(index + offset) ?: return emptyList()
                token += (digit + (char - '0')) % 10
            }
        }
        return token
    }

    private suspend fun resolveWithWebView(
        mainUrl: String,
        pageUrl: String,
        staticSlots: List<KtPlayerSlot>,
        cookieHeaders: Map<String, String>
    ): Resolved {
        val stale     = staticSlots.map { it.url }.toSet()
        val labels    = staticSlots.associate { it.key to it.label }
        val siteHost  = hostOf(mainUrl)
        val fresh     = AtomicReference<List<KtPlayerSlot>>(emptyList())
        val mediaHits = CopyOnWriteArrayList<Request>()

        val resolver = WebViewResolver(
            interceptUrl   = NEVER,
            additionalUrls = listOf(ANY_URL),
            userAgent      = null,
            useOkhttp      = false,
            script         = SCRIPT,
            scriptCallback = { payload ->
                val changed = parseSlots(payload).filter { it.url !in stale }
                if (changed.isNotEmpty()) {
                    fresh.set(changed.map { it.copy(label = it.label ?: labels[it.key]) })
                }
            },
            timeout        = 20_000L
        )

        val (_, seen) = resolver.resolveUsingWebView(
            url             = pageUrl,
            referer         = mainUrl,
            headers         = cookieHeaders,
            requestCallBack = { request ->
                val url = request.url.toString()
                if (isFreshMedia(url, siteHost, stale)) mediaHits.add(request)
                fresh.get().isNotEmpty() || mediaHits.isNotEmpty()
            }
        )

        val request = mediaHits.firstOrNull()
            ?: seen.firstOrNull { isFreshMedia(it.url.toString(), siteHost, stale) }

        val slots = fresh.get().ifEmpty {
            listOfNotNull(request?.let { KtPlayerSlot("video_request", it.url.toString()) })
        }
        return Resolved(slots, request)
    }

    private fun parseSlots(raw: String?): List<KtPlayerSlot> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.removeSurrounding("\"") == "null") return emptyList()
        val json = if (trimmed.startsWith("\"")) AppUtils.tryParseJson<String>(trimmed) ?: return emptyList() else trimmed
        return AppUtils.tryParseJson<List<KtPlayerSlot>>(json).orEmpty()
    }

    private fun playbackHeaders(
        pageUrl: String,
        request: Request?,
        cookieHeaders: Map<String, String>
    ): Map<String, String> = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
        put("Referer", pageUrl)
        putAll(cookieHeaders)
        request?.headers?.forEach { (key, value) ->
            if (key.lowercase() !in DROPPED_HEADERS && value.isNotBlank()) put(key, value)
        }
        if (request != null) WebViewResolver.webViewUserAgent?.let { put("User-Agent", it) }
    }

    private fun isPlayable(url: String): Boolean = url.startsWith("http") && MEDIA_URL.containsMatchIn(url)
    private fun isFreshMedia(url: String, siteHost: String, stale: Set<String>): Boolean =
        url !in stale && isPlayable(url) && isSameSite(url, siteHost)
    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").substringBefore(":").removePrefix("www.").lowercase()
    private fun isSameSite(url: String, siteHost: String): Boolean =
        hostOf(url).let { it == siteHost || it.endsWith(".$siteHost") }
}

// =========================================================================
// 3. TellyHD & Multi-Host Extractors (VidHide, Xtapes, Filemoon, Dood, BigWarp)
// =========================================================================
class Vidsp : VidhideExtractor() {
    override var mainUrl = "https://vidsp.lol"
}

class VidHideplus : VidhideExtractor() {
    override var mainUrl = "https://vidhideplus.com"
}

class VidHidedht : VidhideExtractor() {
    override var mainUrl = "https://dhtpre.com"
}

class Vidhidehub : VidhideExtractor() {
    override var mainUrl = "https://vidhidehub.com"
}

class Vidhidetoul : VidhideExtractor() {
    override var mainUrl = "https://toul.hair"
}

class Xtapes : VidhideExtractor() {
    override var mainUrl = "https://xtapes.porn"
    override var name = "Xtapes"
}

class FilemoonCustom : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("filemoon")) return null
        val doc = app.get(url).document
        val script = doc.selectFirst("script:containsData(eval)")?.data().toString()
        val unpacked = runCatching { getAndUnpack(script) }.getOrDefault(script)
        val link = doc.selectFirst("meta[property=og:video]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return null

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = INFER_TYPE
            ) {
                this.referer = url
                this.quality = Qualities.P1080.value
            }
        )
    }
}

open class Ds2play : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://ds2play.com"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String = "https://dood.wf/d/$id"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("doodstream")) return null
        val response0 = app.get(url).text
        val md5 = mainUrl + (Regex("/pass_md5/[^']*").find(response0)?.value ?: return null)
        val trueUrl = app.get(md5, referer = url).text + "zUEJeL3mUN?token=" + md5.substringAfterLast("/")
        val quality = Regex("\\d{3,4}p").find(response0.substringAfter("<title>").substringBefore("</title>"))?.groupValues?.getOrNull(0)
        return listOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = trueUrl,
                type = INFER_TYPE
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(quality)
            }
        )
    }
}

open class Bigwarp : ExtractorApi() {
    override val name = "Bigwarp"
    override val mainUrl = "https://bigwarp.io"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        if (!WatchOMoviesSettingsDialog.isExtractorEnabled("bigwarp")) return null
        val response = app.get(url).document
        val script = response.selectFirst("script:containsData(sources)")?.data().toString()
        Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(script)?.groupValues?.getOrNull(1)?.let { link ->
            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = INFER_TYPE
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.P1080.value
                }
            )
        }
        return null
    }
}

