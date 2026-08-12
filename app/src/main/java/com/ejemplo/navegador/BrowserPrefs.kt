package com.ejemplo.navegador

import android.content.Context

object BrowserPrefs {
    private const val FILE = "nexo_browser_settings"

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

    const val LOCAL_HOME = "resource://android/assets/home/index.html"

    private fun p(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun theme(c: Context) = p(c).getString("theme", THEME_MIDNIGHT) ?: THEME_MIDNIGHT
    fun setTheme(c: Context, v: String) = p(c).edit().putString("theme", v).apply()

    fun accent(c: Context) = p(c).getString("accent", ACCENT_VIOLET) ?: ACCENT_VIOLET
    fun setAccent(c: Context, v: String) = p(c).edit().putString("accent", v).apply()

    fun searchEngine(c: Context) = p(c).getString("search", ENGINE_GOOGLE) ?: ENGINE_GOOGLE
    fun setSearchEngine(c: Context, v: String) = p(c).edit().putString("search", v).apply()

    fun homePage(c: Context) = p(c).getString("home", LOCAL_HOME) ?: LOCAL_HOME
    fun setHomePage(c: Context, v: String) = p(c).edit().putString("home", v).apply()

    fun restoreTabs(c: Context) = p(c).getBoolean("restore_tabs", true)
    fun setRestoreTabs(c: Context, v: Boolean) = p(c).edit().putBoolean("restore_tabs", v).apply()

    fun trackingProtection(c: Context) = p(c).getBoolean("tracking", true)
    fun setTrackingProtection(c: Context, v: Boolean) = p(c).edit().putBoolean("tracking", v).apply()

    fun showBridgeBadge(c: Context) = p(c).getBoolean("bridge_badge", false)
    fun setShowBridgeBadge(c: Context, v: Boolean) = p(c).edit().putBoolean("bridge_badge", v).apply()

    fun maxLiveTabs(c: Context) = p(c).getInt("max_live", 3).coerceIn(1, 8)
    fun setMaxLiveTabs(c: Context, v: Int) = p(c).edit().putInt("max_live", v.coerceIn(1, 8)).apply()

    fun tabsJson(c: Context) = p(c).getString("tabs_json", null)
    fun setTabsJson(c: Context, v: String) = p(c).edit().putString("tabs_json", v).apply()

    fun activeTabId(c: Context) = p(c).getString("active_tab", null)
    fun setActiveTabId(c: Context, v: String?) = p(c).edit().putString("active_tab", v).apply()
}
