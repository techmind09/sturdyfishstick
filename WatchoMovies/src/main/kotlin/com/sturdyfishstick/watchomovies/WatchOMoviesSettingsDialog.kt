package com.sturdyfishstick.watchomoviesindian

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object WatchOMoviesSettingsDialog {

    const val LOG_TAG = "WatchOMoviesDiag"
    private const val PREFS_NAME = "watchomovies_master_ultra_v5"
    private const val CF_VERIFIED_MARKER = "__CF_VERIFIED__"

    private const val KEY_SRV_PREFIX = "wom_srv_"
    private const val KEY_DOMAIN_PREFIX = "wom_dom_"
    private const val KEY_EXT_PREFIX = "wom_ext_"
    private const val KEY_SRV_ORDER = "wom_srv_order_csv"
    private const val KEY_CF_COOKIES = "wom_cf_cookies_json"
    private const val KEY_USE_WEBVIEW_CF = "wom_use_webview_cf"
    private const val KEY_CF_AUTO_RETRY = "wom_cf_auto_retry"
    private const val KEY_THEME = "wom_ui_theme"

    // Only 3 Master Category Group Filters
    private const val KEY_GRP_INDIAN_OTT = "wom_grp_indian_ott"
    private const val KEY_GRP_STUDIOS = "wom_grp_studios"
    private const val KEY_GRP_TUBES = "wom_grp_tubes"
    private const val KEY_SHUFFLE_HOME = "wom_shuffle_home"
    private const val KEY_ROWS_PER_SERVER = "wom_rows_per_server"

    // Quality & Performance
    private const val KEY_Q_4K = "wom_q_4k"
    private const val KEY_Q_1080 = "wom_q_1080"
    private const val KEY_Q_720 = "wom_q_720"
    private const val KEY_Q_480 = "wom_q_480"
    private const val KEY_CONCURRENCY = "wom_concurrency"
    private const val KEY_TIMEOUT_SEC = "wom_timeout_sec"

    const val CF_BYPASS_UA =
        "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class ThemePalette(
        val id: String,
        val name: String,
        val primaryHex: String,
        val gradStart: String,
        val gradMid: String,
        val gradEnd: String
    )

    val UI_THEMES = listOf(
        ThemePalette("crimson", "Crimson Red", "#E11D48", "#230B14", "#33101F", "#1B0E24"),
        ThemePalette("purple", "Neon Purple", "#7C3AED", "#0B192C", "#1E153D", "#08272E"),
        ThemePalette("blue", "Cyber Blue", "#2563EB", "#09182D", "#0E294B", "#082538"),
        ThemePalette("emerald", "Emerald", "#059669", "#071F1B", "#0C2D24", "#08242B"),
        ThemePalette("amber", "Gold Amber", "#D97706", "#211608", "#2E1C0A", "#1A1423")
    )

    data class ServerItem(
        val key: String,
        val name: String,
        val tag: String,
        val defaultUrl: String,
        val accentHex: String,
        val ottCount: Int,
        val studioCount: Int,
        val tubeCount: Int,
        val groups: Set<String>
    ) {
        val totalCats: Int get() = ottCount + studioCount + tubeCount
    }

    data class ExtractorItem(
        val key: String,
        val name: String,
        val hosts: String,
        val tag: String
    )

    data class ServerHealthInfo(
        val key: String,
        val status: String,
        val isHealthy: Boolean,
        val httpCode: Int = 0,
        val latencyMs: Long = -1L,
        val detail: String = "",
        val updatedAt: String = ""
    )

    // All 11 Servers = 144 Indian OTT + 161 Studios 18+ + 102 Tubes = 407 Total Categories
    val SERVERS = listOf(
        ServerItem("watchomovies", "WatchOMovies", "OTT • STUDIOS", "https://watchomovies.social", "#F43F5E", 34, 20, 2, setOf("Indian OTT", "Studios 18+", "Tubes")),
        ServerItem("tellyhd", "TellyHD Indian", "INDIAN OTT", "https://tellyhd.eu", "#FB7185", 53, 0, 0, setOf("Indian OTT")),
        ServerItem("prmovies", "PrmoviesIndian", "INDIAN OTT", "https://prmovies.ventures", "#FB923C", 36, 0, 0, setOf("Indian OTT")),
        ServerItem("aagmaal", "Aagmaal", "INDIAN OTT", "https://aagmaall.com", "#EC4899", 20, 0, 0, setOf("Indian OTT")),
        ServerItem("brazzpw", "BrazzPW", "STUDIOS • TUBE", "https://brazzpw.xyz", "#38BDF8", 0, 20, 15, setOf("Studios 18+", "Tubes")),
        ServerItem("xmoviesforyou", "XMoviesForYou", "STUDIOS 18+", "https://xmoviesforyou.com", "#A855F7", 0, 53, 0, setOf("Studios 18+")),
        ServerItem("xxxtime", "XXXTime", "STUDIOS • TUBE", "https://xxxtime.video", "#F59E0B", 0, 5, 43, setOf("Studios 18+", "Tubes")),
        ServerItem("watchporn", "WatchPorn", "STUDIOS • TUBE", "https://watchporn.to", "#10B981", 0, 14, 2, setOf("Studios 18+", "Tubes")),
        ServerItem("taboodaddy", "Taboodaddy", "STUDIOS • TUBE", "https://taboodaddy.com", "#C084FC", 0, 10, 13, setOf("Studios 18+", "Tubes")),
        ServerItem("pvzija", "Pvzija (BluMovies)", "ALL 3 GROUPS", "https://xxxparodyhd.net", "#22D3EE", 1, 39, 2, setOf("Indian OTT", "Studios 18+", "Tubes")),
        ServerItem("freepornvideos", "Free Porn Videos", "TUBE", "https://www.freepornvideos.xxx", "#FACC15", 0, 0, 25, setOf("Tubes"))
    )

    val EXTRACTORS = listOf(
        ExtractorItem("vidhost", "Vidhost Stream API", "vidhost • aagmaall.com (/api/stream)", "M3U8"),
        ExtractorItem("speedostream", "SpeedoStream & VidSrc", "speedostream • vidsrc • prmovies/watchomovies", "M3U8"),
        ExtractorItem("lulustream", "LuluStream / Vid / Vdo / Pvp", "lulustream • luluvid • luluvdo • lulupvp", "M3U8"),
        ExtractorItem("ktplayer", "KtPlayer Deobfuscator + WebView", "watchporn.to • flashvars license solver", "MP4/HLS"),
        ExtractorItem("vidhide", "VidHide / Xtapes Mirrors", "vidsp • vidhideplus • dhtpre • toul • xtapes", "HLS"),
        ExtractorItem("filemoon", "FileMoon Extractor", "filemoon.sx • cdntelly", "EMBED"),
        ExtractorItem("doodstream", "DoodStream / Ds2play", "ds2play.com • dood.wf", "MP4"),
        ExtractorItem("bigwarp", "BigWarp Direct", "bigwarp.io", "HLS/MP4"),
        ExtractorItem("streamtape", "StreamTape / MixDrop", "streamtape • mixdrop mirrors", "MIRROR")
    )

    private val AVATAR_PALETTE = listOf(
        "#F43F5E", "#38BDF8", "#A855F7", "#10B981",
        "#F59E0B", "#22D3EE", "#EC4899", "#FACC15"
    )

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    private val diagLogs = CopyOnWriteArrayList<String>()
    private val serverHealthMap = ConcurrentHashMap<String, ServerHealthInfo>()

    fun bindContext(ctx: Context?) {
        if (ctx != null) appContext = ctx.applicationContext
    }

    private fun prefs(ctx: Context? = appContext): SharedPreferences? {
        val c = ctx ?: appContext ?: return null
        bindContext(c)
        return cachedPrefs ?: c.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { cachedPrefs = it }
    }

    private fun nowTs(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    fun logDiag(tag: String, msg: String) {
        val line = "[${nowTs()}] [${tag.uppercase()}] $msg"
        diagLogs.add(line)
        while (diagLogs.size > 200) diagLogs.removeAt(0)
        Log.d(LOG_TAG, line)
    }

    // =========================================================================
    // Persistent Getters & Setters (Domains, 3 Category Groups, Toggles)
    // =========================================================================
    fun getServerDomain(serverKey: String, ctx: Context? = appContext): String {
        val srv = SERVERS.firstOrNull { it.key.equals(serverKey, true) }
        val fallback = srv?.defaultUrl ?: "https://watchomovies.social"
        val saved = prefs(ctx)?.getString("$KEY_DOMAIN_PREFIX${serverKey.lowercase()}", null)?.trim().orEmpty()
        return saved.removeSuffix("/").ifBlank { fallback }
    }

    fun setServerDomain(ctx: Context, serverKey: String, url: String) {
        val srv = SERVERS.firstOrNull { it.key.equals(serverKey, true) }
        val fallback = srv?.defaultUrl ?: "https://watchomovies.social"
        val cleaned = url.trim().removeSuffix("/").let {
            when {
                it.isBlank() -> fallback
                it.startsWith("http://") || it.startsWith("https://") -> it
                else -> "https://$it"
            }
        }
        prefs(ctx)?.edit()?.putString("$KEY_DOMAIN_PREFIX${serverKey.lowercase()}", cleaned)?.apply()
        logDiag("DOMAIN", "${serverKey.uppercase()} -> $cleaned")
    }

    fun getSelectedTheme(ctx: Context): ThemePalette {
        val id = prefs(ctx)?.getString(KEY_THEME, "crimson") ?: "crimson"
        return UI_THEMES.firstOrNull { it.id == id } ?: UI_THEMES.first()
    }

    fun setSelectedTheme(ctx: Context, id: String) {
        prefs(ctx)?.edit()?.putString(KEY_THEME, id)?.apply()
    }

    fun isGroupEnabled(group: String, ctx: Context? = appContext): Boolean {
        val p = prefs(ctx) ?: return true
        return when (group) {
            "Indian OTT" -> p.getBoolean(KEY_GRP_INDIAN_OTT, true)
            "Studios 18+" -> p.getBoolean(KEY_GRP_STUDIOS, true)
            "Tubes" -> p.getBoolean(KEY_GRP_TUBES, true)
            else -> true
        }
    }

    fun setGroupEnabled(ctx: Context, group: String, enabled: Boolean) {
        val key = when (group) {
            "Indian OTT" -> KEY_GRP_INDIAN_OTT
            "Studios 18+" -> KEY_GRP_STUDIOS
            "Tubes" -> KEY_GRP_TUBES
            else -> return
        }
        prefs(ctx)?.edit()?.putBoolean(key, enabled)?.apply()
    }

    fun isShuffleHome(ctx: Context? = appContext): Boolean =
        prefs(ctx)?.getBoolean(KEY_SHUFFLE_HOME, false) ?: false

    fun setShuffleHome(ctx: Context, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_SHUFFLE_HOME, enabled)?.apply()
    }

    // Default 60 = ALL categories included (nothing missing)
    fun getRowsPerServer(ctx: Context? = appContext): Int =
        prefs(ctx)?.getInt(KEY_ROWS_PER_SERVER, 60) ?: 60

    fun setRowsPerServer(ctx: Context, count: Int) {
        prefs(ctx)?.edit()?.putInt(KEY_ROWS_PER_SERVER, count.coerceIn(5, 60))?.apply()
    }

    fun isServerRawChecked(key: String, ctx: Context? = appContext): Boolean =
        prefs(ctx)?.getBoolean("$KEY_SRV_PREFIX${key.lowercase()}", true) ?: true

    fun isServerEnabled(key: String, ctx: Context? = appContext): Boolean {
        if (!isServerRawChecked(key, ctx)) return false
        val srv = SERVERS.firstOrNull { it.key.equals(key, true) } ?: return true
        return srv.groups.any { isGroupEnabled(it, ctx) }
    }

    fun setServerEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean("$KEY_SRV_PREFIX${key.lowercase()}", enabled)?.apply()
    }

    fun getServerOrder(ctx: Context? = appContext): List<String> {
        val allKeys = SERVERS.map { it.key }
        val saved = prefs(ctx)?.getString(KEY_SRV_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: return allKeys
        val valid = saved.filter { it in allKeys }
        return valid + (allKeys - valid.toSet())
    }

    fun saveServerOrder(ctx: Context, orderedKeys: List<String>) {
        prefs(ctx)?.edit()?.putString(KEY_SRV_ORDER, orderedKeys.joinToString(","))?.apply()
    }

    fun getOrderedEnabledServers(ctx: Context? = appContext): List<ServerItem> {
        val orderMap = getServerOrder(ctx).withIndex().associate { it.value to it.index }
        return SERVERS
            .sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
            .filter { isServerEnabled(it.key, ctx) }
    }

    fun isExtractorEnabled(key: String, ctx: Context? = appContext): Boolean =
        prefs(ctx)?.getBoolean("$KEY_EXT_PREFIX${key.lowercase()}", true) ?: true

    fun setExtractorEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean("$KEY_EXT_PREFIX${key.lowercase()}", enabled)?.apply()
    }

    fun isQuality4kEnabled(ctx: Context? = appContext): Boolean = prefs(ctx)?.getBoolean(KEY_Q_4K, true) ?: true
    fun setQuality4kEnabled(ctx: Context, v: Boolean) = prefs(ctx)?.edit()?.putBoolean(KEY_Q_4K, v)?.apply()

    fun isQuality1080Enabled(ctx: Context? = appContext): Boolean = prefs(ctx)?.getBoolean(KEY_Q_1080, true) ?: true
    fun setQuality1080Enabled(ctx: Context, v: Boolean) = prefs(ctx)?.edit()?.putBoolean(KEY_Q_1080, v)?.apply()

    fun isQuality720Enabled(ctx: Context? = appContext): Boolean = prefs(ctx)?.getBoolean(KEY_Q_720, true) ?: true
    fun setQuality720Enabled(ctx: Context, v: Boolean) = prefs(ctx)?.edit()?.putBoolean(KEY_Q_720, v)?.apply()

    fun isQuality480Enabled(ctx: Context? = appContext): Boolean = prefs(ctx)?.getBoolean(KEY_Q_480, true) ?: true
    fun setQuality480Enabled(ctx: Context, v: Boolean) = prefs(ctx)?.edit()?.putBoolean(KEY_Q_480, v)?.apply()

    fun getConcurrency(ctx: Context? = appContext): Int = prefs(ctx)?.getInt(KEY_CONCURRENCY, 6) ?: 6
    fun setConcurrency(ctx: Context, v: Int) = prefs(ctx)?.edit()?.putInt(KEY_CONCURRENCY, v.coerceIn(2, 15))?.apply()

    fun getTimeoutSeconds(ctx: Context? = appContext): Int = prefs(ctx)?.getInt(KEY_TIMEOUT_SEC, 12) ?: 12
    fun setTimeoutSeconds(ctx: Context, v: Int) = prefs(ctx)?.edit()?.putInt(KEY_TIMEOUT_SEC, v.coerceIn(5, 45))?.apply()

    fun isUseWebViewCf(ctx: Context? = appContext): Boolean = prefs(ctx)?.getBoolean(KEY_USE_WEBVIEW_CF, true) ?: true
    fun setUseWebViewCf(ctx: Context, v: Boolean) = prefs(ctx)?.edit()?.putBoolean(KEY_USE_WEBVIEW_CF, v)?.apply()

    fun isCfAutoRetry(ctx: Context? = appContext): Boolean = prefs(ctx)?.getBoolean(KEY_CF_AUTO_RETRY, true) ?: true
    fun setCfAutoRetry(ctx: Context, v: Boolean) = prefs(ctx)?.edit()?.putBoolean(KEY_CF_AUTO_RETRY, v)?.apply()

    // =========================================================================
    // Cloudflare Cookie Management
    // =========================================================================
    private fun extractHostKey(domainOrUrl: String): String = runCatching {
        val raw = if (domainOrUrl.startsWith("http")) domainOrUrl else "https://$domainOrUrl"
        URL(raw).host.lowercase().removePrefix("www.")
    }.getOrDefault(domainOrUrl.lowercase())

    fun saveCookieForDomain(ctx: Context, domainOrUrl: String, cookie: String) {
        val host = extractHostKey(domainOrUrl)
        val map = getSavedCookiesMap(ctx).toMutableMap()
        if (cookie.isBlank()) map.remove(host) else map[host] = cookie
        prefs(ctx)?.edit()?.putString(KEY_CF_COOKIES, JSONObject(map as Map<*, *>).toString())?.apply()
    }

    fun getSavedCookiesMap(ctx: Context? = appContext): Map<String, String> {
        val raw = prefs(ctx)?.getString(KEY_CF_COOKIES, "{}") ?: "{}"
        val out = linkedMapOf<String, String>()
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { k ->
                val v = obj.optString(k)
                if (v.isNotBlank()) out[k] = v
            }
        }
        return out
    }

    fun getCookieForDomain(urlOrDomain: String, ctx: Context? = appContext): String? {
        if (!isUseWebViewCf(ctx)) return null
        val map = getSavedCookiesMap(ctx)
        if (map.isEmpty()) return null
        val host = extractHostKey(urlOrDomain)
        val raw = map[host] ?: map.entries.firstOrNull { host.endsWith(it.key) || it.key.endsWith(host) }?.value
        return raw?.takeIf { it.isNotBlank() && it != CF_VERIFIED_MARKER }
    }

    fun clearAllCfCookies(ctx: Context) {
        prefs(ctx)?.edit()?.remove(KEY_CF_COOKIES)?.apply()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        logDiag("CF", "All Cloudflare cookies cleared")
    }

    // =========================================================================
    // Live Server Health Probe
    // =========================================================================
    private suspend fun probeServerLive(ctx: Context, srv: ServerItem): ServerHealthInfo = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val targetUrl = getServerDomain(srv.key, ctx)
        val tMs = (getTimeoutSeconds(ctx)).coerceIn(5, 12) * 1000
        try {
            val conn = (URL("$targetUrl/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = tMs
                readTimeout = tMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CF_BYPASS_UA)
                getCookieForDomain(targetUrl, ctx)?.let { setRequestProperty("Cookie", it) }
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - start
            runCatching { conn.inputStream?.close() }
            runCatching { conn.errorStream?.close() }
            conn.disconnect()
            val ok = code in 200..399 || code == 405
            val status = when {
                ok -> "LIVE"
                code == 403 || code == 503 -> "CF $code"
                else -> "HTTP $code"
            }
            val info = ServerHealthInfo(srv.key, status, ok, code, ms, targetUrl, nowTs())
            serverHealthMap[srv.key] = info
            logDiag(if (ok) "PROBE" else "CF", "${srv.name}: $status (${ms}ms)")
            info
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - start
            val info = ServerHealthInfo(srv.key, "DOWN", false, 0, ms, e.message ?: "Error", nowTs())
            serverHealthMap[srv.key] = info
            logDiag("ERROR", "${srv.name} probe failed: ${e.message}")
            info
        }
    }

    // =========================================================================
    // MovieFox Glass & Neon Design System Helpers
    // =========================================================================
    private fun resolveActivityContext(rawCtx: Context): Context {
        if (rawCtx is Activity) return rawCtx
        var base = (rawCtx as? ContextWrapper)?.baseContext
        while (base != null) {
            if (base is Activity) return base
            base = (base as? ContextWrapper)?.baseContext
        }
        return rawCtx
    }

    private fun isContextAlive(ctx: Context?): Boolean {
        if (ctx == null) return false
        if (ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)) return false
        return true
    }

    private fun safeColor(hex: String, fallback: String = "#94A3B8"): Int =
        runCatching { Color.parseColor(hex) }.getOrElse { Color.parseColor(fallback) }

    private fun alphaColor(hex: String, alpha: Int): Int {
        val base = safeColor(hex)
        return Color.argb(alpha.coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun lightenColor(hex: String, factor: Float = 0.22f): Int {
        val c = safeColor(hex)
        return Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * factor).toInt().coerceIn(0, 255),
            (Color.green(c) + (255 - Color.green(c)) * factor).toInt().coerceIn(0, 255),
            (Color.blue(c) + (255 - Color.blue(c)) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun darkenColor(hex: String, factor: Float = 0.22f): Int {
        val c = safeColor(hex)
        return Color.rgb(
            (Color.red(c) * (1f - factor)).toInt().coerceIn(0, 255),
            (Color.green(c) * (1f - factor)).toInt().coerceIn(0, 255),
            (Color.blue(c) * (1f - factor)).toInt().coerceIn(0, 255)
        )
    }

    private fun safeToast(ctx: Context?, msg: String) {
        if (!isContextAlive(ctx)) return
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(ctx: Context, px: Int): Int =
        (px * ctx.resources.displayMetrics.density).toInt()

    private fun dpf(ctx: Context, px: Float): Float =
        px * ctx.resources.displayMetrics.density

    private fun cardBg(
        bgHex: String = "#111624",
        strokeHex: String = "#232D45",
        radius: Float = 20f,
        strokeWidth: Int = 2
    ): GradientDrawable = GradientDrawable().apply {
        setColor(safeColor(bgHex, "#111624"))
        cornerRadius = radius
        setStroke(strokeWidth, safeColor(strokeHex, "#232D45"))
    }

    private fun glassCardBg(
        topHex: String = "#151C2E",
        bottomHex: String = "#0E1320",
        strokeColor: Int = safeColor("#26334D"),
        radius: Float = 20f,
        strokeWidth: Int = 2
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(safeColor(topHex), safeColor(bottomHex))
    ).apply {
        cornerRadius = radius
        setStroke(strokeWidth, strokeColor)
    }

    private fun pillBg(bgHex: String, radius: Float = 14f): GradientDrawable = GradientDrawable().apply {
        setColor(safeColor(bgHex, "#1C2230"))
        cornerRadius = radius
    }

    private fun tintBadgeBg(accentHex: String, radius: Float = 10f, bgAlpha: Int = 36, strokeAlpha: Int = 95): GradientDrawable =
        GradientDrawable().apply {
            setColor(alphaColor(accentHex, bgAlpha))
            cornerRadius = radius
            setStroke(2, alphaColor(accentHex, strokeAlpha))
        }

    private fun neonGradBtn(primaryHex: String, radius: Float = 16f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(lightenColor(primaryHex, 0.14f), safeColor(primaryHex), darkenColor(primaryHex, 0.14f))
        ).apply {
            cornerRadius = radius
            setStroke(2, alphaColor("#FFFFFF", 42))
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressAnim(view: View) {
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(85).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun monogramOf(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
            name.length >= 2 -> name.take(2).uppercase()
            else -> "WM"
        }
    }

    private fun makeSectionLabel(ctx: Context, textStr: String, accentHex: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 8)
                bottomMargin = dp(ctx, 6)
            }
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 13)).apply { marginEnd = dp(ctx, 7) }
                background = pillBg(accentHex, 4f)
            })
            addView(TextView(ctx).apply {
                text = textStr.uppercase()
                textSize = 9.5f
                letterSpacing = 0.08f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#94A3B8"))
            })
        }
    }

    private class NeonSwitch(
        ctx: Context,
        initialChecked: Boolean,
        private var accentHex: String
    ) : FrameLayout(ctx) {

        private val trackW = dp(ctx, 44)
        private val trackH = dp(ctx, 24)
        private val thumbSize = dp(ctx, 18)
        private val thumbPad = dp(ctx, 3)
        private val travelX = (trackW - thumbSize - thumbPad * 2).toFloat()

        private val thumbView: View
        var isChecked: Boolean = initialChecked
            private set
        var onCheckedChange: ((Boolean) -> Unit)? = null

        init {
            layoutParams = LinearLayout.LayoutParams(trackW, trackH)
            isClickable = true
            isFocusable = true

            thumbView = View(ctx).apply {
                layoutParams = LayoutParams(thumbSize, thumbSize, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = thumbPad
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                elevation = dpf(ctx, 2f)
            }
            addView(thumbView)
            applyVisualState(animated = false)
            setOnClickListener { toggle() }
        }

        fun updateAccent(newHex: String) {
            accentHex = newHex
            applyVisualState(animated = false)
        }

        fun setCheckedSilent(checked: Boolean) {
            isChecked = checked
            applyVisualState(animated = false)
        }

        fun setChecked(checked: Boolean, animate: Boolean = true) {
            if (isChecked == checked) return
            isChecked = checked
            applyVisualState(animated = animate)
            onCheckedChange?.invoke(isChecked)
        }

        fun toggle() = setChecked(!isChecked, animate = true)

        private fun applyVisualState(animated: Boolean) {
            background = GradientDrawable().apply {
                cornerRadius = trackH / 2f
                if (isChecked) {
                    setColor(safeColor(accentHex, "#E11D48"))
                    setStroke(2, alphaColor("#FFFFFF", 55))
                } else {
                    setColor(safeColor("#1B2335"))
                    setStroke(2, safeColor("#2E3B56"))
                }
            }
            val targetX = if (isChecked) travelX else 0f
            thumbView.animate().cancel()
            if (animated) {
                thumbView.animate().translationX(targetX).setDuration(150).start()
            } else {
                thumbView.translationX = targetX
            }
            thumbView.alpha = if (isChecked) 1f else 0.78f
        }
    }

    // =========================================================================
    // Custom Domain / Mirror Editor Dialog for Any Server
    // =========================================================================
    private fun openDomainEditorDialog(
        ctx: Context,
        srv: ServerItem,
        accentHex: String,
        onSaved: () -> Unit
    ) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0B1120", "#070B14", alphaColor(accentHex, 130), dpf(ctx, 18f), 2)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        }
        box.addView(TextView(ctx).apply {
            text = "🌐 ${srv.name} Mirror Domain"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(ctx).apply {
            text = "Default: ${srv.defaultUrl} • ${srv.totalCats} categories"
            textSize = 10f
            setTextColor(safeColor("#94A3B8"))
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 10))
        })
        val input = EditText(ctx).apply {
            setText(getServerDomain(srv.key, ctx))
            hint = srv.defaultUrl
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            background = cardBg("#0F172A", "#334155", dpf(ctx, 11f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 42)).apply {
                bottomMargin = dp(ctx, 12)
            }
        }
        box.addView(input)

        val dlg = AlertDialog.Builder(ctx).setView(box).create()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(ctx).apply {
            text = "RESET"
            textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#1E293B", "#334155", dpf(ctx, 10f), 1)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 6) }
            setOnClickListener {
                setServerDomain(ctx, srv.key, srv.defaultUrl)
                onSaved()
                safeToast(ctx, "${srv.name} reset to default")
                dlg.dismiss()
            }
        })
        row.addView(Button(ctx).apply {
            text = "SAVE URL"
            textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn(accentHex, dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1.3f)
            setOnClickListener {
                setServerDomain(ctx, srv.key, input.text.toString())
                onSaved()
                safeToast(ctx, "${srv.name} domain saved!")
                dlg.dismiss()
            }
        })
        box.addView(row)
        runCatching {
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dlg.show()
        }
    }

    // =========================================================================
    // Multi-Server WebView Cloudflare Solver
    // =========================================================================
    @SuppressLint("SetJavaScriptEnabled")
    fun openCfBypassQueue(
        rawCtx: Context,
        targets: List<ServerItem>,
        onCompleted: (() -> Unit)? = null
    ) {
        val ctx = resolveActivityContext(rawCtx)
        if (!isContextAlive(ctx) || targets.isEmpty()) return
        bindContext(ctx)

        val handler = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val stepToken = AtomicInteger(0)
        var dialogRef: Dialog? = null
        var webViewRef: WebView? = null
        var idx = 0
        var savedCount = 0

        val titleTv = TextView(ctx).apply {
            textSize = 14f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        }
        val statusTv = TextView(ctx).apply {
            textSize = 10.5f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            setTextColor(safeColor("#93C5FD")); setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 15
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4)).apply {
                bottomMargin = dp(ctx, 8)
            }
        }

        fun currentTarget(): ServerItem = targets[idx.coerceIn(0, targets.lastIndex)]
        fun currentTargetUrl(): String = getServerDomain(currentTarget().key, ctx)

        fun saveDomainState(url: String?, requireClearance: Boolean): Boolean {
            return runCatching {
                val cm = CookieManager.getInstance()
                cm.flush()
                val targetUrl = currentTargetUrl()
                val cookie = cm.getCookie(url ?: targetUrl).orEmpty().ifBlank { cm.getCookie(targetUrl).orEmpty() }
                val hasClearance = cookie.contains("cf_clearance", true) || cookie.contains("__cf_bm", true)
                when {
                    hasClearance -> {
                        saveCookieForDomain(ctx, targetUrl, cookie)
                        logDiag("CF", "${currentTarget().name}: clearance saved")
                        true
                    }
                    !requireClearance -> {
                        saveCookieForDomain(ctx, targetUrl, cookie.ifBlank { CF_VERIFIED_MARKER })
                        true
                    }
                    else -> false
                }
            }.getOrDefault(false)
        }

        fun closeAll() {
            if (!closed.compareAndSet(false, true)) return
            stepToken.incrementAndGet()
            handler.removeCallbacksAndMessages(null)
            runCatching { webViewRef?.stopLoading(); webViewRef?.destroy() }
            webViewRef = null
            runCatching { dialogRef?.dismiss() }
            dialogRef = null
            if (savedCount > 0) safeToast(ctx, "Verified ($savedCount/${targets.size})")
            onCompleted?.invoke()
        }

        fun loadCurrentTarget() {
            if (closed.get()) return
            if (idx >= targets.size) { closeAll(); return }
            stepToken.incrementAndGet()
            val srv = currentTarget()
            val url = currentTargetUrl()
            titleTv.text = "🛡️ CF Solver (${idx + 1}/${targets.size}) • ${srv.name}"
            statusTv.text = "Loading $url..."
            statusTv.setTextColor(safeColor("#93C5FD"))
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 15
            webViewRef?.stopLoading()
            webViewRef?.loadUrl(url)
        }

        fun advanceNext(expectedToken: Int, markSaved: Boolean) {
            if (closed.get()) return
            if (!stepToken.compareAndSet(expectedToken, expectedToken + 1)) return
            if (markSaved) savedCount++
            idx++
            if (idx >= targets.size) closeAll() else loadCurrentTarget()
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0B1120", "#070B14", alphaColor("#38BDF8", 110), dpf(ctx, 20f), 2)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        container.addView(titleTv); container.addView(statusTv); container.addView(progressBar)

        val wvHeight = (ctx.resources.displayMetrics.heightPixels * 0.54f).toInt().coerceAtLeast(dp(ctx, 290))
        val wv = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wvHeight).apply { bottomMargin = dp(ctx, 10) }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = CF_BYPASS_UA
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    if (newProgress >= 100) progressBar.visibility = View.INVISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    val token = stepToken.get()
                    if (saveDomainState(url, requireClearance = true)) {
                        statusTv.text = "✓ CF Cleared for ${currentTarget().name}!"
                        statusTv.setTextColor(safeColor("#6EE7B7"))
                        handler.postDelayed({ advanceNext(token, true) }, 450L)
                    } else {
                        statusTv.text = "Solve challenge if visible, or tap ✓ Save & Next"
                        statusTv.setTextColor(safeColor("#FDE68A"))
                    }
                }
            }
        }
        webViewRef = wv
        container.addView(wv)

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(ctx).apply {
            text = "⚡ CF Click"; textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 5) }
            attachPressAnim(this)
            setOnClickListener {
                wv.evaluateJavascript(
                    """
                    (function(){
                        var cf = document.querySelector('#challenge-stage input[type=checkbox], .cf-turnstile input, .cb-i');
                        if (cf) { cf.click(); return 'CLICKED'; }
                        var ifr = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                        if (ifr) {
                            var r = ifr.getBoundingClientRect();
                            return 'IFRAME:' + Math.round(r.left + 32) + ':' + Math.round(r.top + r.height / 2);
                        }
                        return 'NONE';
                    })();
                    """.trimIndent()
                ) { res ->
                    val clean = res?.replace("\"", "")?.trim().orEmpty()
                    if (clean.startsWith("IFRAME:")) {
                        val parts = clean.split(":")
                        val d = ctx.resources.displayMetrics.density
                        val x = (parts.getOrNull(1)?.toFloatOrNull() ?: 80f) * d
                        val y = (parts.getOrNull(2)?.toFloatOrNull() ?: 160f) * d
                        val now = SystemClock.uptimeMillis()
                        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                        val up = MotionEvent.obtain(now, now + 80L, MotionEvent.ACTION_UP, x, y, 0)
                        wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up)
                        down.recycle(); up.recycle()
                    }
                }
            }
        })
        btnRow.addView(Button(ctx).apply {
            text = if (targets.size > 1) "✓ Save & Next" else "✓ Save"
            textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#2563EB", dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1.15f).apply { marginEnd = dp(ctx, 5) }
            attachPressAnim(this)
            setOnClickListener {
                val token = stepToken.get()
                val ok = saveDomainState(wv.url, requireClearance = false)
                advanceNext(token, ok)
            }
        })
        if (targets.size > 1) {
            btnRow.addView(Button(ctx).apply {
                text = "Skip"; textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = cardBg("#1E293B", "#334155", dpf(ctx, 10f), 1)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 0.7f).apply { marginEnd = dp(ctx, 5) }
                attachPressAnim(this)
                setOnClickListener { advanceNext(stepToken.get(), false) }
            })
        }
        btnRow.addView(Button(ctx).apply {
            text = "Close"; textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#141C2B", "#2A3650", dpf(ctx, 10f), 1)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 0.7f)
            attachPressAnim(this)
            setOnClickListener { closeAll() }
        })
        container.addView(btnRow)

        val dlg = AlertDialog.Builder(ctx).setView(container).setCancelable(false).create()
        dialogRef = dlg
        dlg.setOnDismissListener { if (!closed.get()) closeAll() }

        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dlg.show()
            val width = (ctx.resources.displayMetrics.widthPixels * 0.95).toInt()
            dlg.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            loadCurrentTarget()
        }
    }

    // =========================================================================
    // Server List Adapter
    // =========================================================================
    private class ServerListAdapter(
        private val ctx: Context,
        private val getAccentHex: () -> String,
        private val onToggleChanged: () -> Unit,
        private val onEditDomain: (ServerItem) -> Unit,
        private val onMoveUp: (ServerItem) -> Unit,
        private val onMoveDown: (ServerItem) -> Unit
    ) : BaseAdapter() {

        private var items: List<ServerItem> = emptyList()

        fun submitList(newList: List<ServerItem>) {
            items = newList
            notifyDataSetChanged()
        }

        private class VH(
            val root: LinearLayout,
            val avatarTv: TextView,
            val statusDot: View,
            val nameTv: TextView,
            val catTv: TextView,
            val countTv: TextView,
            val statusTv: TextView,
            val urlTv: TextView,
            val editBtn: TextView,
            val upBtn: TextView,
            val downBtn: TextView,
            val sw: NeonSwitch
        )

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): ServerItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: VH
            val rowView: View

            if (convertView == null) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
                }

                val avatarWrap = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 9) }
                }
                val avatarTv = TextView(ctx).apply {
                    textSize = 11.5f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                val statusDot = View(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9), Gravity.END or Gravity.BOTTOM)
                }
                avatarWrap.addView(avatarTv); avatarWrap.addView(statusDot)

                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 5) }
                }
                val nameTv = TextView(ctx).apply {
                    textSize = 13f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                }
                val badgeLine = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(ctx, 3)
                    }
                }
                val catTv = TextView(ctx).apply {
                    textSize = 7.6f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
                }
                val countTv = TextView(ctx).apply {
                    textSize = 7.6f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(ctx, 4)
                    }
                }
                val statusTv = TextView(ctx).apply {
                    textSize = 7.6f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(ctx, 4)
                    }
                }
                badgeLine.addView(catTv); badgeLine.addView(countTv); badgeLine.addView(statusTv)

                val urlTv = TextView(ctx).apply {
                    textSize = 9.2f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(safeColor("#6B7894")); setPadding(0, dp(ctx, 2), 0, 0)
                }
                col.addView(nameTv); col.addView(badgeLine); col.addView(urlTv)

                val editBtn = TextView(ctx).apply {
                    text = "URL"; textSize = 8.5f; gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#93C5FD"))
                    background = tintBadgeBg("#2563EB", dpf(ctx, 7f), 36, 95)
                    setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(ctx, 5)
                    }
                    attachPressAnim(this)
                }

                val orderCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(ctx, 6)
                    }
                }
                val upBtn = TextView(ctx).apply {
                    text = "▲"; textSize = 8f; gravity = Gravity.CENTER; includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 17)).apply { bottomMargin = dp(ctx, 3) }
                    attachPressAnim(this)
                }
                val downBtn = TextView(ctx).apply {
                    text = "▼"; textSize = 8f; gravity = Gravity.CENTER; includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 17))
                    attachPressAnim(this)
                }
                orderCol.addView(upBtn); orderCol.addView(downBtn)

                val sw = NeonSwitch(ctx, true, getAccentHex())

                card.addView(avatarWrap); card.addView(col); card.addView(editBtn); card.addView(orderCol); card.addView(sw)

                holder = VH(card, avatarTv, statusDot, nameTv, catTv, countTv, statusTv, urlTv, editBtn, upBtn, downBtn, sw)
                card.tag = holder
                rowView = card
            } else {
                rowView = convertView
                holder = convertView.tag as VH
            }

            val srv = items[position]
            val active = isServerEnabled(srv.key, ctx)
            val rawChecked = isServerRawChecked(srv.key, ctx)
            val accent = getAccentHex()
            val domainUrl = getServerDomain(srv.key, ctx)

            holder.root.background = glassCardBg(
                topHex = if (active) "#141C2F" else "#0E131F",
                bottomHex = if (active) "#0D1322" else "#0A0E17",
                strokeColor = if (active) alphaColor(srv.accentHex, 78) else safeColor("#1C2438"),
                radius = dpf(ctx, 15f),
                strokeWidth = 2
            )

            holder.avatarTv.text = monogramOf(srv.name)
            holder.avatarTv.setTextColor(safeColor(srv.accentHex, "#38BDF8"))
            holder.avatarTv.background = tintBadgeBg(srv.accentHex, dpf(ctx, 10f), 42, 110)

            val health = serverHealthMap[srv.key]
            val dotHex = when {
                health == null -> if (active) "#38BDF8" else "#475569"
                health.isHealthy -> "#22C55E"
                else -> "#EF4444"
            }
            holder.statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(safeColor(dotHex)); setStroke(dp(ctx, 2), safeColor("#0E1320"))
            }

            holder.nameTv.text = srv.name
            holder.nameTv.alpha = if (active) 1f else 0.55f

            val activeGroups = srv.groups.filter { isGroupEnabled(it, ctx) }
            holder.catTv.text = if (activeGroups.isEmpty()) "GROUP OFF" else activeGroups.joinToString(" • ")
            holder.catTv.setTextColor(safeColor("#38BDF8"))
            holder.catTv.background = tintBadgeBg("#38BDF8", dpf(ctx, 6f), 32, 70)

            val activeCatCount = (if (isGroupEnabled("Indian OTT", ctx)) srv.ottCount else 0) +
                (if (isGroupEnabled("Studios 18+", ctx)) srv.studioCount else 0) +
                (if (isGroupEnabled("Tubes", ctx)) srv.tubeCount else 0)
            holder.countTv.text = "$activeCatCount/${srv.totalCats}"
            holder.countTv.setTextColor(safeColor(srv.accentHex))
            holder.countTv.background = tintBadgeBg(srv.accentHex, dpf(ctx, 6f), 32, 70)

            if (health != null) {
                holder.statusTv.visibility = View.VISIBLE
                holder.statusTv.text = if (health.latencyMs > 0) "${health.status} ${health.latencyMs}ms" else health.status
                val hHex = if (health.isHealthy) "#34D399" else "#F87171"
                holder.statusTv.setTextColor(safeColor(hHex))
                holder.statusTv.background = tintBadgeBg(hHex, dpf(ctx, 6f), 38, 95)
            } else {
                holder.statusTv.visibility = View.GONE
            }

            holder.urlTv.text = domainUrl
            holder.editBtn.setOnClickListener { onEditDomain(srv) }

            val canUp = position > 0
            val canDown = position < items.lastIndex
            holder.upBtn.setTextColor(safeColor(if (canUp) "#7DD3FC" else "#334155"))
            holder.upBtn.background = cardBg(if (canUp) "#162238" else "#0E1422", if (canUp) "#263B5E" else "#182033", dpf(ctx, 6f), 1)
            holder.downBtn.setTextColor(safeColor(if (canDown) "#7DD3FC" else "#334155"))
            holder.downBtn.background = cardBg(if (canDown) "#162238" else "#0E1422", if (canDown) "#263B5E" else "#182033", dpf(ctx, 6f), 1)

            holder.upBtn.setOnClickListener { if (canUp) onMoveUp(srv) }
            holder.downBtn.setOnClickListener { if (canDown) onMoveDown(srv) }

            holder.sw.onCheckedChange = null
            holder.sw.updateAccent(accent)
            holder.sw.setCheckedSilent(rawChecked)
            holder.sw.onCheckedChange = { checked ->
                setServerEnabled(ctx, srv.key, checked)
                onToggleChanged()
                notifyDataSetChanged()
            }
            holder.root.setOnClickListener { holder.sw.toggle() }
            return rowView
        }
    }

    // =========================================================================
    // Main Settings Dialog Show
    // =========================================================================
    fun show(
        rawCtx: Context,
        onSettingsApplied: (() -> Unit)? = null,
        onClearProviderCfCache: (() -> Unit)? = null
    ) {
        val ctx = resolveActivityContext(rawCtx)
        if (!isContextAlive(ctx)) return
        bindContext(ctx)

        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var activeTheme = getSelectedTheme(ctx)
        var currentQuery = ""
        var selectedCategory = "All"
        var currentConcurrency = getConcurrency(ctx)
        var currentTimeout = getTimeoutSeconds(ctx)
        var currentRowsPerServer = getRowsPerServer(ctx)
        var activeTabIdx = 0

        val themedSwitches = mutableListOf<NeonSwitch>()
        val themedPlusBtns = mutableListOf<TextView>()
        val themedSectionBars = mutableListOf<View>()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 13), dp(ctx, 13), dp(ctx, 13), dp(ctx, 13))
        }

        // ── Hero Header ──────────────────────────────────────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = "WATCHOMOVIES ULTRA MAX"
            textSize = 16.5f; letterSpacing = 0.04f; isSingleLine = true
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        titleCol.addView(TextView(ctx).apply {
            text = "11 SERVERS • 407 CATEGORIES • 3 CLEAN GROUPS"
            textSize = 8.2f; letterSpacing = 0.08f; isSingleLine = true
            setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#94A3B8"))
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        titleRow.addView(titleCol)
        titleRow.addView(TextView(ctx).apply {
            text = "⚡ PRO ENGINE"
            textSize = 9f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#6EE7B7"))
            background = tintBadgeBg("#10B981", dpf(ctx, 10f), 46, 130)
            setPadding(dp(ctx, 9), dp(ctx, 5), dp(ctx, 9), dp(ctx, 5))
        })
        header.addView(titleRow)

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 10) }
        }
        fun statChip(label: String, colorHex: String, endDp: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = glassCardBg("#0E1525", "#090D18", alphaColor(colorHex, 75), dpf(ctx, 12f), 2)
                setPadding(dp(ctx, 9), dp(ctx, 7), dp(ctx, 9), dp(ctx, 7))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (endDp > 0) marginEnd = dp(ctx, endDp)
                }
            }
            val valTv = TextView(ctx).apply { text = "-"; textSize = 12.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor(colorHex)) }
            val lblTv = TextView(ctx).apply { text = label; textSize = 7.4f; letterSpacing = 0.06f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#8A98B5")); setPadding(0, dp(ctx, 1), 0, 0) }
            box.addView(valTv); box.addView(lblTv)
            return box to valTv
        }
        val (ottChip, ottValTv) = statChip("🇮🇳 INDIAN OTT", "#FB7185", 6)
        val (stdChip, stdValTv) = statChip("🎬 STUDIOS 18+", "#38BDF8", 6)
        val (tubChip, tubValTv) = statChip("📺 TUBES", "#FBBF24", 0)
        statsRow.addView(ottChip); statsRow.addView(stdChip); statsRow.addView(tubChip)
        header.addView(statsRow)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) })

        fun refreshStats() {
            val enabledSrvs = SERVERS.filter { isServerRawChecked(it.key, ctx) }
            val activeOtt = if (isGroupEnabled("Indian OTT", ctx)) enabledSrvs.sumOf { it.ottCount } else 0
            val activeStd = if (isGroupEnabled("Studios 18+", ctx)) enabledSrvs.sumOf { it.studioCount } else 0
            val activeTub = if (isGroupEnabled("Tubes", ctx)) enabledSrvs.sumOf { it.tubeCount } else 0
            ottValTv.text = "$activeOtt/144"
            stdValTv.text = "$activeStd/161"
            tubValTv.text = "$activeTub/102"
        }
        refreshStats()

        // ── Segmented Tab Bar ────────────────────────────────────────────────
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0C111D", "#1E2940", dpf(ctx, 14f), 2)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        val tabTitles = listOf("⚡ Servers (11)", "🧩 Extractors", "🎛️ 3 Groups & UI", "🛡️ CF & Health")
        val tabButtons = tabTitles.map { title ->
            Button(ctx).apply {
                text = title; textSize = 10.3f; isAllCaps = false; isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 35)).apply { marginEnd = dp(ctx, 3) }
                attachPressAnim(this)
            }
        }
        tabButtons.forEach { tabBar.addView(it) }
        val tabScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) }
        root.addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) })

        // ── Tab 1 Controls: Search + 3 Group Filter Chips ────────────────────
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        val searchBox = EditText(ctx).apply {
            hint = "🔍 Search Prmovies, Aagmaal, TellyHD..."
            setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#0F1524", "#24314B", dpf(ctx, 12f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 6) }
        }
        val allOnBtn = Button(ctx).apply {
            text = "ALL ON"; textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38)).apply { marginEnd = dp(ctx, 5) }
            attachPressAnim(this)
        }
        val allOffBtn = Button(ctx).apply {
            text = "OFF"; textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#1A2234", "#2D3A54", dpf(ctx, 12f), 2)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38))
            attachPressAnim(this)
        }
        actionRow.addView(searchBox); actionRow.addView(allOnBtn); actionRow.addView(allOffBtn)
        root.addView(actionRow)

        val categories = listOf("All", "Indian OTT", "Studios 18+", "Tubes")
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        val chipViews = mutableListOf<TextView>()
        lateinit var applyServerFilter: () -> Unit

        fun refreshCategoryChips() {
            chipViews.forEachIndexed { idx, tv ->
                val active = categories[idx] == selectedCategory
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = if (active) neonGradBtn(activeTheme.primaryHex, dpf(ctx, 10f))
                else cardBg("#111726", "#202A40", dpf(ctx, 10f), 1)
            }
        }
        categories.forEach { cat ->
            val tv = TextView(ctx).apply {
                text = when (cat) {
                    "Indian OTT" -> "🇮🇳 Indian OTT"
                    "Studios 18+" -> "🎬 Studios 18+"
                    "Tubes" -> "📺 Tubes"
                    else -> "All (11)"
                }
                textSize = 10f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                setOnClickListener { selectedCategory = cat; refreshCategoryChips(); applyServerFilter() }
            }
            chipViews.add(tv); chipRow.addView(tv)
        }
        refreshCategoryChips()
        val chipScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        root.addView(chipScroll)

        val screenH = ctx.resources.displayMetrics.heightPixels
        val contentHeight = (screenH * 0.43f).toInt().coerceIn(dp(ctx, 290), dp(ctx, 430))

        lateinit var serverAdapter: ServerListAdapter
        serverAdapter = ServerListAdapter(
            ctx = ctx,
            getAccentHex = { activeTheme.primaryHex },
            onToggleChanged = { refreshStats() },
            onEditDomain = { srv ->
                openDomainEditorDialog(ctx, srv, activeTheme.primaryHex) {
                    applyServerFilter()
                    onClearProviderCfCache?.invoke()
                    onSettingsApplied?.invoke()
                }
            },
            onMoveUp = { srv ->
                val order = getServerOrder(ctx).toMutableList()
                val idx = order.indexOf(srv.key)
                if (idx > 0) {
                    order[idx] = order[idx - 1].also { order[idx - 1] = order[idx] }
                    saveServerOrder(ctx, order)
                    applyServerFilter()
                }
            },
            onMoveDown = { srv ->
                val order = getServerOrder(ctx).toMutableList()
                val idx = order.indexOf(srv.key)
                if (idx in 0 until order.lastIndex) {
                    order[idx] = order[idx + 1].also { order[idx + 1] = order[idx] }
                    saveServerOrder(ctx, order)
                    applyServerFilter()
                }
            }
        )

        val serversListView = ListView(ctx).apply {
            adapter = serverAdapter
            divider = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            dividerHeight = dp(ctx, 7)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight)
        }

        applyServerFilter = {
            val orderMap = getServerOrder(ctx).withIndex().associate { it.value to it.index }
            val sorted = SERVERS.sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
            val filtered = sorted.filter { item ->
                val catOk = selectedCategory == "All" || item.groups.any { it.equals(selectedCategory, ignoreCase = true) }
                val dom = getServerDomain(item.key, ctx)
                val qOk = currentQuery.isEmpty() ||
                    item.name.lowercase().contains(currentQuery) ||
                    item.tag.lowercase().contains(currentQuery) ||
                    dom.lowercase().contains(currentQuery)
                catOk && qOk
            }
            serverAdapter.submitList(filtered)
            refreshStats()
        }
        applyServerFilter()

        allOnBtn.setOnClickListener {
            val ed = prefs(ctx)?.edit()
            SERVERS.forEach { ed?.putBoolean("$KEY_SRV_PREFIX${it.key}", true) }
            ed?.apply()
            applyServerFilter()
        }
        allOffBtn.setOnClickListener {
            val ed = prefs(ctx)?.edit()
            SERVERS.forEach { ed?.putBoolean("$KEY_SRV_PREFIX${it.key}", false) }
            ed?.apply()
            applyServerFilter()
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                currentQuery = s?.toString().orEmpty().trim().lowercase()
                applyServerFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // ── Scrollable Tabs 2, 3, 4 ──────────────────────────────────────────
        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight + dp(ctx, 76))
        }
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val extractorPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val streamPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val healthPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // ── Tab 2: Extractors ────────────────────────────────────────────────
        val extSec = makeSectionLabel(ctx, "Built-In & Custom Extractors (${EXTRACTORS.size})", activeTheme.primaryHex)
        themedSectionBars.add(extSec.getChildAt(0))
        extractorPane.addView(extSec)

        EXTRACTORS.forEachIndexed { idx, ext ->
            val badgeAccent = AVATAR_PALETTE[idx % AVATAR_PALETTE.size]
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
                setPadding(dp(ctx, 11), dp(ctx, 10), dp(ctx, 11), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) }
            }
            val headRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            headRow.addView(TextView(ctx).apply {
                text = ext.name; textSize = 12.5f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            headRow.addView(TextView(ctx).apply {
                text = ext.tag; textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(badgeAccent))
                background = tintBadgeBg(badgeAccent, dpf(ctx, 6f), 34, 85)
                setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 6) }
            })
            col.addView(headRow)
            col.addView(TextView(ctx).apply { text = ext.hosts; textSize = 9.5f; setTextColor(safeColor("#7D8AA6")); setPadding(0, dp(ctx, 3), 0, 0) })
            val sw = NeonSwitch(ctx, isExtractorEnabled(ext.key, ctx), activeTheme.primaryHex).apply {
                onCheckedChange = { checked -> setExtractorEnabled(ctx, ext.key, checked) }
            }
            themedSwitches.add(sw)
            card.setOnClickListener { sw.toggle() }
            card.addView(col); card.addView(sw)
            extractorPane.addView(card)
        }

        // ── Tab 3: Only 3 Clean Group Filters + Performance + Theme ──────────
        fun makeGroupCard(parent: LinearLayout, title: String, desc: String, badgeText: String, badgeHex: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = glassCardBg("#131A2B", "#0D121F", alphaColor(badgeHex, 85), dpf(ctx, 15f), 2)
                setPadding(dp(ctx, 10), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
            }
            card.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 4), dp(ctx, 34)).apply { marginEnd = dp(ctx, 10) }
                background = pillBg(badgeHex, dpf(ctx, 4f))
            })
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) }
            }
            val topLine = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topLine.addView(TextView(ctx).apply {
                text = title; textSize = 13f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            topLine.addView(TextView(ctx).apply {
                text = badgeText; textSize = 8.2f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(badgeHex))
                background = tintBadgeBg(badgeHex, dpf(ctx, 6f), 38, 95)
                setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 6) }
            })
            col.addView(topLine)
            col.addView(TextView(ctx).apply { text = desc; textSize = 10f; setTextColor(safeColor("#8A98B5")); setPadding(0, dp(ctx, 3), 0, 0) })
            val sw = NeonSwitch(ctx, initial, activeTheme.primaryHex).apply { onCheckedChange = onChange }
            themedSwitches.add(sw)
            card.setOnClickListener { sw.toggle() }
            card.addView(col); card.addView(sw)
            parent.addView(card)
        }

        fun makeToggleCard(parent: LinearLayout, title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) }
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = desc; textSize = 10f; setTextColor(safeColor("#7D8AA6")); setPadding(0, dp(ctx, 2), 0, 0) })
            val sw = NeonSwitch(ctx, initial, activeTheme.primaryHex).apply { onCheckedChange = onChange }
            themedSwitches.add(sw)
            card.setOnClickListener { sw.toggle() }
            card.addView(col); card.addView(sw)
            parent.addView(card)
        }

        fun makeStepperCard(parent: LinearLayout, title: String, desc: String, initial: Int, step: Int, minVal: Int, maxVal: Int, suffix: String, onChange: (Int) -> Unit) {
            var cur = initial
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) }
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = desc; textSize = 10f; setTextColor(safeColor("#7D8AA6")); setPadding(0, dp(ctx, 2), 0, 0) })
            val valTv = TextView(ctx).apply {
                text = if (maxVal == 60 && cur >= 60) "ALL" else "$cur$suffix"
                textSize = 12.5f; gravity = Gravity.CENTER; minWidth = dp(ctx, 40)
                setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            }
            val minusBtn = TextView(ctx).apply {
                text = "−"; textSize = 16f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = cardBg("#1A2338", "#2C3B59", dpf(ctx, 9f), 1)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
                attachPressAnim(this)
                setOnClickListener {
                    if (cur - step >= minVal) {
                        cur -= step
                        valTv.text = "$cur$suffix"
                        onChange(cur)
                    }
                }
            }
            val plusBtn = TextView(ctx).apply {
                text = "+"; textSize = 16f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 9f))
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
                attachPressAnim(this)
                setOnClickListener {
                    if (cur + step <= maxVal) {
                        cur += step
                        valTv.text = if (maxVal == 60 && cur >= 60) "ALL" else "$cur$suffix"
                        onChange(cur)
                    }
                }
            }
            themedPlusBtns.add(plusBtn)
            card.addView(col); card.addView(minusBtn); card.addView(valTv); card.addView(plusBtn)
            parent.addView(card)
        }

        // ONLY 3 GROUP FILTERS
        val catSec = makeSectionLabel(ctx, "🎛️ 3 Category Group Filters", activeTheme.primaryHex)
        themedSectionBars.add(catSec.getChildAt(0))
        streamPane.addView(catSec)

        makeGroupCard(
            streamPane,
            "🇮🇳 Indian OTT / Web Series",
            "Sirf Indian Hindi OTT (WatchOMovies 34 + TellyHD 53 + Prmovies 36 + Aagmaal 20 + Indian X 1)",
            "144 CATS",
            "#FB7185",
            isGroupEnabled("Indian OTT", ctx)
        ) { setGroupEnabled(ctx, "Indian OTT", it); applyServerFilter() }

        makeGroupCard(
            streamPane,
            "🎬 International Studios (18+)",
            "Saare 18+ Studios (XMovies 53 + Pvzija 39 + WatchOMovies 20 + BrazzPW 20 + WatchPorn 14 + Taboo 10 + XXXTime 5)",
            "161 CATS",
            "#38BDF8",
            isGroupEnabled("Studios 18+", ctx)
        ) { setGroupEnabled(ctx, "Studios 18+", it); applyServerFilter() }

        makeGroupCard(
            streamPane,
            "📺 Tubes & Models",
            "Baaki sab Tube tags, Feeds & Models (XXXTime 43 + FPV 25 + BrazzPW 15 + Taboo 13 + Others 6)",
            "102 CATS",
            "#FBBF24",
            isGroupEnabled("Tubes", ctx)
        ) { setGroupEnabled(ctx, "Tubes", it); applyServerFilter() }

        makeToggleCard(streamPane, "Shuffle Rows Inside Groups", "Har group ke andar categories randomize karein", isShuffleHome(ctx)) {
            setShuffleHome(ctx, it)
        }

        val pSec = makeSectionLabel(ctx, "🚀 Speed & Home Load Control", activeTheme.primaryHex)
        themedSectionBars.add(pSec.getChildAt(0))
        streamPane.addView(pSec)
        makeStepperCard(streamPane, "Rows Per Server Limit", "ALL = Sab 407 categories show hongi (5-55 = limit)", currentRowsPerServer, 5, 5, 60, "") {
            currentRowsPerServer = it; setRowsPerServer(ctx, it)
        }
        makeStepperCard(streamPane, "Parallel Search Workers", "Concurrent server search threads", currentConcurrency, 1, 2, 15, "x") {
            currentConcurrency = it; setConcurrency(ctx, it)
        }
        makeStepperCard(streamPane, "Request Timeout", "Per-server network timeout", currentTimeout, 2, 5, 45, "s") {
            currentTimeout = it; setTimeoutSeconds(ctx, it)
        }

        val qSec = makeSectionLabel(ctx, "🎞️ Video Quality Filters", activeTheme.primaryHex)
        themedSectionBars.add(qSec.getChildAt(0))
        streamPane.addView(qSec)
        makeToggleCard(streamPane, "4K + 1440p UHD", "Ultra HD streams", isQuality4kEnabled(ctx)) { setQuality4kEnabled(ctx, it) }
        makeToggleCard(streamPane, "1080p Full HD", "Full HD streams", isQuality1080Enabled(ctx)) { setQuality1080Enabled(ctx, it) }
        makeToggleCard(streamPane, "720p HD", "Standard HD streams", isQuality720Enabled(ctx)) { setQuality720Enabled(ctx, it) }
        makeToggleCard(streamPane, "480p & Below", "Data saver streams", isQuality480Enabled(ctx)) { setQuality480Enabled(ctx, it) }

        val aSec = makeSectionLabel(ctx, "🎨 Appearance", activeTheme.primaryHex)
        themedSectionBars.add(aSec.getChildAt(0))
        streamPane.addView(aSec)

        val themeCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        themeCard.addView(TextView(ctx).apply { text = "UI Accent Theme"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val themeScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 8), 0, 0) }
        val themeChipMap = linkedMapOf<String, LinearLayout>()
        lateinit var applyThemeEverywhere: () -> Unit

        fun refreshThemeSwatches() {
            UI_THEMES.forEach { pal ->
                val chip = themeChipMap[pal.id] ?: return@forEach
                val isSel = pal.id == activeTheme.id
                chip.background = if (isSel) {
                    GradientDrawable().apply {
                        setColor(alphaColor(pal.primaryHex, 70)); cornerRadius = dpf(ctx, 10f)
                        setStroke(dp(ctx, 2), safeColor(pal.primaryHex))
                    }
                } else cardBg("#0F1523", "#222E46", dpf(ctx, 10f), 1)
            }
        }
        UI_THEMES.forEach { pal ->
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply { marginEnd = dp(ctx, 6) }
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(safeColor(pal.primaryHex)) }
                })
                addView(TextView(ctx).apply { text = pal.name; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
                setOnClickListener {
                    activeTheme = pal; setSelectedTheme(ctx, pal.id); applyThemeEverywhere(); refreshThemeSwatches()
                }
            }
            themeChipMap[pal.id] = chip; themeRow.addView(chip)
        }
        refreshThemeSwatches()
        themeScroll.addView(themeRow); themeCard.addView(themeScroll); streamPane.addView(themeCard)

        // ── Tab 4: CF Shield & Health ────────────────────────────────────────
        val cfBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0B1D1E", "#081216", alphaColor("#10B981", 105), dpf(ctx, 16f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) }
        }
        val cfHeader = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cfHeader.addView(TextView(ctx).apply {
            text = "🛡️ Cloudflare Shield (${SERVERS.size} Servers)"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        lateinit var refreshHealthPane: () -> Unit

        cfHeader.addView(Button(ctx).apply {
            text = "⚡ Solve All"; textSize = 9.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 9f))
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32))
            attachPressAnim(this)
            setOnClickListener {
                val targets = SERVERS.filter { isServerEnabled(it.key, ctx) }.ifEmpty { SERVERS }
                openCfBypassQueue(ctx, targets) { refreshHealthPane(); applyServerFilter() }
            }
        })
        cfBox.addView(cfHeader)
        cfBox.addView(TextView(ctx).apply {
            text = "403 / 503 Cloudflare block aane par WebView se verify karein ya 1-click me cookies reset karein."
            textSize = 9.5f; setTextColor(safeColor("#8EA3B5")); setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
        })

        cfBox.addView(Button(ctx).apply {
            text = "🗑️ Clear Cloudflare Cookies & Dead Host Cache"
            textSize = 10.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#FCA5A5"))
            background = tintBadgeBg("#EF4444", dpf(ctx, 10f), 38, 115)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 37))
            attachPressAnim(this)
            setOnClickListener {
                clearAllCfCookies(ctx)
                onClearProviderCfCache?.invoke()
                refreshHealthPane()
                safeToast(ctx, "CF cookies & dead host cache cleared!")
            }
        })
        healthPane.addView(cfBox)

        makeToggleCard(healthPane, "Use Saved WebView CF Cookies", "Attach cf_clearance cookie to requests", isUseWebViewCf(ctx)) {
            setUseWebViewCf(ctx, it)
        }
        makeToggleCard(healthPane, "Auto-Retry on 403 / 503", "Clear CF killer & retry automatically on block", isCfAutoRetry(ctx)) {
            setCfAutoRetry(ctx, it)
        }

        val probeBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        val testAllBtn = Button(ctx).apply {
            text = "⚡ TEST ALL (${SERVERS.size})"; textSize = 9.8f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 11f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1.3f).apply { marginEnd = dp(ctx, 6) }
            attachPressAnim(this)
            setOnClickListener {
                text = "TESTING..."; isEnabled = false
                onClearProviderCfCache?.invoke()
                dialogScope.launch {
                    supervisorScope { SERVERS.map { srv -> async { probeServerLive(ctx, srv) } }.awaitAll() }
                    text = "⚡ TEST ALL (${SERVERS.size})"; isEnabled = true
                    refreshHealthPane(); applyServerFilter()
                }
            }
        }
        val copyLogBtn = Button(ctx).apply {
            text = "COPY LOGS"; textSize = 9.8f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#1A2234", "#2D3A54", dpf(ctx, 11f), 1)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 0.9f)
            attachPressAnim(this)
            setOnClickListener {
                runCatching {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText("WatchOMovies Logs", diagLogs.joinToString("\n")))
                    safeToast(ctx, "Logs copied!")
                }
            }
        }
        probeBtnRow.addView(testAllBtn); probeBtnRow.addView(copyLogBtn)
        healthPane.addView(probeBtnRow)

        val logSec = makeSectionLabel(ctx, "Live Console Output", activeTheme.primaryHex)
        themedSectionBars.add(logSec.getChildAt(0))
        val logTv = TextView(ctx).apply {
            textSize = 9.2f; typeface = Typeface.MONOSPACE; setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#060911", "#1E2A42", dpf(ctx, 13f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 10), dp(ctx, 11), dp(ctx, 10))
        }
        healthPane.addView(logSec); healthPane.addView(logTv)

        refreshHealthPane = {
            logTv.text = if (diagLogs.isEmpty()) {
                "> Ready • 11 Servers • 407 Categories • Tag: $LOG_TAG"
            } else {
                diagLogs.asReversed().take(60).joinToString("\n")
            }
        }
        refreshHealthPane()

        val scrollPanes = listOf(extractorPane, streamPane, healthPane)
        scrollPanes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)

        fun selectTab(idx: Int) {
            activeTabIdx = idx
            if (idx == 0) {
                serversListView.visibility = View.VISIBLE
                actionRow.visibility = View.VISIBLE
                chipScroll.visibility = View.VISIBLE
                scrollView.visibility = View.GONE
                serversListView.alpha = 0f
                serversListView.translationY = dpf(ctx, 6f)
                serversListView.animate().alpha(1f).translationY(0f).setDuration(140).start()
            } else {
                serversListView.visibility = View.GONE
                actionRow.visibility = View.GONE
                chipScroll.visibility = View.GONE
                scrollView.visibility = View.VISIBLE
                scrollView.scrollTo(0, 0)
                scrollPanes.forEachIndexed { i, p ->
                    val show = (i == idx - 1)
                    p.visibility = if (show) View.VISIBLE else View.GONE
                    if (show) {
                        p.alpha = 0f
                        p.translationY = dpf(ctx, 6f)
                        p.animate().alpha(1f).translationY(0f).setDuration(140).start()
                    }
                }
                if (idx == 3) refreshHealthPane()
            }
            tabButtons.forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 11f))
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(safeColor("#8B98B3"))
                }
            }
        }
        tabButtons.forEachIndexed { i, btn -> btn.setOnClickListener { selectTab(i) } }

        root.addView(serversListView); root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnDismissListener { runCatching { dialogScope.cancel() } }

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply { topMargin = dp(ctx, 10); bottomMargin = dp(ctx, 9) }
            setBackgroundColor(safeColor("#1C263B"))
        })

        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val resetBtn = Button(ctx).apply {
            text = "RESET ALL"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#151C2C", "#2A3652", dpf(ctx, 13f), 2)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { marginEnd = dp(ctx, 8) }
            attachPressAnim(this)
            setOnClickListener {
                prefs(ctx)?.edit()?.clear()?.apply()
                clearAllCfCookies(ctx)
                onClearProviderCfCache?.invoke()
                onSettingsApplied?.invoke()
                safeToast(ctx, "All settings reset")
                runCatching { dialog.dismiss() }
            }
        }
        val saveApplyBtn = Button(ctx).apply {
            text = "SAVE & APPLY"; textSize = 11.5f; letterSpacing = 0.04f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.4f).apply { marginStart = dp(ctx, 4) }
            attachPressAnim(this)
            setOnClickListener {
                setSelectedTheme(ctx, activeTheme.id)
                onClearProviderCfCache?.invoke()
                onSettingsApplied?.invoke()
                safeToast(ctx, "Settings saved! Restart app if Home rows don't update.")
                runCatching { dialog.dismiss() }
            }
        }
        footer.addView(resetBtn); footer.addView(saveApplyBtn); root.addView(footer)

        applyThemeEverywhere = {
            root.background = glassCardBg("#090D18", "#05070E", alphaColor(activeTheme.primaryHex, 95), dpf(ctx, 24f), 3)
            header.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(safeColor(activeTheme.gradStart), safeColor(activeTheme.gradMid), safeColor(activeTheme.gradEnd))
            ).apply {
                cornerRadius = dpf(ctx, 18f); setStroke(2, alphaColor(activeTheme.primaryHex, 175))
            }
            allOnBtn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 12f))
            saveApplyBtn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 13f))
            themedSwitches.forEach { it.updateAccent(activeTheme.primaryHex) }
            themedPlusBtns.forEach { it.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 9f)) }
            themedSectionBars.forEach { it.background = pillBg(activeTheme.primaryHex, 4f) }
            refreshCategoryChips()
            tabButtons.forEachIndexed { i, btn ->
                if (i == activeTabIdx) {
                    btn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 11f))
                    btn.setTextColor(Color.WHITE)
                }
            }
            serverAdapter.notifyDataSetChanged()
        }

        applyThemeEverywhere()
        selectTab(0)

        runCatching {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            val width = (ctx.resources.displayMetrics.widthPixels * 0.95).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}

