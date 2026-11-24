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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object WatchOMoviesSettingsDialog {

    const val LOG_TAG = "WatchOMoviesDiag"
    const val DEFAULT_DOMAIN = "https://watchomovies.social"
    private const val PREFS_NAME = "watchomovies_ultra_v1"
    private const val CF_VERIFIED_MARKER = "__CF_VERIFIED__"

    private const val KEY_CUSTOM_DOMAIN = "wom_custom_domain"
    private const val KEY_THEME = "wom_ui_theme"
    private const val KEY_CAT_PREFIX = "wom_cat_"
    private const val KEY_CAT_ORDER = "wom_cat_order_csv"
    private const val KEY_GRP_GENERAL = "wom_grp_general"
    private const val KEY_GRP_INDIAN_OTT = "wom_grp_indian_ott"
    private const val KEY_GRP_STUDIOS = "wom_grp_studios"
    private const val KEY_SHUFFLE_HOME = "wom_shuffle_home"
    private const val KEY_CF_COOKIES = "wom_cf_cookies_json"
    private const val KEY_USE_WEBVIEW_CF = "wom_use_webview_cf"
    private const val KEY_CF_AUTO_RETRY = "wom_cf_auto_retry"

    const val CF_BYPASS_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

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

    data class CategoryItem(
        val key: String,
        val path: String,
        val name: String,
        val group: String, // General, Indian OTT, Studios 18+
        val tag: String,
        val accentHex: String
    )

    data class DomainHealthInfo(
        val status: String,
        val isHealthy: Boolean,
        val httpCode: Int = 0,
        val latencyMs: Long = -1L,
        val detail: String = "",
        val updatedAt: String = ""
    )

    val ALL_CATEGORIES = listOf(
        // General
        CategoryItem("latest", "", "Latest Updates", "General", "HOME", "#38BDF8"),
        CategoryItem("movies", "movies", "Movies", "General", "MOVIES", "#22D3EE"),

        // Indian OTT / Web Series
        CategoryItem("ullu", "director/ullu-originals", "Ullu Originals", "Indian OTT", "OTT", "#F43F5E"),
        CategoryItem("fliz", "director/fliz-movies", "Fliz Movies", "Indian OTT", "OTT", "#FB7185"),
        CategoryItem("nuefliks", "director/nuefliks", "Nuefliks", "Indian OTT", "OTT", "#A855F7"),
        CategoryItem("hotshots", "director/hotshots", "Hotshots", "Indian OTT", "OTT", "#F59E0B"),
        CategoryItem("hunters", "director/hunters", "Hunters", "Indian OTT", "OTT", "#EC4899"),
        CategoryItem("moodx", "director/moodx-originals", "MoodX", "Indian OTT", "OTT", "#C084FC"),
        CategoryItem("voovi", "director/voovi", "VooVi", "Indian OTT", "OTT", "#FB923C"),
        CategoryItem("primeplay", "director/primeplay", "PrimePlay", "Indian OTT", "OTT", "#34D399"),
        CategoryItem("besharams", "director/besharams", "Besharams", "Indian OTT", "OTT", "#F43F5E"),
        CategoryItem("kooku", "director/kooku", "Kooku", "Indian OTT", "OTT", "#FACC15"),
        CategoryItem("gupchup", "director/gupchup", "Gupchup", "Indian OTT", "OTT", "#38BDF8"),
        CategoryItem("feneomovies", "director/feneomovies", "Feneomovies", "Indian OTT", "OTT", "#818CF8"),
        CategoryItem("cinemadosti", "director/cinemadosti", "Cinemadosti", "Indian OTT", "OTT", "#10B981"),
        CategoryItem("primeflix", "director/primeflix", "Primeflix", "Indian OTT", "OTT", "#22D3EE"),
        CategoryItem("gemplex", "director/gemplex", "Gemplex", "Indian OTT", "OTT", "#A855F7"),
        CategoryItem("rabbit", "director/rabbit", "Rabbit", "Indian OTT", "OTT", "#EC4899"),
        CategoryItem("hotmasti", "director/hotmasti", "HotMasti", "Indian OTT", "OTT", "#FB7185"),
        CategoryItem("mastiprime", "director/mastiprime", "MastiPrime", "Indian OTT", "OTT", "#F59E0B"),
        CategoryItem("eknight", "director/ek-night-show", "Ek Night Show", "Indian OTT", "OTT", "#C084FC"),
        CategoryItem("flixsk", "director/flixskmovies", "Flixskmovies", "Indian OTT", "OTT", "#38BDF8"),
        CategoryItem("lootlo", "director/lootlo", "Lootlo", "Indian OTT", "OTT", "#FB923C"),
        CategoryItem("hootzy", "director/hootzy", "Hootzy", "Indian OTT", "OTT", "#34D399"),
        CategoryItem("balloons", "director/balloons", "Balloons", "Indian OTT", "OTT", "#F43F5E"),
        CategoryItem("bigmoviezoo", "director/big-movie-zoo", "Big Movie Zoo", "Indian OTT", "OTT", "#FACC15"),
        CategoryItem("bambooflix", "director/bambooflix", "Bambooflix", "Indian OTT", "OTT", "#10B981"),
        CategoryItem("piliflix", "director/piliflix", "Piliflix", "Indian OTT", "OTT", "#F59E0B"),
        CategoryItem("11upmovies", "director/11upmovies", "11upmovies", "Indian OTT", "OTT", "#818CF8"),
        CategoryItem("eightshots", "director/eightshots", "Eightshots", "Indian OTT", "OTT", "#EC4899"),
        CategoryItem("ientertainment", "director/i-entertainment", "I-Entertainment", "Indian OTT", "OTT", "#22D3EE"),
        CategoryItem("hotprime", "director/hotprime", "Hotprime", "Indian OTT", "OTT", "#F43F5E"),
        CategoryItem("bananaprime", "director/bananaprime", "BananaPrime", "Indian OTT", "OTT", "#FACC15"),
        CategoryItem("chikooflix", "director/chikooflix", "Chikooflix", "Indian OTT", "OTT", "#FB923C"),
        CategoryItem("glamheart", "director/glamheart", "Glamheart", "Indian OTT", "OTT", "#FB7185"),
        CategoryItem("worldprime", "director/worldprime", "Worldprime", "Indian OTT", "OTT", "#38BDF8"),

        // International Studios (18+)
        CategoryItem("brazzers", "genre/brazzers", "Brazzers", "Studios 18+", "18+", "#FACC15"),
        CategoryItem("bangbros", "genre/bangbros", "Bangbros", "Studios 18+", "18+", "#FB923C"),
        CategoryItem("naughtyamerica", "genre/naughty-america", "Naughty America", "Studios 18+", "18+", "#38BDF8"),
        CategoryItem("realitykings", "genre/reality-kings", "Reality Kings", "Studios 18+", "18+", "#A855F7"),
        CategoryItem("vixen", "genre/vixen", "Vixen", "Studios 18+", "18+", "#F43F5E"),
        CategoryItem("wicked", "genre/wicked-pictures", "Wicked Pictures", "Studios 18+", "18+", "#EC4899"),
        CategoryItem("evilangel", "genre/evil-angel", "Evil Angel", "Studios 18+", "18+", "#EF4444"),
        CategoryItem("teamskeet", "genre/teamskeet", "Teamskeet", "Studios 18+", "18+", "#22D3EE"),
        CategoryItem("digitalplayground", "genre/digital-playground", "Digital Playground", "Studios 18+", "18+", "#818CF8"),
        CategoryItem("twistys", "genre/twistys", "Twistys", "Studios 18+", "18+", "#FB7185"),
        CategoryItem("marcdorcel", "genre/marc-dorcel", "Marc Dorcel", "Studios 18+", "18+", "#C084FC"),
        CategoryItem("julesjordan", "genre/jules-jordan-video", "Jules Jordan Video", "Studios 18+", "18+", "#F59E0B"),
        CategoryItem("newsensations", "genre/new-sensations", "New Sensations", "Studios 18+", "18+", "#34D399"),
        CategoryItem("ddfnetwork", "genre/ddf-network", "DDF Network", "Studios 18+", "18+", "#38BDF8"),
        CategoryItem("mofos", "genre/mofos", "Mofos", "Studios 18+", "18+", "#FB923C"),
        CategoryItem("elegantangel", "genre/elegant-angel", "Elegant Angel", "Studios 18+", "18+", "#EC4899"),
        CategoryItem("fakehub", "genre/fakehub", "FakeHub", "Studios 18+", "18+", "#FACC15"),
        CategoryItem("faketaxi", "genre/faketaxi", "FakeTaxi", "Studios 18+", "18+", "#F59E0B"),
        CategoryItem("girlsway", "genre/girlsway", "GirlsWay", "Studios 18+", "18+", "#F43F5E"),
        CategoryItem("mylf", "genre/mylf", "MYLF", "Studios 18+", "18+", "#A855F7")
    )

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    @Volatile
    private var lastDomainHealth: DomainHealthInfo? = null
    private val diagLogs = CopyOnWriteArrayList<String>()

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
        val line = "[${nowTs()}] [$tag] $msg"
        diagLogs.add(line)
        while (diagLogs.size > 150) diagLogs.removeAt(0)
        Log.d(LOG_TAG, line)
    }

    // =========================================================================
    // Persistent Getters & Setters
    // =========================================================================
    fun getCustomDomain(ctx: Context? = appContext): String {
        val raw = prefs(ctx)?.getString(KEY_CUSTOM_DOMAIN, DEFAULT_DOMAIN)?.trim().orEmpty()
        return raw.removeSuffix("/").ifBlank { DEFAULT_DOMAIN }
    }

    fun setCustomDomain(ctx: Context, domain: String) {
        val cleaned = domain.trim().removeSuffix("/").let {
            when {
                it.isBlank() -> DEFAULT_DOMAIN
                it.startsWith("http://") || it.startsWith("https://") -> it
                else -> "https://$it"
            }
        }
        prefs(ctx)?.edit()?.putString(KEY_CUSTOM_DOMAIN, cleaned)?.apply()
        logDiag("DOMAIN", "Updated to $cleaned")
    }

    fun getSelectedTheme(ctx: Context): ThemePalette {
        val id = prefs(ctx)?.getString(KEY_THEME, "crimson") ?: "crimson"
        return UI_THEMES.firstOrNull { it.id == id } ?: UI_THEMES.first()
    }

    fun setSelectedTheme(ctx: Context, id: String) {
        prefs(ctx)?.edit()?.putString(KEY_THEME, id)?.apply()
    }

    fun isGroupEnabled(ctx: Context? = appContext, group: String): Boolean {
        val p = prefs(ctx) ?: return true
        return when (group) {
            "General" -> p.getBoolean(KEY_GRP_GENERAL, true)
            "Indian OTT" -> p.getBoolean(KEY_GRP_INDIAN_OTT, true)
            "Studios 18+" -> p.getBoolean(KEY_GRP_STUDIOS, true)
            else -> true
        }
    }

    fun setGroupEnabled(ctx: Context, group: String, enabled: Boolean) {
        val key = when (group) {
            "General" -> KEY_GRP_GENERAL
            "Indian OTT" -> KEY_GRP_INDIAN_OTT
            "Studios 18+" -> KEY_GRP_STUDIOS
            else -> return
        }
        prefs(ctx)?.edit()?.putBoolean(key, enabled)?.apply()
    }

    fun isCategoryEnabled(ctx: Context? = appContext, cat: CategoryItem): Boolean {
        if (!isGroupEnabled(ctx, cat.group)) return false
        return prefs(ctx)?.getBoolean("$KEY_CAT_PREFIX${cat.key}", true) ?: true
    }

    fun isCategorySwitchChecked(ctx: Context, catKey: String): Boolean {
        return prefs(ctx)?.getBoolean("$KEY_CAT_PREFIX$catKey", true) ?: true
    }

    fun setCategoryEnabled(ctx: Context, catKey: String, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean("$KEY_CAT_PREFIX$catKey", enabled)?.apply()
    }

    fun getCategoryOrder(ctx: Context? = appContext): List<String> {
        val allKeys = ALL_CATEGORIES.map { it.key }
        val saved = prefs(ctx)?.getString(KEY_CAT_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: return allKeys
        val valid = saved.filter { it in allKeys }
        return valid + (allKeys - valid.toSet())
    }

    fun saveCategoryOrder(ctx: Context, orderedKeys: List<String>) {
        prefs(ctx)?.edit()?.putString(KEY_CAT_ORDER, orderedKeys.joinToString(","))?.apply()
    }

    fun getActiveCategories(ctx: Context? = appContext): List<CategoryItem> {
        val orderMap = getCategoryOrder(ctx).withIndex().associate { it.value to it.index }
        val sorted = ALL_CATEGORIES
            .sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
            .filter { isCategoryEnabled(ctx, it) }
        val result = sorted.ifEmpty { listOf(ALL_CATEGORIES.first()) }
        return if (isShuffleHome(ctx) && result.size > 1) {
            listOf(result.first()) + result.drop(1).shuffled()
        } else {
            result
        }
    }

    fun isShuffleHome(ctx: Context? = appContext): Boolean =
        prefs(ctx)?.getBoolean(KEY_SHUFFLE_HOME, false) ?: false

    fun setShuffleHome(ctx: Context, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_SHUFFLE_HOME, enabled)?.apply()
    }

    fun isUseWebViewCf(ctx: Context? = appContext): Boolean =
        prefs(ctx)?.getBoolean(KEY_USE_WEBVIEW_CF, true) ?: true

    fun setUseWebViewCf(ctx: Context, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_USE_WEBVIEW_CF, enabled)?.apply()
    }

    fun isCfAutoRetry(ctx: Context? = appContext): Boolean =
        prefs(ctx)?.getBoolean(KEY_CF_AUTO_RETRY, true) ?: true

    fun setCfAutoRetry(ctx: Context, enabled: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_CF_AUTO_RETRY, enabled)?.apply()
    }

    // =========================================================================
    // Cloudflare Cookie Storage + Clear
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

    fun getCookieForDomain(ctx: Context? = appContext, urlOrDomain: String): String? {
        if (!isUseWebViewCf(ctx)) return null
        val map = getSavedCookiesMap(ctx)
        if (map.isEmpty()) return null
        val host = extractHostKey(urlOrDomain)
        val raw = map[host] ?: map.entries.firstOrNull { host.endsWith(it.key) || it.key.endsWith(host) }?.value
        return raw?.takeIf { it.isNotBlank() && it != CF_VERIFIED_MARKER }
    }

    fun isDomainCfVerified(ctx: Context? = appContext, urlOrDomain: String): Boolean {
        val map = getSavedCookiesMap(ctx)
        if (map.isEmpty()) return false
        val host = extractHostKey(urlOrDomain)
        return map.containsKey(host) || map.keys.any { host.endsWith(it) || it.endsWith(host) }
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
    // Live Domain Health Probe
    // =========================================================================
    private suspend fun probeDomainLive(ctx: Context, domainUrl: String): DomainHealthInfo = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val conn = (URL("$domainUrl/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CF_BYPASS_UA)
                getCookieForDomain(ctx, domainUrl)?.let { setRequestProperty("Cookie", it) }
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - start
            runCatching { conn.inputStream?.close() }
            runCatching { conn.errorStream?.close() }
            conn.disconnect()
            val ok = code in 200..399
            val status = when {
                ok -> "LIVE ($code)"
                code == 403 || code == 503 -> "CF BLOCKED ($code)"
                else -> "HTTP $code"
            }
            val info = DomainHealthInfo(status, ok, code, ms, domainUrl, nowTs())
            lastDomainHealth = info
            logDiag(if (ok) "PROBE" else "CF", "$status • ${ms}ms • $domainUrl")
            info
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - start
            val info = DomainHealthInfo("DOWN", false, 0, ms, e.message ?: "Network error", nowTs())
            lastDomainHealth = info
            logDiag("ERROR", "Probe failed (${ms}ms): ${e.message}")
            info
        }
    }

    // =========================================================================
    // Ultra Glass & Neon Design Helpers (MovieFox Style)
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
    // WebView Cloudflare Bypass Solver (Exact MovieFox Engine)
    // =========================================================================
    @SuppressLint("SetJavaScriptEnabled")
    fun openCfBypassWindow(
        rawCtx: Context,
        targetUrl: String,
        onCompleted: (() -> Unit)? = null
    ) {
        val ctx = resolveActivityContext(rawCtx)
        if (!isContextAlive(ctx)) return
        bindContext(ctx)

        val handler = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        var dialogRef: Dialog? = null
        var webViewRef: WebView? = null

        val titleTv = TextView(ctx).apply {
            text = "🛡️ Cloudflare Solver • WatchOMovies"
            textSize = 14f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val statusTv = TextView(ctx).apply {
            text = "Loading $targetUrl..."
            textSize = 10.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(safeColor("#93C5FD"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 15
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4)).apply {
                bottomMargin = dp(ctx, 8)
            }
        }

        fun saveCookieNow(url: String?, requireClearance: Boolean): Boolean {
            return runCatching {
                val cm = CookieManager.getInstance()
                cm.flush()
                val cookie = cm.getCookie(url ?: targetUrl).orEmpty().ifBlank { cm.getCookie(targetUrl).orEmpty() }
                val hasClearance = cookie.contains("cf_clearance", true) || cookie.contains("__cf_bm", true)
                when {
                    hasClearance -> {
                        saveCookieForDomain(ctx, targetUrl, cookie)
                        logDiag("CF", "Clearance cookie saved (${cookie.length} chars)")
                        true
                    }
                    !requireClearance -> {
                        saveCookieForDomain(ctx, targetUrl, cookie.ifBlank { CF_VERIFIED_MARKER })
                        logDiag("CF", if (cookie.isNotBlank()) "Cookie saved (${cookie.length} chars)" else "Verified (No CF block)")
                        true
                    }
                    else -> false
                }
            }.getOrDefault(false)
        }

        fun closeAll() {
            if (!closed.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            runCatching { webViewRef?.stopLoading(); webViewRef?.destroy() }
            webViewRef = null
            runCatching { dialogRef?.dismiss() }
            dialogRef = null
            onCompleted?.invoke()
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0B1120", "#070B14", alphaColor("#38BDF8", 110), dpf(ctx, 20f), 2)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        container.addView(titleTv)
        container.addView(statusTv)
        container.addView(progressBar)

        val wvHeight = (ctx.resources.displayMetrics.heightPixels * 0.54f).toInt().coerceAtLeast(dp(ctx, 290))
        val wv = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wvHeight).apply {
                bottomMargin = dp(ctx, 10)
            }
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
                    if (saveCookieNow(url, requireClearance = true)) {
                        statusTv.text = "✓ CF Cleared! Closing..."
                        statusTv.setTextColor(safeColor("#6EE7B7"))
                        handler.postDelayed({ closeAll() }, 450L)
                    } else {
                        statusTv.text = "Solve challenge if visible, or tap ✓ Save"
                        statusTv.setTextColor(safeColor("#FDE68A"))
                    }
                }
            }
        }
        webViewRef = wv
        container.addView(wv)

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(ctx).apply {
            text = "⚡ CF Click"
            textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 6) }
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
            text = "✓ Save Cookie"
            textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#2563EB", dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1.1f).apply { marginEnd = dp(ctx, 6) }
            attachPressAnim(this)
            setOnClickListener {
                saveCookieNow(wv.url, requireClearance = false)
                safeToast(ctx, "CF cookie saved!")
                closeAll()
            }
        })
        btnRow.addView(Button(ctx).apply {
            text = "Close"
            textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
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
            wv.loadUrl(targetUrl)
        }
    }

    // =========================================================================
    // Category List Adapter (MovieFox ServerListAdapter Style)
    // =========================================================================
    private class CategoryListAdapter(
        private val ctx: Context,
        private val getAccentHex: () -> String,
        private val onToggleChanged: () -> Unit,
        private val onMoveUp: (CategoryItem) -> Unit,
        private val onMoveDown: (CategoryItem) -> Unit
    ) : BaseAdapter() {

        private var items: List<CategoryItem> = emptyList()

        fun submitList(newList: List<CategoryItem>) {
            items = newList
            notifyDataSetChanged()
        }

        private class VH(
            val root: LinearLayout,
            val avatarTv: TextView,
            val nameTv: TextView,
            val groupTv: TextView,
            val tagTv: TextView,
            val pathTv: TextView,
            val upBtn: TextView,
            val downBtn: TextView,
            val sw: NeonSwitch
        )

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): CategoryItem = items[position]
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

                val avatarTv = TextView(ctx).apply {
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 10) }
                }

                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(ctx, 6)
                    }
                }
                val nameTv = TextView(ctx).apply {
                    textSize = 13f
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                val badgeLine = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(ctx, 3)
                    }
                }
                val groupTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
                }
                val tagTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 4) }
                }
                badgeLine.addView(groupTv)
                badgeLine.addView(tagTv)

                val pathTv = TextView(ctx).apply {
                    textSize = 9.2f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(safeColor("#6B7894"))
                    setPadding(0, dp(ctx, 2), 0, 0)
                }
                col.addView(nameTv)
                col.addView(badgeLine)
                col.addView(pathTv)

                val orderCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(ctx, 8)
                    }
                }
                val upBtn = TextView(ctx).apply {
                    text = "▲"; textSize = 8.5f; gravity = Gravity.CENTER; includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 18)).apply { bottomMargin = dp(ctx, 3) }
                    attachPressAnim(this)
                }
                val downBtn = TextView(ctx).apply {
                    text = "▼"; textSize = 8.5f; gravity = Gravity.CENTER; includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 18))
                    attachPressAnim(this)
                }
                orderCol.addView(upBtn)
                orderCol.addView(downBtn)

                val sw = NeonSwitch(ctx, true, getAccentHex())

                card.addView(avatarTv)
                card.addView(col)
                card.addView(orderCol)
                card.addView(sw)

                holder = VH(card, avatarTv, nameTv, groupTv, tagTv, pathTv, upBtn, downBtn, sw)
                card.tag = holder
                rowView = card
            } else {
                rowView = convertView
                holder = convertView.tag as VH
            }

            val cat = items[position]
            val groupOn = isGroupEnabled(ctx, cat.group)
            val isOn = isCategorySwitchChecked(ctx, cat.key) && groupOn
            val accent = getAccentHex()

            holder.root.background = glassCardBg(
                topHex = if (isOn) "#141C2F" else "#0E131F",
                bottomHex = if (isOn) "#0D1322" else "#0A0E17",
                strokeColor = if (isOn) alphaColor(cat.accentHex, 78) else safeColor("#1C2438"),
                radius = dpf(ctx, 15f),
                strokeWidth = 2
            )

            holder.avatarTv.text = monogramOf(cat.name)
            holder.avatarTv.setTextColor(safeColor(cat.accentHex, "#38BDF8"))
            holder.avatarTv.background = tintBadgeBg(cat.accentHex, dpf(ctx, 10f), 42, 110)

            holder.nameTv.text = cat.name
            holder.nameTv.alpha = if (isOn) 1f else 0.52f

            val grpColor = when (cat.group) {
                "Indian OTT" -> "#F43F5E"
                "Studios 18+" -> "#F59E0B"
                else -> "#38BDF8"
            }
            holder.groupTv.text = if (groupOn) cat.group else "${cat.group} (OFF)"
            holder.groupTv.setTextColor(safeColor(if (groupOn) grpColor else "#94A3B8"))
            holder.groupTv.background = tintBadgeBg(if (groupOn) grpColor else "#64748B", dpf(ctx, 6f), 32, 70)

            holder.tagTv.text = cat.tag
            holder.tagTv.setTextColor(safeColor(cat.accentHex))
            holder.tagTv.background = tintBadgeBg(cat.accentHex, dpf(ctx, 6f), 32, 70)

            holder.pathTv.text = if (cat.path.isBlank()) "/ (Home Feed)" else "/${cat.path}/"

            val canUp = position > 0
            val canDown = position < items.lastIndex
            holder.upBtn.setTextColor(safeColor(if (canUp) "#7DD3FC" else "#334155"))
            holder.upBtn.background = cardBg(if (canUp) "#162238" else "#0E1422", if (canUp) "#263B5E" else "#182033", dpf(ctx, 6f), 1)
            holder.downBtn.setTextColor(safeColor(if (canDown) "#7DD3FC" else "#334155"))
            holder.downBtn.background = cardBg(if (canDown) "#162238" else "#0E1422", if (canDown) "#263B5E" else "#182033", dpf(ctx, 6f), 1)

            holder.upBtn.setOnClickListener { if (canUp) onMoveUp(cat) }
            holder.downBtn.setOnClickListener { if (canDown) onMoveDown(cat) }

            holder.sw.onCheckedChange = null
            holder.sw.updateAccent(accent)
            holder.sw.setCheckedSilent(isCategorySwitchChecked(ctx, cat.key))
            holder.sw.onCheckedChange = { checked ->
                setCategoryEnabled(ctx, cat.key, checked)
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
        var selectedGroup = "All"
        var activeTabIdx = 0

        val themedSwitches = mutableListOf<NeonSwitch>()
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
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = "WATCHOMOVIES ULTRA"
            textSize = 16.5f
            letterSpacing = 0.04f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        titleCol.addView(TextView(ctx).apply {
            text = "PRO SETTINGS • DOMAIN, FILTERS & CF SHIELD"
            textSize = 8.2f
            letterSpacing = 0.08f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#94A3B8"))
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        titleRow.addView(titleCol)
        titleRow.addView(TextView(ctx).apply {
            text = "⚡ PRO UI"
            textSize = 9f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#6EE7B7"))
            background = tintBadgeBg("#10B981", dpf(ctx, 10f), 46, 130)
            setPadding(dp(ctx, 9), dp(ctx, 5), dp(ctx, 9), dp(ctx, 5))
        })
        header.addView(titleRow)

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 11) }
        }
        fun statChip(label: String, colorHex: String, marginEndDp: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = glassCardBg("#0E1525", "#090D18", alphaColor(colorHex, 75), dpf(ctx, 12f), 2)
                setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (marginEndDp > 0) marginEnd = dp(ctx, marginEndDp)
                }
            }
            val valTv = TextView(ctx).apply {
                text = "-"; textSize = 12.5f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor(colorHex))
            }
            val lblTv = TextView(ctx).apply {
                text = label; textSize = 7.8f; letterSpacing = 0.08f; isSingleLine = true
                setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#8A98B5"))
                setPadding(0, dp(ctx, 1), 0, 0)
            }
            box.addView(valTv); box.addView(lblTv)
            return box to valTv
        }
        val (catChip, catValTv) = statChip("CATEGORIES", "#38BDF8", 7)
        val (domChip, domValTv) = statChip("DOMAIN", "#C084FC", 7)
        val (cfChip, cfValTv) = statChip("CF SHIELD", "#34D399", 0)
        statsRow.addView(catChip); statsRow.addView(domChip); statsRow.addView(cfChip)
        header.addView(statsRow)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) })

        fun refreshStats() {
            val activeCount = ALL_CATEGORIES.count { isCategoryEnabled(ctx, it) }
            val domain = getCustomDomain(ctx)
            catValTv.text = "$activeCount/${ALL_CATEGORIES.size}"
            domValTv.text = extractHostKey(domain)
            cfValTv.text = if (isDomainCfVerified(ctx, domain)) "VERIFIED ✓" else "READY"
        }
        refreshStats()

        // ── Segmented Tab Bar ────────────────────────────────────────────────
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0C111D", "#1E2940", dpf(ctx, 14f), 2)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        val tabTitles = listOf("📂 Categories", "🌐 Domain & UI", "🛡️ CF & Health")
        val tabButtons = tabTitles.map { title ->
            Button(ctx).apply {
                text = title; textSize = 10.8f; isAllCaps = false; isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 35), 1f).apply { marginEnd = dp(ctx, 3) }
                attachPressAnim(this)
            }
        }
        tabButtons.forEach { tabBar.addView(it) }
        root.addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) })

        // ── Tab 1 Controls: Search + Group Chips ─────────────────────────────
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        val searchBox = EditText(ctx).apply {
            hint = "🔍 Search Ullu, Brazzers, Fliz..."
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12f
            isSingleLine = true
            background = cardBg("#0F1524", "#24314B", dpf(ctx, 12f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 6) }
        }
        val allOnBtn = Button(ctx).apply {
            text = "ALL ON"; textSize = 9.5f; isSingleLine = true
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38)).apply { marginEnd = dp(ctx, 5) }
            attachPressAnim(this)
        }
        val allOffBtn = Button(ctx).apply {
            text = "OFF"; textSize = 9.5f; isSingleLine = true
            setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#1A2234", "#2D3A54", dpf(ctx, 12f), 2)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38))
            attachPressAnim(this)
        }
        actionRow.addView(searchBox); actionRow.addView(allOnBtn); actionRow.addView(allOffBtn)
        root.addView(actionRow)

        val groups = listOf("All", "General", "Indian OTT", "Studios 18+")
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        val chipViews = mutableListOf<TextView>()
        lateinit var applyCategoryFilter: () -> Unit

        fun refreshGroupChips() {
            chipViews.forEachIndexed { idx, tv ->
                val active = groups[idx] == selectedGroup
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = if (active) neonGradBtn(activeTheme.primaryHex, dpf(ctx, 10f))
                else cardBg("#111726", "#202A40", dpf(ctx, 10f), 1)
            }
        }
        groups.forEach { grp ->
            val tv = TextView(ctx).apply {
                text = grp; textSize = 10f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                setOnClickListener {
                    selectedGroup = grp
                    refreshGroupChips()
                    applyCategoryFilter()
                }
            }
            chipViews.add(tv); chipRow.addView(tv)
        }
        refreshGroupChips()
        val chipScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        root.addView(chipScroll)

        val screenH = ctx.resources.displayMetrics.heightPixels
        val contentHeight = (screenH * 0.43f).toInt().coerceIn(dp(ctx, 290), dp(ctx, 430))

        lateinit var catAdapter: CategoryListAdapter
        catAdapter = CategoryListAdapter(
            ctx = ctx,
            getAccentHex = { activeTheme.primaryHex },
            onToggleChanged = { refreshStats() },
            onMoveUp = { cat ->
                val order = getCategoryOrder(ctx).toMutableList()
                val idx = order.indexOf(cat.key)
                if (idx > 0) {
                    order[idx] = order[idx - 1].also { order[idx - 1] = order[idx] }
                    saveCategoryOrder(ctx, order)
                    applyCategoryFilter()
                }
            },
            onMoveDown = { cat ->
                val order = getCategoryOrder(ctx).toMutableList()
                val idx = order.indexOf(cat.key)
                if (idx in 0 until order.lastIndex) {
                    order[idx] = order[idx + 1].also { order[idx + 1] = order[idx] }
                    saveCategoryOrder(ctx, order)
                    applyCategoryFilter()
                }
            }
        )

        val catListView = ListView(ctx).apply {
            adapter = catAdapter
            divider = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            dividerHeight = dp(ctx, 7)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight)
        }

        applyCategoryFilter = {
            val orderMap = getCategoryOrder(ctx).withIndex().associate { it.value to it.index }
            val sorted = ALL_CATEGORIES.sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
            val filtered = sorted.filter { item ->
                val grpOk = selectedGroup == "All" || item.group.equals(selectedGroup, true)
                val qOk = currentQuery.isEmpty() ||
                    item.name.lowercase().contains(currentQuery) ||
                    item.path.lowercase().contains(currentQuery) ||
                    item.group.lowercase().contains(currentQuery)
                grpOk && qOk
            }
            catAdapter.submitList(filtered)
            refreshStats()
        }
        applyCategoryFilter()

        allOnBtn.setOnClickListener {
            val ed = prefs(ctx)?.edit()
            ALL_CATEGORIES.filter { selectedGroup == "All" || it.group == selectedGroup }
                .forEach { ed?.putBoolean("$KEY_CAT_PREFIX${it.key}", true) }
            ed?.apply()
            applyCategoryFilter()
        }
        allOffBtn.setOnClickListener {
            val ed = prefs(ctx)?.edit()
            ALL_CATEGORIES.filter { selectedGroup == "All" || it.group == selectedGroup }
                .forEach { ed?.putBoolean("$KEY_CAT_PREFIX${it.key}", false) }
            ed?.apply()
            applyCategoryFilter()
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                currentQuery = s?.toString().orEmpty().trim().lowercase()
                applyCategoryFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // ── Scrollable Container for Tabs 2 & 3 ──────────────────────────────
        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight + dp(ctx, 76))
        }
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val domainUiPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val cfHealthPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // Helper for toggle cards
        fun makeToggleCard(parent: LinearLayout, title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit): NeonSwitch {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
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
            return sw
        }

        // ── Tab 2: Domain & UI ───────────────────────────────────────────────
        val domSec = makeSectionLabel(ctx, "🌐 Custom Domain / Mirror URL", activeTheme.primaryHex)
        themedSectionBars.add(domSec.getChildAt(0))
        domainUiPane.addView(domSec)

        val domainCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0E1A30", "#0A1120", alphaColor("#38BDF8", 115), dpf(ctx, 16f), 2)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) }
        }
        domainCard.addView(TextView(ctx).apply {
            text = "Active Mirror Domain"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        domainCard.addView(TextView(ctx).apply {
            text = "Site ka domain change ho jaye toh yahan naya URL daal kar bina plugin update chalayein."
            textSize = 9.5f; setTextColor(safeColor("#8A9AB8")); setPadding(0, dp(ctx, 3), 0, dp(ctx, 8))
        })
        val domainInput = EditText(ctx).apply {
            setText(getCustomDomain(ctx))
            hint = DEFAULT_DOMAIN
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            background = cardBg("#0B111E", "#263654", dpf(ctx, 11f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 40)).apply { bottomMargin = dp(ctx, 8) }
        }
        val domBtnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        domBtnRow.addView(Button(ctx).apply {
            text = "✓ APPLY DOMAIN"
            textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1.3f).apply { marginEnd = dp(ctx, 6) }
            attachPressAnim(this)
            setOnClickListener {
                setCustomDomain(ctx, domainInput.text.toString())
                domainInput.setText(getCustomDomain(ctx))
                refreshStats()
                onSettingsApplied?.invoke()
                safeToast(ctx, "Domain updated: ${getCustomDomain(ctx)}")
            }
        })
        domBtnRow.addView(Button(ctx).apply {
            text = "RESET DEFAULT"
            textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#1A2234", "#2D3A54", dpf(ctx, 10f), 1)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f)
            attachPressAnim(this)
            setOnClickListener {
                setCustomDomain(ctx, DEFAULT_DOMAIN)
                domainInput.setText(DEFAULT_DOMAIN)
                refreshStats()
                onSettingsApplied?.invoke()
                safeToast(ctx, "Default domain restored")
            }
        })
        domainCard.addView(domainInput)
        domainCard.addView(domBtnRow)
        domainUiPane.addView(domainCard)

        val grpSec = makeSectionLabel(ctx, "🎛️ Master Category Filters", activeTheme.primaryHex)
        themedSectionBars.add(grpSec.getChildAt(0))
        domainUiPane.addView(grpSec)

        makeToggleCard(domainUiPane, "Latest & Movies", "Show Latest Updates & Movies on Home", isGroupEnabled(ctx, "General")) {
            setGroupEnabled(ctx, "General", it); applyCategoryFilter()
        }
        makeToggleCard(domainUiPane, "Indian OTT / Web Series", "Ullu, Fliz, PrimePlay, MoodX, Kooku, etc.", isGroupEnabled(ctx, "Indian OTT")) {
            setGroupEnabled(ctx, "Indian OTT", it); applyCategoryFilter()
        }
        makeToggleCard(domainUiPane, "International Studios (18+)", "Brazzers, Bangbros, Vixen, Naughty America, etc.", isGroupEnabled(ctx, "Studios 18+")) {
            setGroupEnabled(ctx, "Studios 18+", it); applyCategoryFilter()
        }
        makeToggleCard(domainUiPane, "Shuffle Home Categories", "Randomize category rows on every reload", isShuffleHome(ctx)) {
            setShuffleHome(ctx, it)
        }

        val themeSec = makeSectionLabel(ctx, "🎨 Appearance", activeTheme.primaryHex)
        themedSectionBars.add(themeSec.getChildAt(0))
        domainUiPane.addView(themeSec)

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
                        setColor(alphaColor(pal.primaryHex, 70))
                        cornerRadius = dpf(ctx, 10f)
                        setStroke(dp(ctx, 2), safeColor(pal.primaryHex))
                    }
                } else {
                    cardBg("#0F1523", "#222E46", dpf(ctx, 10f), 1)
                }
            }
        }
        UI_THEMES.forEach { pal ->
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply { marginEnd = dp(ctx, 6) }
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(safeColor(pal.primaryHex)) }
                })
                addView(TextView(ctx).apply { text = pal.name; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
                setOnClickListener {
                    activeTheme = pal
                    setSelectedTheme(ctx, pal.id)
                    applyThemeEverywhere()
                    refreshThemeSwatches()
                }
            }
            themeChipMap[pal.id] = chip
            themeRow.addView(chip)
        }
        refreshThemeSwatches()
        themeScroll.addView(themeRow)
        themeCard.addView(themeScroll)
        domainUiPane.addView(themeCard)

        // ── Tab 3: CF Shield & Health ────────────────────────────────────────
        val cfBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0B1D1E", "#081216", alphaColor("#10B981", 105), dpf(ctx, 16f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) }
        }
        val cfHeader = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cfHeader.addView(TextView(ctx).apply {
            text = "🛡️ Cloudflare Shield"
            textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        lateinit var refreshHealthPane: () -> Unit

        cfHeader.addView(Button(ctx).apply {
            text = "⚡ Solve CF"
            textSize = 9.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 9f))
            setPadding(dp(ctx, 11), 0, dp(ctx, 11), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32))
            attachPressAnim(this)
            setOnClickListener {
                openCfBypassWindow(ctx, getCustomDomain(ctx)) {
                    refreshHealthPane()
                    refreshStats()
                }
            }
        })
        cfBox.addView(cfHeader)
        cfBox.addView(TextView(ctx).apply {
            text = "403 / 503 Cloudflare error aane par WebView se verify karein ya 1-click me cookies reset karein."
            textSize = 9.5f; setTextColor(safeColor("#8EA3B5")); setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
        })

        val cfStatusBanner = TextView(ctx).apply {
            textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
            attachPressAnim(this)
            setOnClickListener {
                openCfBypassWindow(ctx, getCustomDomain(ctx)) {
                    refreshHealthPane()
                    refreshStats()
                }
            }
        }
        cfBox.addView(cfStatusBanner)

        cfBox.addView(Button(ctx).apply {
            text = "🗑️ Clear Cloudflare Cookies"
            textSize = 10.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#FCA5A5"))
            background = tintBadgeBg("#EF4444", dpf(ctx, 10f), 38, 115)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 37))
            attachPressAnim(this)
            setOnClickListener {
                clearAllCfCookies(ctx)
                onClearProviderCfCache?.invoke()
                refreshHealthPane()
                refreshStats()
                safeToast(ctx, "Cloudflare cookies & cache cleared!")
            }
        })
        cfHealthPane.addView(cfBox)

        makeToggleCard(cfHealthPane, "Use Saved WebView CF Cookies", "Attach solved cf_clearance cookie to requests", isUseWebViewCf(ctx)) {
            setUseWebViewCf(ctx, it)
        }
        makeToggleCard(cfHealthPane, "Auto-Retry on 403 / 503", "Clear CF killer & retry automatically on block", isCfAutoRetry(ctx)) {
            setCfAutoRetry(ctx, it)
        }

        // Domain Live Probe Card
        val probeStatusTv = TextView(ctx).apply {
            textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#94A3B8"))
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
        }
        val probeBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) }
        }
        val testDomBtn = Button(ctx).apply {
            text = "⚡ TEST DOMAIN LIVE"
            textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#0284C7", dpf(ctx, 11f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1.3f).apply { marginEnd = dp(ctx, 6) }
            attachPressAnim(this)
            setOnClickListener {
                text = "TESTING..."
                isEnabled = false
                dialogScope.launch {
                    probeDomainLive(ctx, getCustomDomain(ctx))
                    text = "⚡ TEST DOMAIN LIVE"
                    isEnabled = true
                    refreshHealthPane()
                }
            }
        }
        val copyLogBtn = Button(ctx).apply {
            text = "COPY LOGS"
            textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
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
        probeBtnRow.addView(testDomBtn); probeBtnRow.addView(copyLogBtn)

        val logSec = makeSectionLabel(ctx, "Live Console Output", activeTheme.primaryHex)
        themedSectionBars.add(logSec.getChildAt(0))
        val logTv = TextView(ctx).apply {
            textSize = 9.2f
            typeface = Typeface.MONOSPACE
            setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#060911", "#1E2A42", dpf(ctx, 13f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 10), dp(ctx, 11), dp(ctx, 10))
        }

        cfHealthPane.addView(probeStatusTv)
        cfHealthPane.addView(probeBtnRow)
        cfHealthPane.addView(logSec)
        cfHealthPane.addView(logTv)

        refreshHealthPane = {
            val dom = getCustomDomain(ctx)
            if (isDomainCfVerified(ctx, dom)) {
                cfStatusBanner.text = "✓ CF Cookie Active for ${extractHostKey(dom)}"
                cfStatusBanner.setTextColor(safeColor("#6EE7B7"))
                cfStatusBanner.background = tintBadgeBg("#10B981", dpf(ctx, 10f), 42, 120)
            } else {
                cfStatusBanner.text = "⚡ No CF Cookie — Tap Here to Solve for ${extractHostKey(dom)}"
                cfStatusBanner.setTextColor(safeColor("#93C5FD"))
                cfStatusBanner.background = tintBadgeBg("#2563EB", dpf(ctx, 10f), 42, 120)
            }
            val h = lastDomainHealth
            probeStatusTv.text = if (h != null) {
                "Last Probe: ${h.status} • ${h.latencyMs}ms (${h.updatedAt})"
            } else {
                "Target: $dom • Tap Test Domain Live to check latency"
            }
            probeStatusTv.setTextColor(safeColor(when {
                h == null -> "#94A3B8"
                h.isHealthy -> "#34D399"
                else -> "#F87171"
            }))
            logTv.text = if (diagLogs.isEmpty()) {
                "> Ready • ADB Tag: $LOG_TAG"
            } else {
                diagLogs.asReversed().take(50).joinToString("\n")
            }
        }
        refreshHealthPane()

        val scrollPanes = listOf(domainUiPane, cfHealthPane)
        scrollPanes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)

        fun selectTab(idx: Int) {
            activeTabIdx = idx
            if (idx == 0) {
                catListView.visibility = View.VISIBLE
                actionRow.visibility = View.VISIBLE
                chipScroll.visibility = View.VISIBLE
                scrollView.visibility = View.GONE
            } else {
                catListView.visibility = View.GONE
                actionRow.visibility = View.GONE
                chipScroll.visibility = View.GONE
                scrollView.visibility = View.VISIBLE
                scrollView.scrollTo(0, 0)
                scrollPanes.forEachIndexed { i, p -> p.visibility = if (i == idx - 1) View.VISIBLE else View.GONE }
                if (idx == 2) refreshHealthPane()
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

        root.addView(catListView)
        root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnDismissListener { runCatching { dialogScope.cancel() } }

        // Footer Divider & Action Buttons
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
                topMargin = dp(ctx, 10); bottomMargin = dp(ctx, 9)
            }
            setBackgroundColor(safeColor("#1C263B"))
        })

        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val resetBtn = Button(ctx).apply {
            text = "RESET ALL"
            textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#151C2C", "#2A3652", dpf(ctx, 13f), 2)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { marginEnd = dp(ctx, 8) }
            attachPressAnim(this)
            setOnClickListener {
                prefs(ctx)?.edit()?.clear()?.apply()
                clearAllCfCookies(ctx)
                onClearProviderCfCache?.invoke()
                onSettingsApplied?.invoke()
                safeToast(ctx, "All settings reset to default")
                runCatching { dialog.dismiss() }
            }
        }
        val saveApplyBtn = Button(ctx).apply {
            text = "SAVE & APPLY"
            textSize = 11.5f; letterSpacing = 0.04f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.4f).apply { marginStart = dp(ctx, 4) }
            attachPressAnim(this)
            setOnClickListener {
                setCustomDomain(ctx, domainInput.text.toString())
                setSelectedTheme(ctx, activeTheme.id)
                onSettingsApplied?.invoke()
                safeToast(ctx, "Settings saved! Restart app if Home rows don't refresh.")
                runCatching { dialog.dismiss() }
            }
        }
        footer.addView(resetBtn); footer.addView(saveApplyBtn)
        root.addView(footer)

        // Live Theme Applier
        applyThemeEverywhere = {
            root.background = glassCardBg("#090D18", "#05070E", alphaColor(activeTheme.primaryHex, 95), dpf(ctx, 24f), 3)
            header.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(safeColor(activeTheme.gradStart), safeColor(activeTheme.gradMid), safeColor(activeTheme.gradEnd))
            ).apply {
                cornerRadius = dpf(ctx, 18f)
                setStroke(2, alphaColor(activeTheme.primaryHex, 175))
            }
            allOnBtn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 12f))
            saveApplyBtn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 13f))
            themedSwitches.forEach { it.updateAccent(activeTheme.primaryHex) }
            themedSectionBars.forEach { it.background = pillBg(activeTheme.primaryHex, 4f) }
            refreshGroupChips()
            tabButtons.forEachIndexed { i, btn ->
                if (i == activeTabIdx) {
                    btn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 11f))
                    btn.setTextColor(Color.WHITE)
                }
            }
            catAdapter.notifyDataSetChanged()
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

