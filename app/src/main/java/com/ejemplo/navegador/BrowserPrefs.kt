package com.ejemplo.navegador

import android.content.Context

object BrowserPrefs {
    private const val FILE = "nexo_browser_settings"

    const val THEME_SYSTEM = "system"
    const val THEME_MIDNIGHT = "midnight"
    const val THEME_OLED = "oled"
    const val THEME_LIGHT = "light"

    const val ACCENT_VIOLET = "violet"
    const val ACCENT_CYAN = "cyan"
    const val ACCENT_LIME = "lime"
    const val ACCENT_ORANGE = "orange"
    const val ACCENT_PINK = "pink"
    const val ACCENT_RED = "red"

    const val ENGINE_GOOGLE = "google"
    const val ENGINE_DDG = "duckduckgo"
    const val ENGINE_BING = "bing"
    const val ENGINE_BRAVE = "brave"
    const val ENGINE_STARTPAGE = "startpage"

    const val DNS_SYSTEM = "system"
    const val DNS_CLOUDFLARE = "cloudflare"
    const val DNS_GOOGLE = "google"
    const val DNS_QUAD9 = "quad9"

    const val COOKIES_BALANCED = "balanced"
    const val COOKIES_FIRST_PARTY = "first_party"
    const val COOKIES_ALL = "all"
    const val COOKIES_NONE = "none"

    private fun p(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun theme(c: Context) = p(c).getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
    fun setTheme(c: Context, v: String) = p(c).edit().putString("theme", v).apply()

    fun accent(c: Context) = p(c).getString("accent", ACCENT_VIOLET) ?: ACCENT_VIOLET
    fun setAccent(c: Context, v: String) = p(c).edit().putString("accent", v).apply()

    fun searchEngine(c: Context) = p(c).getString("search", ENGINE_GOOGLE) ?: ENGINE_GOOGLE
    fun setSearchEngine(c: Context, v: String) = p(c).edit().putString("search", v).apply()

    fun freeSearch(c: Context) = p(c).getBoolean("free_search", false)
    fun setFreeSearch(c: Context, v: Boolean) = p(c).edit().putBoolean("free_search", v).apply()

    fun homePage(c: Context): String =
        when (searchEngine(c)) {
            ENGINE_DDG -> "https://duckduckgo.com/"
            ENGINE_BING -> "https://www.bing.com/"
            ENGINE_BRAVE -> "https://search.brave.com/"
            ENGINE_STARTPAGE -> "https://www.startpage.com/"
            else -> "https://www.google.com/"
        }

    fun restoreTabs(c: Context) = p(c).getBoolean("restore_tabs", true)
    fun setRestoreTabs(c: Context, v: Boolean) = p(c).edit().putBoolean("restore_tabs", v).apply()

    fun trackingProtection(c: Context) = p(c).getBoolean("tracking", true)
    fun setTrackingProtection(c: Context, v: Boolean) = p(c).edit().putBoolean("tracking", v).apply()

    fun globalPrivacyControl(c: Context) = p(c).getBoolean("gpc", true)
    fun setGlobalPrivacyControl(c: Context, v: Boolean) = p(c).edit().putBoolean("gpc", v).apply()

    fun httpsOnly(c: Context) = p(c).getBoolean("https_only", false)
    fun setHttpsOnly(c: Context, v: Boolean) = p(c).edit().putBoolean("https_only", v).apply()

    fun dnsProvider(c: Context) = p(c).getString("dns_provider", DNS_SYSTEM) ?: DNS_SYSTEM
    fun setDnsProvider(c: Context, v: String) = p(c).edit().putString("dns_provider", v).apply()

    fun cookieMode(c: Context) = p(c).getString("cookie_mode", COOKIES_BALANCED) ?: COOKIES_BALANCED
    fun setCookieMode(c: Context, v: String) = p(c).edit().putString("cookie_mode", v).apply()

    // Compatibilidad interna: el bridge ya no se muestra como extensión.
    fun showBridgeBadge(c: Context) = false
    fun setShowBridgeBadge(c: Context, v: Boolean) = Unit

    fun smartPip(c: Context) = p(c).getBoolean("smart_pip", true)
    fun setSmartPip(c: Context, v: Boolean) = p(c).edit().putBoolean("smart_pip", v).apply()

    fun backgroundMedia(c: Context) = p(c).getBoolean("background_media", true)
    fun setBackgroundMedia(c: Context, v: Boolean) = p(c).edit().putBoolean("background_media", v).apply()

    fun translatorApiKey(c: Context) =
        p(c).getString("translator_api_key", "") ?: ""
    fun setTranslatorApiKey(c: Context, v: String) =
        p(c).edit().putString("translator_api_key", v.trim()).apply()

    fun translatorTarget(c: Context) =
        p(c).getString("translator_target", "es") ?: "es"
    fun setTranslatorTarget(c: Context, v: String) =
        p(c).edit().putString("translator_target", v).apply()

    fun maxLiveTabs(c: Context) = p(c).getInt("max_live", 5).coerceIn(1, 8)
    fun setMaxLiveTabs(c: Context, v: Int) = p(c).edit().putInt("max_live", v.coerceIn(1, 8)).apply()

    fun tabsJson(c: Context) = p(c).getString("tabs_json", null)
    fun setTabsJson(c: Context, v: String) = p(c).edit().putString("tabs_json", v).apply()

    fun activeTabId(c: Context) = p(c).getString("active_tab", null)
    fun setActiveTabId(c: Context, v: String?) = p(c).edit().putString("active_tab", v).apply()
}
