package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.StorageController

class SettingsActivity : Activity() {
    private lateinit var theme: Spinner
    private lateinit var accent: Spinner
    private lateinit var search: Spinner
    private lateinit var liveTabs: Spinner
    private lateinit var dns: Spinner
    private lateinit var cookies: Spinner
    private lateinit var restore: Switch
    private lateinit var tracking: Switch
    private lateinit var gpc: Switch
    private lateinit var httpsOnly: Switch
    private lateinit var freeSearch: Switch
    private lateinit var smartPip: Switch
    private lateinit var backgroundMedia: Switch
    private lateinit var translatorKey: EditText
    private lateinit var translatorTarget: Spinner

    private val themes = listOf(
        "Seguir sistema" to BrowserPrefs.THEME_SYSTEM,
        "Midnight" to BrowserPrefs.THEME_MIDNIGHT,
        "OLED negro" to BrowserPrefs.THEME_OLED,
        "Claro" to BrowserPrefs.THEME_LIGHT
    )
    private val accents = listOf(
        "Violeta" to BrowserPrefs.ACCENT_VIOLET,
        "Cian" to BrowserPrefs.ACCENT_CYAN,
        "Lima" to BrowserPrefs.ACCENT_LIME,
        "Naranja" to BrowserPrefs.ACCENT_ORANGE,
        "Rosa" to BrowserPrefs.ACCENT_PINK,
        "Rojo" to BrowserPrefs.ACCENT_RED
    )
    private val engines = listOf(
        "Google" to BrowserPrefs.ENGINE_GOOGLE,
        "DuckDuckGo" to BrowserPrefs.ENGINE_DDG,
        "Bing" to BrowserPrefs.ENGINE_BING,
        "Brave Search" to BrowserPrefs.ENGINE_BRAVE,
        "Startpage" to BrowserPrefs.ENGINE_STARTPAGE
    )
    private val dnsOptions = listOf(
        "DNS del sistema" to BrowserPrefs.DNS_SYSTEM,
        "Cloudflare DNS over HTTPS" to BrowserPrefs.DNS_CLOUDFLARE,
        "Google DNS over HTTPS" to BrowserPrefs.DNS_GOOGLE,
        "Quad9 DNS over HTTPS" to BrowserPrefs.DNS_QUAD9
    )
    private val cookieOptions = listOf(
        "Equilibrado · aislar terceros" to BrowserPrefs.COOKIES_BALANCED,
        "Solo cookies del sitio" to BrowserPrefs.COOKIES_FIRST_PARTY,
        "Aceptar todas" to BrowserPrefs.COOKIES_ALL,
        "Bloquear todas" to BrowserPrefs.COOKIES_NONE
    )
    private val liveOptions = listOf(2, 3, 4, 5, 6, 8)
    private val translatorLanguages = listOf(
        "Español" to "es",
        "Inglés" to "en",
        "Portugués" to "pt",
        "Francés" to "fr",
        "Alemán" to "de",
        "Italiano" to "it",
        "Japonés" to "ja",
        "Coreano" to "ko"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        theme = findViewById(R.id.themeSpinner)
        accent = findViewById(R.id.accentSpinner)
        search = findViewById(R.id.searchSpinner)
        liveTabs = findViewById(R.id.liveTabsSpinner)
        dns = findViewById(R.id.dnsSpinner)
        cookies = findViewById(R.id.cookiesSpinner)
        restore = findViewById(R.id.restoreTabsSwitch)
        tracking = findViewById(R.id.trackingSwitch)
        gpc = findViewById(R.id.gpcSwitch)
        httpsOnly = findViewById(R.id.httpsOnlySwitch)
        freeSearch = findViewById(R.id.freeSearchSwitch)
        smartPip = findViewById(R.id.smartPipSwitch)
        backgroundMedia = findViewById(R.id.backgroundMediaSwitch)
        translatorKey = findViewById(R.id.translatorApiKeyEdit)
        translatorTarget = findViewById(R.id.translatorTargetSpinner)

        populate()
        applyTheme()
        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener { save() }
        findViewById<Button>(R.id.clearDataButton).setOnClickListener { confirmClear() }
    }

