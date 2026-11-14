package com.nexo.browser

import android.content.Context
import android.content.res.Configuration
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.net.URI

object NightMode {
    private val privateExceptions = java.util.WeakHashMap<GeckoSession, Boolean>()
    private fun host(url: String): String? = runCatching { URI(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
    fun allowed(context: Context, tab: BrowserTab): Boolean = if (tab.isPrivate) {
        tab.session?.let { privateExceptions[it] } != true
    } else host(tab.url) !in context.getSharedPreferences("nexo_night", Context.MODE_PRIVATE).getStringSet("excluded", emptySet()).orEmpty()
    fun setAllowed(context: Context, tab: BrowserTab, allowed: Boolean) {
        if (tab.isPrivate) tab.session?.let { privateExceptions[it] = !allowed }
        else host(tab.url)?.let { site ->
            val prefs = context.getSharedPreferences("nexo_night", Context.MODE_PRIVATE)
            val excluded = prefs.getStringSet("excluded", emptySet()).orEmpty().toMutableSet()
            if (allowed) excluded.remove(site) else excluded.add(site)
            prefs.edit().putStringSet("excluded", excluded).apply()
        }
        ExtensionManager.sendBrowserState(context)
    }
    fun clear(context: Context) {
        privateExceptions.clear()
        context.getSharedPreferences("nexo_night", Context.MODE_PRIVATE).edit().clear().apply()
        ExtensionManager.sendBrowserState(context)
    }
    fun state(context: Context, session: GeckoSession?): JSONObject {
        val tab = session?.let { TabManager.tabForSession(it) }
        val theme = BrowserPrefs.theme(context)
        val dark = when (theme) {
            BrowserPrefs.THEME_LIGHT -> false
            BrowserPrefs.THEME_SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
        return JSONObject().put("enabled", dark && tab != null && UrlSafety.isWebUrl(tab.url) && allowed(context, tab))
            .put("allowFetch", tab != null && !tab.isPrivate && tab.burnerId == null && tab.networkProfile == null)
            .put("background", if (theme == BrowserPrefs.THEME_OLED) "#000000" else "#181a1b")
            .put("text", "#e8e6e3")
    }
}
