package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

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
    private lateinit var nexoHome: Switch
    private lateinit var smartPip: Switch
    private lateinit var backgroundMedia: Switch
    private lateinit var translatorTarget: Spinner
    private lateinit var textSize: Spinner
    private lateinit var autoplay: Spinner
    private lateinit var forceZoom: Switch
    private lateinit var pullToRefresh: Switch
    private lateinit var dockGestures: Switch
    private lateinit var parallelDownloads: Switch
    private lateinit var desktopDefault: Switch
    private lateinit var javascript: Switch
    private lateinit var strictTracking: Switch
    private lateinit var safeBrowsing: Switch
    private lateinit var fingerprinting: Switch
    private lateinit var queryStripping: Switch
    private lateinit var clearOnExit: Switch
    private lateinit var remoteDebug: Switch
    private lateinit var isolatedTabs: Switch
    private lateinit var captionsEnabled: Switch

    private val textSizes = listOf(80, 90, 100, 110, 120, 135, 150, 175)
    private val autoplayOptions = listOf(
        "Permitir" to BrowserPrefs.AUTOPLAY_ALLOW,
        "Bloquear solo con sonido" to BrowserPrefs.AUTOPLAY_BLOCK_AUDIBLE,
        "Bloquear siempre" to BrowserPrefs.AUTOPLAY_BLOCK_ALL
    )

    private val themes = listOf(
        "Seguir sistema" to BrowserPrefs.THEME_SYSTEM,
        "Midnight" to BrowserPrefs.THEME_MIDNIGHT,
        "OLED negro" to BrowserPrefs.THEME_OLED,
        "Claro" to BrowserPrefs.THEME_LIGHT
    )
    private val accents = listOf(
        "Cobalto" to BrowserPrefs.ACCENT_COBALT,
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
        "Quad9 DNS over HTTPS" to BrowserPrefs.DNS_QUAD9,
        "DoH personalizado (barra de direcciones)" to BrowserPrefs.DNS_CUSTOM
    )
    private val cookieOptions = listOf(
        "Equilibrado · aislar terceros" to BrowserPrefs.COOKIES_BALANCED,
        "Solo cookies del sitio" to BrowserPrefs.COOKIES_FIRST_PARTY,
        "Aceptar todas" to BrowserPrefs.COOKIES_ALL,
        "Bloquear todas" to BrowserPrefs.COOKIES_NONE
    )
    private val liveOptions = listOf(2, 3, 4, 5, 6, 8)
    private val translatorLanguages by lazy {
        val common = listOf(
        "Español" to "es",
        "Inglés" to "en",
        "Portugués" to "pt",
        "Francés" to "fr",
        "Alemán" to "de",
        "Italiano" to "it",
        "Japonés" to "ja",
        "Coreano" to "ko"
    )
        val selected=BrowserPrefs.translatorTarget(this)
        if(common.none { it.second==selected } && selected.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) {
            common + (java.util.Locale.forLanguageTag(selected).getDisplayName(java.util.Locale.getDefault()) to selected)
        } else common
    }

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
        nexoHome = findViewById(R.id.nexoHomeSwitch)
        smartPip = findViewById(R.id.smartPipSwitch)
        backgroundMedia = findViewById(R.id.backgroundMediaSwitch)
        translatorTarget = findViewById(R.id.translatorTargetSpinner)
        textSize = findViewById(R.id.textSizeSpinner)
        autoplay = findViewById(R.id.autoplaySpinner)
        forceZoom = findViewById(R.id.forceZoomSwitch)
        pullToRefresh = findViewById(R.id.pullToRefreshSwitch)
        dockGestures = findViewById(R.id.dockGesturesSwitch)
        parallelDownloads = findViewById(R.id.parallelDownloadsSwitch)
        desktopDefault = findViewById(R.id.desktopDefaultSwitch)
        javascript = findViewById(R.id.javascriptSwitch)
        strictTracking = findViewById(R.id.strictTrackingSwitch)
        safeBrowsing = findViewById(R.id.safeBrowsingSwitch)
        fingerprinting = findViewById(R.id.fingerprintingSwitch)
        queryStripping = findViewById(R.id.queryStrippingSwitch)
        clearOnExit = findViewById(R.id.clearOnExitSwitch)
        remoteDebug = findViewById(R.id.remoteDebugSwitch)
        isolatedTabs = findViewById(R.id.isolatedTabsSwitch)
        captionsEnabled = findViewById(R.id.captionsEnabledSwitch)

        findViewById<Button>(R.id.transferProfileButton).setOnClickListener { MigrationMode.enter(this) }
        // Paso automático desde la app anterior (com.ejemplo.navegador); solo tiene sentido en la nueva.
        findViewById<Button>(R.id.legacyImportButton).apply {
            visibility = if (packageName == LegacyImport.TARGET_PACKAGE) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { LegacyImport.showManual(this@SettingsActivity) { recreate() } }
        }
        populate()
        applyTheme()
        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener { save() }
        findViewById<Button>(R.id.clearDataButton).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.defaultBrowserButton).setOnClickListener { requestDefaultBrowser() }
        findViewById<Button>(R.id.configureCaptionsButton).setOnClickListener {
            CaptionsUi.showSheet(this) { updateCaptionsSummary() }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<Button>(R.id.defaultBrowserButton).visibility =
            if (isDefaultBrowser()) View.GONE else View.VISIBLE
    }

    private fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roles.isRoleHeld(RoleManager.ROLE_BROWSER)
            }
        }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val handler = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        return handler?.activityInfo?.packageName == packageName
    }

    private fun requestDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
                !roles.isRoleHeld(RoleManager.ROLE_BROWSER)
            ) {
                @Suppress("DEPRECATION")
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQUEST_DEFAULT_BROWSER)
                return
            }
        }
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "Elige Nexo en Ajustes › Apps predeterminadas › Navegador", Toast.LENGTH_LONG).show()
        }
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
        textSize.adapter = ThemeManager.spinnerAdapter(this, textSizes.map { if (it == 100) "100 % (normal)" else "$it %" })
        autoplay.adapter = ThemeManager.spinnerAdapter(this, autoplayOptions.map { it.first })
        textSize.setSelection(textSizes.indexOf(BrowserPrefs.textScale(this)).takeIf { it >= 0 } ?: textSizes.indexOf(100))
        autoplay.setSelection(autoplayOptions.indexOfFirst { it.second == BrowserPrefs.autoplay(this) }.coerceAtLeast(0))
        forceZoom.isChecked = BrowserPrefs.forceZoom(this)
        pullToRefresh.isChecked = BrowserPrefs.pullToRefresh(this)
        dockGestures.isChecked = BrowserPrefs.dockGestures(this)
        parallelDownloads.isChecked = BrowserPrefs.parallelDownloads(this)
        desktopDefault.isChecked = BrowserPrefs.desktopDefault(this)
        javascript.isChecked = BrowserPrefs.javascript(this)
        strictTracking.isChecked = BrowserPrefs.strictTracking(this)
        safeBrowsing.isChecked = BrowserPrefs.safeBrowsing(this)
        fingerprinting.isChecked = BrowserPrefs.fingerprinting(this)
        queryStripping.isChecked = BrowserPrefs.queryStripping(this)
        clearOnExit.isChecked = BrowserPrefs.clearOnExit(this)
        remoteDebug.isChecked = BrowserPrefs.remoteDebugging(this)
        isolatedTabs.isChecked = BrowserPrefs.isolatedTabs(this)
        strictTracking.isEnabled = BrowserPrefs.trackingProtection(this)
        tracking.setOnCheckedChangeListener { _, on -> strictTracking.isEnabled = on }
        findViewById<TextView>(R.id.aboutText).text = aboutText()
        captionsEnabled.isChecked = BrowserPrefs.captionsEnabled(this)
        updateCaptionsSummary()

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
        nexoHome.isChecked = BrowserPrefs.nexoHome(this)
        smartPip.isChecked = BrowserPrefs.smartPip(this)
        backgroundMedia.isChecked = BrowserPrefs.backgroundMedia(this)
        translatorTarget.setSelection(
            translatorLanguages.indexOfFirst {
                it.second == BrowserPrefs.translatorTarget(this)
            }.coerceAtLeast(0)
        )
    }

    private fun save() {
        if(dnsOptions[dns.selectedItemPosition].second == BrowserPrefs.DNS_CUSTOM && !DnsProfile.valid(BrowserPrefs.dnsCustom(this))) {
            android.widget.Toast.makeText(this,"Configura la URL DoH en Herramientas de pestaña → DNS.",android.widget.Toast.LENGTH_LONG).show();return
        }
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
        BrowserPrefs.setNexoHome(this, nexoHome.isChecked)
        BrowserPrefs.setSmartPip(this, smartPip.isChecked)
        BrowserPrefs.setBackgroundMedia(this, backgroundMedia.isChecked)
        BrowserPrefs.setTextScale(this, textSizes[textSize.selectedItemPosition])
        BrowserPrefs.setAutoplay(this, autoplayOptions[autoplay.selectedItemPosition].second)
        BrowserPrefs.setForceZoom(this, forceZoom.isChecked)
        BrowserPrefs.setPullToRefresh(this, pullToRefresh.isChecked)
        BrowserPrefs.setDockGestures(this, dockGestures.isChecked)
        BrowserPrefs.setParallelDownloads(this, parallelDownloads.isChecked)
        BrowserPrefs.setDesktopDefault(this, desktopDefault.isChecked)
        BrowserPrefs.setJavascript(this, javascript.isChecked)
        BrowserPrefs.setStrictTracking(this, strictTracking.isChecked)
        BrowserPrefs.setSafeBrowsing(this, safeBrowsing.isChecked)
        BrowserPrefs.setFingerprinting(this, fingerprinting.isChecked)
        BrowserPrefs.setQueryStripping(this, queryStripping.isChecked)
        BrowserPrefs.setClearOnExit(this, clearOnExit.isChecked)
        BrowserPrefs.setRemoteDebugging(this, remoteDebug.isChecked)
        BrowserPrefs.setIsolatedTabs(this, isolatedTabs.isChecked)
        BrowserPrefs.setCaptionsEnabled(this, captionsEnabled.isChecked)
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
            .setTitle("Borrar datos de navegación")
            .setMessage("Se borrarán cookies, caché, datos de sitios, miniaturas e historial. Las pestañas abiertas se conservan.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                BrowsingData.clear(this) { ok, error ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) "Datos de navegación borrados" else "No se pudo borrar: ${error ?: "error desconocido"}",
                            if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.show()
    }

    private fun updateCaptionsSummary() {
        val language = CaptionsUi.LANGUAGES.firstOrNull { it.first == BrowserPrefs.captionLanguage(this) }?.second ?: "Automático"
        val target = CaptionsUi.TARGETS.firstOrNull { it.first == BrowserPrefs.captionTranslateTo(this) }?.second ?: "No traducir"
        findViewById<TextView>(R.id.captionsSummary).text =
            (if (BrowserPrefs.deepgramKey(this).isBlank()) "Sin clave de Deepgram: solo videos con subtítulos propios."
            else "Audio: $language · ${LiveCaptions.usageText(this)}") + "\nTraducción: $target"
    }

    private fun aboutText(): String {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        return "Nexo $version · motor Gecko ${org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION}"
    }

    private fun styleReadableTree(view: View) {
        when {
            view.tag == "card" -> ThemeManager.styleCard(this, view)
            view.tag == "divider" -> ThemeManager.styleDivider(this, view)
            view is Button -> Unit
            view is TextView && view.tag == "section" -> ThemeManager.styleSectionLabel(this, view)
            view is TextView && view.tag == "muted" -> ThemeManager.styleText(this, view, muted = true)
            view is TextView -> ThemeManager.styleText(this, view)
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
            dns, cookies, translatorTarget, textSize, autoplay
        ).forEach {
            ThemeManager.styleSpinner(this, it)
        }
        listOf(
            restore, tracking, gpc, httpsOnly, freeSearch, nexoHome, smartPip, backgroundMedia,
            forceZoom, pullToRefresh, dockGestures, parallelDownloads, desktopDefault, javascript, strictTracking, safeBrowsing,
            fingerprinting, queryStripping, clearOnExit, remoteDebug, isolatedTabs, captionsEnabled
        ).forEach {
            ThemeManager.styleSwitch(this, it)
        }
        ThemeManager.styleText(this, findViewById(R.id.settingsTitle))
        ThemeManager.styleButton(this, findViewById(R.id.saveSettingsButton), primary = true)
        ThemeManager.styleButton(this, findViewById(R.id.clearDataButton), danger = true)
        ThemeManager.styleButton(this, findViewById(R.id.defaultBrowserButton))
        ThemeManager.styleButton(this, findViewById(R.id.configureCaptionsButton))
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
        findViewById<View>(R.id.settingsFooter).background =
            ThemeManager.barBackground(this, lineOnTop = true)
        findViewById<View>(R.id.settingsHeader).setBackgroundColor(p.background)
        ThemeManager.takeInitialFocus(settingsRoot)
    }

    companion object {
        private const val REQUEST_DEFAULT_BROWSER = 51
    }
}