    private fun populate() {
        theme.adapter = ThemeManager.spinnerAdapter(this, themes.map { it.first })
        accent.adapter = ThemeManager.spinnerAdapter(this, accents.map { it.first })
        search.adapter = ThemeManager.spinnerAdapter(this, engines.map { it.first })
        liveTabs.adapter = ThemeManager.spinnerAdapter(this, liveOptions.map(Int::toString))
        dns.adapter = ThemeManager.spinnerAdapter(this, dnsOptions.map { it.first })
        cookies.adapter = ThemeManager.spinnerAdapter(this, cookieOptions.map { it.first })
        translatorTarget.adapter = ThemeManager.spinnerAdapter(
            this,
            translatorLanguages.map { it.first }
        )

        theme.setSelection(themes.indexOfFirst { it.second == BrowserPrefs.theme(this) }.coerceAtLeast(0))
        accent.setSelection(accents.indexOfFirst { it.second == BrowserPrefs.accent(this) }.coerceAtLeast(0))
        search.setSelection(engines.indexOfFirst { it.second == BrowserPrefs.searchEngine(this) }.coerceAtLeast(0))
        liveTabs.setSelection(liveOptions.indexOf(BrowserPrefs.maxLiveTabs(this)).coerceAtLeast(0))
        dns.setSelection(dnsOptions.indexOfFirst { it.second == BrowserPrefs.dnsProvider(this) }.coerceAtLeast(0))
        cookies.setSelection(cookieOptions.indexOfFirst { it.second == BrowserPrefs.cookieMode(this) }.coerceAtLeast(0))

        restore.isChecked = BrowserPrefs.restoreTabs(this)
        tracking.isChecked = BrowserPrefs.trackingProtection(this)
        gpc.isChecked = BrowserPrefs.globalPrivacyControl(this)
        httpsOnly.isChecked = BrowserPrefs.httpsOnly(this)
        freeSearch.isChecked = BrowserPrefs.freeSearch(this)
        smartPip.isChecked = BrowserPrefs.smartPip(this)
        backgroundMedia.isChecked = BrowserPrefs.backgroundMedia(this)
        translatorKey.setText(BrowserPrefs.translatorApiKey(this))
        translatorTarget.setSelection(
            translatorLanguages.indexOfFirst {
                it.second == BrowserPrefs.translatorTarget(this)
            }.coerceAtLeast(0)
        )
    }

    private fun save() {
        BrowserPrefs.setTheme(this, themes[theme.selectedItemPosition].second)
        BrowserPrefs.setAccent(this, accents[accent.selectedItemPosition].second)
        BrowserPrefs.setSearchEngine(this, engines[search.selectedItemPosition].second)
        BrowserPrefs.setDnsProvider(this, dnsOptions[dns.selectedItemPosition].second)
        BrowserPrefs.setCookieMode(this, cookieOptions[cookies.selectedItemPosition].second)
        BrowserPrefs.setMaxLiveTabs(this, liveOptions[liveTabs.selectedItemPosition])
        BrowserPrefs.setRestoreTabs(this, restore.isChecked)
        BrowserPrefs.setTrackingProtection(this, tracking.isChecked)
        BrowserPrefs.setGlobalPrivacyControl(this, gpc.isChecked)
        BrowserPrefs.setHttpsOnly(this, httpsOnly.isChecked)
        BrowserPrefs.setFreeSearch(this, freeSearch.isChecked)
        BrowserPrefs.setSmartPip(this, smartPip.isChecked)
        BrowserPrefs.setBackgroundMedia(this, backgroundMedia.isChecked)
        BrowserPrefs.setTranslatorApiKey(
            this,
            translatorKey.text.toString()
        )
        BrowserPrefs.setTranslatorTarget(
            this,
            translatorLanguages[
                translatorTarget.selectedItemPosition
            ].second
        )

        GeckoRuntimeHolder.applyRuntimePrefs(this)
        TabManager.reapplySettings()
        ExtensionManager.sendBrowserState(this)
        MediaPlaybackService.sync(this)
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Borrar datos")
            .setMessage(
                "Se cerrarán las sesiones y se borrarán cookies, caché, " +
                    "almacenamiento de sitios, miniaturas e historial."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                TabManager.closeAllSessionsKeepingState()
                GeckoRuntimeHolder.get(this).storageController
                    .clearData(StorageController.ClearFlags.ALL)
                    .accept(
                        {
                            HistoryStore.clear(this)
                            TabPreviewStore.clear(this)
                            runOnUiThread {
                                Toast.makeText(this, "Datos de navegación borrados", Toast.LENGTH_SHORT).show()
                            }
                        },
                        { error ->
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "Error: ${error?.message ?: "desconocido"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
            }.show()
    }

    private fun styleReadableTree(view: View) {
        when (view) {
            is Button -> Unit
            is TextView -> ThemeManager.styleText(this, view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) styleReadableTree(view.getChildAt(i))
        }
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        val settingsRoot = findViewById<View>(R.id.settingsRoot)
        settingsRoot.setBackgroundColor(p.background)
        styleReadableTree(settingsRoot)
        listOf(
            theme, accent, search, liveTabs,
            dns, cookies, translatorTarget
        ).forEach {
            ThemeManager.styleSpinner(this, it)
        }
        listOf(restore, tracking, gpc, httpsOnly, freeSearch, smartPip, backgroundMedia).forEach {
            ThemeManager.styleSwitch(this, it)
        }
        ThemeManager.styleText(this, findViewById(R.id.settingsTitle))
        ThemeManager.styleEdit(this, translatorKey)
        ThemeManager.styleButton(this, findViewById(R.id.saveSettingsButton), primary = true)
        ThemeManager.styleButton(this, findViewById(R.id.clearDataButton))
    }
}
