package com.ejemplo.navegador

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity(), TabManager.Listener, BrowserMediaController.Listener {
    private lateinit var geckoView: GeckoView
    private lateinit var omnibox: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: Button
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var clearOmniboxButton: Button
    private lateinit var privateBanner: TextView
    private lateinit var miniPlayerButton: Button
    private lateinit var root: View
    private lateinit var browserFrame: android.widget.FrameLayout
    private lateinit var startPage: StartPageView
    private var startPageKey = ""

    /**
     * Mini video nativo: rectángulo del video (px de la GeckoView) que se
     * amplía para llenar la ventana PiP. Mientras no sea null, la GeckoView
     * conserva su tamaño, así la página NO se redimensiona (YouTube pausaba
     * y reorganizaba la página al achicarse) y no hace falta modificar su CSS.
     */
    private var pipCrop: Rect? = null
    private lateinit var topBar: View
    private lateinit var navBar: View
    private lateinit var viewModel: BrowserViewModel
    private var attachedSession: GeckoSession? = null
    private var settingsFingerprint = ""
    private var themeKey = ""
    private var mediaNotificationPermissionAsked = false
    private var suppressAutoPipOnce = false

    // Estado capturado ANTES de que Android/YouTube cambien
    // la Activity de foreground a PiP/background.
    private var pipPlaybackWasActive = false
    private var backgroundPlaybackWasActive = false

    // onStop antes de salir de PiP = el usuario cerró la ventana mini.
    private var activityStopped = false

    // Un sitio puso un elemento (normalmente el video) en pantalla completa.
    private var fullScreenActive = false
    private var pendingPipEntry: Runnable? = null

    /** Botones reproducir/pausar de la ventana PiP. */
    private val pipControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIP_CONTROL) return
            val command = intent.getStringExtra(EXTRA_PIP_COMMAND) ?: return
            val tab = TabManager.activeTab() ?: return

            if (!ExtensionManager.sendPipCommand(tab.session, command)) {
                when (command) {
                    PIP_COMMAND_PLAY -> BrowserMediaController.play(tab.id)
                    PIP_COMMAND_PAUSE -> BrowserMediaController.pause(tab.id)
                }
            }
            // El estado real llega desde la página; refrescar el icono en breve.
            geckoView.postDelayed({ updatePipParams() }, 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        CrashLog.consume(this)?.let { crash ->
            AlertDialog.Builder(this)
                .setTitle("Nexo detectó un cierre inesperado")
                .setMessage(crash.take(8000))
                .setNegativeButton("Cerrar", null)
                .setPositiveButton("Copiar") { _, _ ->
                    copyText("Nexo crash", crash)
                }.show()
        }

        viewModel = BrowserViewModel(applicationContext)
        bindViews()
        BrowserMediaController.attach(this)
        applyTheme()
        configureUi()
        ThemeManager.takeInitialFocus(root)
        TabManager.initialize(this)
        TabManager.attach(this)
        ExtensionManager.attachPromptActivity(this)
        ContextCompat.registerReceiver(
            this,
            pipControlReceiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Al recrear la Activity (rotación, cambio de tema) no repetir la
        // navegación del intent original.
        if (savedInstanceState == null) handleNavigationIntent(intent)
    }

    private fun bindViews() {
        root = findViewById(R.id.rootView)
        topBar = findViewById(R.id.topBar)
        navBar = findViewById(R.id.navBar)
        geckoView = findViewById(R.id.geckoView)
        browserFrame = findViewById(R.id.browserFrame)
        startPage = StartPageView(
            this,
            onSearch = { focusOmnibox() },
            onOpen = { url -> TabManager.navigate(url) }
        ).apply { visibility = View.GONE }
        // Encima de la GeckoView y debajo del botón "Mini video".
        browserFrame.addView(
            startPage,
            1,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        browserFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (pipCrop != null && isInPictureInPictureMode) applyPipCrop()
        }
        omnibox = findViewById(R.id.omnibox)
        progress = findViewById(R.id.pageProgress)
        tabButton = findViewById(R.id.tabButton)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        clearOmniboxButton = findViewById(R.id.clearOmniboxButton)
        privateBanner = findViewById(R.id.privateBanner)
        miniPlayerButton = findViewById(R.id.miniPlayerButton)
    }

    private fun configureUi() {
        findViewById<TextView>(R.id.brandText).setOnClickListener { viewModel.goHome() }
        findViewById<ImageButton>(R.id.reloadButton).setOnClickListener { TabManager.reload() }
        backButton.setOnClickListener { TabManager.goBackOrPrevious() }
        forwardButton.setOnClickListener { TabManager.goForward() }
        findViewById<ImageButton>(R.id.homeButton).setOnClickListener { viewModel.goHome() }
        findViewById<ImageButton>(R.id.newTabButton).setOnClickListener { viewModel.newTab(false) }
        tabButton.setOnClickListener { showTabsDialog() }
        findViewById<Button>(R.id.menuButton).setOnClickListener { showMenu(it) }
        miniPlayerButton.setOnClickListener { startMiniPlayer() }

        clearOmniboxButton.setOnClickListener {
            omnibox.setText("")
            omnibox.requestFocus()
        }

        omnibox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearOmniboxButton.visibility =
                    if (omnibox.hasFocus() && !s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        omnibox.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH || enter
            ) {
                navigateOmnibox()
                true
            } else false
        }

        omnibox.setOnFocusChangeListener { _, focused ->
            val tab = TabManager.activeTab()
            if (focused) {
                // Al editar se muestra la URL completa, seleccionada.
                tab?.takeUnless { BrowserPrefs.isNexoHome(it.url) }?.let { omnibox.setText(it.url) }
                omnibox.selectAll()
                setOmniboxIcon(null)
                clearOmniboxButton.visibility =
                    if (omnibox.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            } else {
                clearOmniboxButton.visibility = View.GONE
                tab?.let { renderOmnibox(it) }
            }
        }
    }

    private fun navigateOmnibox() {
        viewModel.navigate(omnibox.text?.toString().orEmpty())
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(omnibox.windowToken, 0)
        omnibox.clearFocus()
    }

    /** Menú principal: hoja inferior con la página actual y una cuadrícula de acciones. */
    private fun showMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val current = TabManager.activeTab()
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }

        val actions = listOf(
            SheetAction(R.drawable.ic_nav_add, "Nueva pestaña") { viewModel.newTab(false) },
            SheetAction(R.drawable.ic_menu_private, "Privada") { viewModel.newTab(true) },
            SheetAction(R.drawable.ic_menu_history, "Historial") { openScreen(HistoryActivity::class.java) },
            SheetAction(R.drawable.ic_menu_download, "Descargas") { openScreen(DownloadsActivity::class.java) },
            SheetAction(R.drawable.ic_extension_placeholder, "Extensiones") {
                openScreen(ExtensionStoreActivity::class.java)
            },
            SheetAction(R.drawable.ic_menu_translate, "Traducir") {
                TranslatorManager.translateActive(this) { _, message ->
                    runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
                }
            },
            SheetAction(R.drawable.ic_menu_share, "Compartir") { shareCurrent() },
            SheetAction(
                R.drawable.ic_menu_desktop,
                "Escritorio",
                active = current?.desktopMode == true
            ) { TabManager.setDesktopMode(!(current?.desktopMode ?: false)) },
            SheetAction(R.drawable.ic_menu_copy, "Copiar enlace") {
                current?.let { copyText("Enlace", it.url) }
            },
            SheetAction(R.drawable.ic_menu_tabs, "Pestañas") { showTabsDialog() },
            SheetAction(R.drawable.ic_menu_settings, "Ajustes") { openScreen(SettingsActivity::class.java) },
            SheetAction(R.drawable.ic_menu_close, "Cerrar", danger = true) { viewModel.closeCurrentTab() }
        )

        val content = NexoSheets.column(
            this,
            NexoSheets.header(
                this,
                if (BrowserPrefs.isNexoHome(current?.url)) "Página de inicio"
                else current?.title?.ifBlank { null } ?: "Nexo Browser",
                current?.url?.takeUnless { BrowserPrefs.isNexoHome(it) }
            ),
            NexoSheets.grid(this, actions, dismiss = dismiss)
        )
        sheet = NexoSheets.show(this, content)
    }

    private fun openScreen(screen: Class<out Activity>) {
        suppressAutoPipOnce = true
        startActivity(Intent(this, screen))
    }

    private fun captureCurrentTabPreview() {
        val tab = TabManager.activeTab() ?: return
        if (
            tab.isPrivate ||
            BrowserPrefs.isNexoHome(tab.url) ||
            tab.isLoading ||
            BrowserMediaController.isPlaying(tab.id) ||
            geckoView.width <= 0 ||
            geckoView.height <= 0
        ) return
        runCatching {
            geckoView.capturePixels().accept(
                { bitmap ->
                    if (bitmap != null) {
                        TabPreviewStore.save(applicationContext, tab.id, bitmap)
                    }
                },
                { _ -> Unit }
            )
        }
    }

    private fun showTabsDialog() {
        captureCurrentTabPreview()
        TabSwitcher.show(this) { private -> viewModel.newTab(private) }
    }

    /** Mantener pulsado un enlace o imagen: hoja con las acciones disponibles. */
    override fun onContextElement(element: GeckoSession.ContentDelegate.ContextElement) {
        val link = element.linkUri.orEmpty()
        val image = element.srcUri.orEmpty()
        if (link.isBlank() && image.isBlank()) return

        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val actions = mutableListOf<SheetAction>()

        if (link.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_menu_open, "Abrir en nueva pestaña") {
                TabManager.createTab(link)
            }
            actions += SheetAction(R.drawable.ic_menu_private, "Abrir en pestaña privada") {
                TabManager.createTab(link, isPrivate = true)
            }
            actions += SheetAction(R.drawable.ic_menu_link, "Abrir aquí") { TabManager.navigate(link) }
            actions += SheetAction(R.drawable.ic_menu_copy, "Copiar enlace") { copyText("Enlace", link) }
            actions += SheetAction(R.drawable.ic_menu_share, "Compartir enlace") { shareText(link) }
            actions += SheetAction(R.drawable.ic_menu_download, "Descargar enlace") { downloadUrl(link) }
        }
        if (image.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_menu_image, "Abrir imagen en nueva pestaña") {
                TabManager.createTab(image)
            }
            actions += SheetAction(R.drawable.ic_menu_copy, "Copiar dirección de la imagen") {
                copyText("Imagen", image)
            }
            actions += SheetAction(R.drawable.ic_menu_download, "Descargar imagen") { downloadUrl(image) }
        }

        val title = element.title?.takeIf { it.isNotBlank() }
            ?: element.altText?.takeIf { it.isNotBlank() }
            ?: element.linkText?.takeIf { it.isNotBlank() }
            ?: if (link.isNotBlank()) "Enlace" else "Imagen"

        sheet = NexoSheets.show(
            this,
            NexoSheets.column(
                this,
                NexoSheets.header(this, title, link.ifBlank { image }),
                NexoSheets.list(this, actions, dismiss)
            )
        )
    }

    private fun shareText(text: String) {
        suppressAutoPipOnce = true
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Compartir"
        ))
    }

    private fun copyText(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    private fun downloadUrl(url: String) {
        if (ExtensionManager.isRemoteExtensionPackage(url)) {
            Toast.makeText(this, "Abriendo instalador de extensión…", Toast.LENGTH_SHORT).show()
            ExtensionManager.installUrl(this, url) { _, message ->
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
            return
        }

        if (!UrlSafety.isWebUrl(url)) {
            Toast.makeText(this, "Este recurso no se puede descargar directamente", Toast.LENGTH_LONG).show()
            return
        }

        // Descargar con Gecko (cookies de la sesión, modo privado) en lugar de
        // DownloadManager, que no tiene acceso a la sesión del navegador.
        val tab = TabManager.activeTab()
        Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show()
        DownloadStore.download(
            this,
            url,
            isPrivate = tab?.isPrivate == true,
            referrer = tab?.url
        ) { _, message ->
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun shareCurrent() {
        val tab = TabManager.activeTab() ?: return
        suppressAutoPipOnce = true
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, tab.title)
                putExtra(Intent.EXTRA_TEXT, "${tab.title}\n${tab.url}")
            }, "Compartir página"
        ))
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        root.setBackgroundColor(p.background)
        topBar.background = ThemeManager.barBackground(this, lineOnTop = false)
        navBar.background = ThemeManager.barBackground(this, lineOnTop = true)
        ThemeManager.styleEdit(this, omnibox)
        ThemeManager.styleButton(this, miniPlayerButton, primary = true)
        miniPlayerButton.elevation = 6f * resources.displayMetrics.density
        ThemeManager.styleTabCounter(this, tabButton)
        ThemeManager.styleFlatButton(this, findViewById(R.id.menuButton))
        ThemeManager.styleFlatButton(this, clearOmniboxButton)

        listOf(
            backButton,
            forwardButton,
            findViewById<ImageButton>(R.id.homeButton),
            findViewById<ImageButton>(R.id.reloadButton),
            findViewById<ImageButton>(R.id.newTabButton)
        ).forEach { ThemeManager.styleIconButton(this, it) }

        privateBanner.setBackgroundColor(p.accentSoft)
        privateBanner.setTextColor(p.accent)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
        progress.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        settingsFingerprint = fingerprint()
        themeKey = ThemeManager.themeKey(this)
    }

    private fun fingerprint(): String =
        listOf(
            BrowserPrefs.theme(this), BrowserPrefs.accent(this),
            BrowserPrefs.searchEngine(this), BrowserPrefs.freeSearch(this).toString(),
            BrowserPrefs.trackingProtection(this).toString(),
            BrowserPrefs.maxLiveTabs(this).toString(),
            BrowserPrefs.dnsProvider(this), BrowserPrefs.cookieMode(this),
            BrowserPrefs.httpsOnly(this).toString(), BrowserPrefs.globalPrivacyControl(this).toString(),
            BrowserPrefs.smartPip(this).toString(), BrowserPrefs.backgroundMedia(this).toString()
        ).joinToString("|")

    /*
     * MainActivity está exportada (launcher), así que cualquier app puede
     * enviarle EXTRA_OPEN_URL. Solo las pantallas propias, que llegan por el
     * alias NO exportado InternalOpenActivity, pueden abrir páginas internas
     * (about:, moz-extension:…). Desde fuera solo se aceptan http/https.
     */
    private fun handleNavigationIntent(intent: Intent?) {
        if (intent == null) return
        val raw = intent
            .getStringExtra(EXTRA_OPEN_URL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        // Consumir el extra para no reabrirlo al volver a la Activity.
        intent.removeExtra(EXTRA_OPEN_URL)

        val internal = intent.component?.className == INTERNAL_OPEN_ALIAS
        val allowed =
            if (internal) UrlSafety.isAppNavigable(raw)
            else UrlSafety.isExternallyOpenable(raw)

        if (!allowed) {
            Toast.makeText(
                this,
                "Nexo bloqueó un enlace con esquema no permitido",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Enlaces de otras apps: pestaña nueva (como otros navegadores). Así,
        // al abrir Nexo en frío, la restauración de la pestaña anterior no
        // puede pisar la URL pedida.
        if (internal) TabManager.navigate(raw) else TabManager.createTab(raw)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onActiveTabChanged(tab: BrowserTab, session: GeckoSession) {
        if (attachedSession !== session) {
            if (attachedSession != null) geckoView.releaseSession()
            geckoView.setSession(session)
            attachedSession = session
        }
        if (fullScreenActive != tab.isFullScreen) {
            fullScreenActive = tab.isFullScreen
            applyChromeVisibility()
        }
        renderTab(tab)
        updatePipParams()
    }

    override fun onFullScreenChanged(tab: BrowserTab, fullScreen: Boolean) {
        fullScreenActive = fullScreen
        applyChromeVisibility()
    }

    private val immersive: Boolean
        get() = isInPictureInPictureMode || fullScreenActive

    /**
     * Barras de Nexo y del sistema según el modo: normal, pantalla completa
     * de un video, o ventana PiP (solo el contenido web).
     */
    private fun applyChromeVisibility(forcePip: Boolean = false) {
        val pip = forcePip || isInPictureInPictureMode
        val hideChrome = pip || fullScreenActive
        val tab = TabManager.activeTab()

        topBar.visibility = if (hideChrome) View.GONE else View.VISIBLE
        navBar.visibility = if (hideChrome) View.GONE else View.VISIBLE
        privateBanner.visibility =
            if (!hideChrome && tab?.isPrivate == true) View.VISIBLE else View.GONE
        progress.visibility =
            if (!hideChrome && tab?.isLoading == true) View.VISIBLE else View.GONE

        setSystemBarsHidden(fullScreenActive && !pip)
        if (hideChrome) startPage.visibility = View.GONE else tab?.let { updateStartPage(it) }
        updateMiniPlayerChip()
        root.requestLayout()
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (hidden) {
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            if (hidden) {
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                ThemeManager.applyWindow(this)
            }
        }
    }

    override fun onTabChanged(tab: BrowserTab) {
        if (tab.id == TabManager.activeTab()?.id) renderTab(tab)
    }

    override fun onProgress(tab: BrowserTab, progressValue: Int, loading: Boolean) {
        if (tab.id != TabManager.activeTab()?.id) return
        progress.progress = progressValue
        progress.visibility = if (loading && !immersive) View.VISIBLE else View.GONE
        if (!loading) captureCurrentTabPreview()
    }

    override fun onTabCountChanged(count: Int) {
        tabButton.text = if (count > 99) "99+" else count.toString()
    }

    override fun onMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBrowserMediaStateChanged() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            BrowserPrefs.backgroundMedia(this) &&
            BrowserMediaController.isAnyPlaying() &&
            !mediaNotificationPermissionAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            mediaNotificationPermissionAsked = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_MEDIA_NOTIFICATIONS
            )
        }
        updateMiniPlayerChip()
        updatePipParams()
        followPipVideo()
    }

    /** Si el video cambia de sitio durante el PiP, mover el recorte con él. */
    private fun followPipVideo() {
        val current = pipCrop ?: return
        if (!isInPictureInPictureMode) return
        val tab = TabManager.activeTab() ?: return
        val rect = BrowserMediaController.videoRect(tab.id) ?: return
        if (!rect.intersect(0, 0, geckoView.width, geckoView.height)) return
        if (rect == current || rect.width() < 40 || rect.height() < 40) return
        pipCrop = rect
        applyPipCrop()
    }

    private fun focusOmnibox() {
        omnibox.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(omnibox, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Página de inicio de Nexo en pestañas nuevas (about:blank). */
    private fun updateStartPage(tab: BrowserTab) {
        val show = BrowserPrefs.isNexoHome(tab.url) && !immersive
        if (show) {
            // Re-dibujar solo si cambió la pestaña, el tema o la privacidad.
            val key = tab.id + "|" + tab.isPrivate + "|" + ThemeManager.themeKey(this)
            if (key != startPageKey || startPage.visibility != View.VISIBLE) {
                startPage.render(tab.isPrivate)
                startPageKey = key
            }
        }
        startPage.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Muestra el dominio (como Chrome/Firefox) y un candado si es HTTPS. */
    private fun renderOmnibox(tab: BrowserTab) {
        if (BrowserPrefs.isNexoHome(tab.url)) {
            omnibox.setText("")
            setOmniboxIcon(R.drawable.ic_search)
            return
        }
        omnibox.setText(displayUrl(tab.url))
        setOmniboxIcon(
            if (UrlSafety.scheme(tab.url) == "https") R.drawable.ic_lock else R.drawable.ic_search
        )
    }

    private fun setOmniboxIcon(icon: Int?) {
        val drawable = icon?.let { getDrawable(it)?.mutate() }
        omnibox.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
    }

    private fun displayUrl(url: String): String {
        if (!UrlSafety.isWebUrl(url)) return url
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return url
        return host.removePrefix("www.").removePrefix("m.")
    }

    private fun renderTab(tab: BrowserTab) {
        if (!omnibox.hasFocus()) renderOmnibox(tab)
        updateStartPage(tab)
        backButton.isEnabled = TabManager.canGoBackOrPrevious()
        forwardButton.isEnabled = tab.canGoForward
        privateBanner.visibility =
            if (!immersive && tab.isPrivate) View.VISIBLE else View.GONE
        title = if (tab.isPrivate) "Privado · ${tab.title}" else tab.title
        updateMiniPlayerChip()
    }

    override fun onStart() {
        super.onStart()
        activityStopped = false
        val tab = TabManager.activeTab()
        if (tab != null && tab.session == null) TabManager.switchTo(tab.id)
        else TabManager.resumeActive()
    }

    override fun onResume() {
        suppressAutoPipOnce = false
        backgroundPlaybackWasActive = false

        if (!isInPictureInPictureMode) {
            TabManager.activeTab()?.let { tab ->
                BrowserMediaController.clearPlaybackHold(tab.id)
            }
        }

        super.onResume()
        ExtensionManager.attachPromptActivity(this)
        if (themeKey.isNotBlank() && themeKey != ThemeManager.themeKey(this)) {
            // Diálogos, menús y controles del sistema dependen del tema de la
            // Activity: recrearla para aplicarlo entero.
            recreate()
            return
        }
        if (settingsFingerprint.isNotBlank() && settingsFingerprint != fingerprint()) {
            ThemeManager.applyWindow(this)
            applyTheme()
            GeckoRuntimeHolder.applyRuntimePrefs(this)
            TabManager.reapplySettings()
            ExtensionManager.sendBrowserState(this)
            MediaPlaybackService.sync(this)
            updatePipParams()
        }
    }

    override fun onStop() {
        captureCurrentTabPreview()

        if (!isInPictureInPictureMode) {
            val tab = TabManager.activeTab()

            TabManager.prepareForBackground()

            /*
             * Si el usuario salió mientras multimedia estaba
             * reproduciéndose, damos a Gecko una segunda
             * oportunidad de continuar después de la transición.
             */
            if (backgroundPlaybackWasActive && BrowserPrefs.backgroundMedia(this) && tab != null) {
                listOf(180L, 650L).forEach { delay ->
                    geckoView.postDelayed(
                        { BrowserMediaController.resumeIfRecent(tab.id, 8_000L) },
                        delay
                    )
                }
            }
        }

        activityStopped = true
        super.onStop()
    }

    // ---------------------------------------------------------------
    // Mini reproductor / Picture-in-Picture
    // ---------------------------------------------------------------

    private fun isYoutubeVideoPage(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()

        return when (host) {
            "youtu.be" -> path.length > 1
            "youtube.com", "www.youtube.com", "m.youtube.com" ->
                path == "/watch" ||
                    path.startsWith("/shorts/") ||
                    path.startsWith("/live/")
            else -> false
        }
    }

    private fun isMiniPlaybackActive(tab: BrowserTab): Boolean =
        BrowserMediaController.isVideoPlaying(tab.id) ||
            BrowserMediaController.isPlaying(tab.id)

    private fun hasMiniVideo(tab: BrowserTab): Boolean =
        BrowserMediaController.isVideoPresent(tab.id) ||
            BrowserMediaController.isVideoPlaying(tab.id) ||
            (isYoutubeVideoPage(tab.url) && BrowserMediaController.isPlaying(tab.id))

    private fun isMiniVideoAvailable(): Boolean {
        val tab = TabManager.activeTab() ?: return false
        return BrowserPrefs.smartPip(this) && hasMiniVideo(tab)
    }

    private fun updateMiniPlayerChip() {
        miniPlayerButton.visibility =
            if (!immersive && isMiniVideoAvailable()) View.VISIBLE
            else View.GONE
    }

    private fun startMiniPlayer(
        silent: Boolean = false,
        requirePlayback: Boolean = false,
        delayMs: Long = 120L
    ) {
        if (isInPictureInPictureMode) return
        pendingPipEntry?.let { pending ->
            if (delayMs <= 0L) {
                geckoView.removeCallbacks(pending)
                pending.run()
            }
            return
        }

        val tab = TabManager.activeTab() ?: return
        val session = tab.session ?: return

        // Capturar el estado antes de tocar CSS, GeckoSession o PiP.
        pipPlaybackWasActive = isMiniPlaybackActive(tab)

        if (!hasMiniVideo(tab)) {
            if (!silent) {
                Toast.makeText(this, "No se detectó un video para abrir Mini.", Toast.LENGTH_SHORT).show()
            }
            updateMiniPlayerChip()
            return
        }

        if (requirePlayback && !isMiniPlaybackActive(tab)) return

        // Mantener GeckoSession activa durante la transición a PiP.
        TabManager.prepareForPictureInPicture()

        // Recorte nativo si conocemos el rectángulo del video; si no (p. ej.
        // video en un iframe), aislar el video con CSS como respaldo.
        val nativeCrop = lockForNativePip(tab)
        ExtensionManager.setPipMode(
            session,
            true,
            resumePlayback = pipPlaybackWasActive,
            isolateCss = !nativeCrop
        )

        miniPlayerButton.visibility = View.GONE

        val enterPip = Runnable {
            pendingPipEntry = null
            if (activityStopped || suppressAutoPipOnce || isFinishing || isDestroyed || attachedSession !== session ||
                TabManager.activeTab()?.id != tab.id) {
                ExtensionManager.setPipMode(session, false)
                releaseNativePip()
                return@Runnable
            }
            // Ocultar las barras ANTES de entrar: el primer cuadro de la
            // ventana mini ya debe ser solo el video.
            applyChromeVisibility(forcePip = true)

            val entered = runCatching {
                enterPictureInPictureMode(buildPipParams(tab.id))
            }.getOrDefault(false)

            if (!entered) {
                pipPlaybackWasActive = false
                ExtensionManager.setPipMode(session, false)
                releaseNativePip()
                applyChromeVisibility()
            }
        }

        /*
         * Botón manual: 120 ms para que JS prepare el aislamiento.
         * Home / cambio de app: delay 0 para evitar carrera con onStop().
         */
        pendingPipEntry = enterPip
        if (delayMs <= 0L) enterPip.run() else geckoView.postDelayed(enterPip, delayMs)
    }

    private fun lockForNativePip(tab: BrowserTab): Boolean {
        val w = geckoView.width
        val h = geckoView.height
        if (w <= 0 || h <= 0) return false

        val rect = BrowserMediaController.videoRect(tab.id) ?: return false
        if (!rect.intersect(0, 0, w, h)) return false
        val minW = (80 * resources.displayMetrics.density).toInt()
        val minH = (45 * resources.displayMetrics.density).toInt()
        if (rect.width() < minW || rect.height() < minH) return false

        pipCrop = rect
        // Tamaño fijo: aunque la ventana se achique, Gecko sigue viendo la misma
        // vista y la página no recibe ningún "resize".
        geckoView.layoutParams = android.widget.FrameLayout.LayoutParams(w, h)
        return true
    }

    /** Amplía el rectángulo del video para cubrir la ventana PiP. */
    private fun applyPipCrop() {
        val crop = pipCrop ?: return
        val fw = browserFrame.width.toFloat()
        val fh = browserFrame.height.toFloat()
        if (fw <= 0f || fh <= 0f) return

        val scale = maxOf(fw / crop.width(), fh / crop.height())
        geckoView.pivotX = 0f
        geckoView.pivotY = 0f
        geckoView.scaleX = scale
        geckoView.scaleY = scale
        geckoView.translationX = -crop.left * scale + (fw - crop.width() * scale) / 2f
        geckoView.translationY = -crop.top * scale + (fh - crop.height() * scale) / 2f
    }

    private fun releaseNativePip() {
        if (pipCrop == null && geckoView.scaleX == 1f && geckoView.translationY == 0f) return
        pipCrop = null
        geckoView.scaleX = 1f
        geckoView.scaleY = 1f
        geckoView.translationX = 0f
        geckoView.translationY = 0f
        geckoView.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    private fun buildPipParams(tabId: String?): PictureInPictureParams {
        val crop = pipCrop
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(
                if (crop != null) clampAspect(crop.width(), crop.height())
                else pipAspectRatio(tabId)
            )

        if (geckoView.width > 0 && geckoView.height > 0) {
            // El hint debe ir en coordenadas de ventana: solo el video.
            val location = IntArray(2)
            geckoView.getLocationInWindow(location)
            val hint = if (crop != null) {
                Rect(crop).apply { offset(location[0], location[1]) }
            } else {
                Rect().takeIf { geckoView.getGlobalVisibleRect(it) }
            }
            hint?.let { builder.setSourceRectHint(it) }
        }

        builder.setActions(pipActions(tabId))

        if (Build.VERSION.SDK_INT >= 31) {
            /*
             * Nexo controla manualmente el PiP desde onUserLeaveHint().
             * autoEnter desactivado para no activar PiP al entrar en
             * Configuración, Historial, etc.
             */
            builder
                .setAutoEnterEnabled(false)
                .setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    private fun pipActions(tabId: String?): List<RemoteAction> {
        val playing = tabId != null && BrowserMediaController.isPlaybackActive(tabId)
        val (icon, label, command) =
            if (playing) Triple(R.drawable.ic_pip_pause, "Pausar", PIP_COMMAND_PAUSE)
            else Triple(R.drawable.ic_pip_play, "Reproducir", PIP_COMMAND_PLAY)

        fun action(iconRes: Int, description: String, value: String, requestCode: Int) =
            RemoteAction(
                Icon.createWithResource(this, iconRes), description, description,
                PendingIntent.getBroadcast(
                    this, requestCode,
                    Intent(ACTION_PIP_CONTROL).setPackage(packageName)
                        .putExtra(EXTRA_PIP_COMMAND, value),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

        val playback = action(icon, label, command, REQUEST_PIP_CONTROL)
        if (!ExtensionManager.hasMediaBridge(TabManager.activeSession())) return listOf(playback)
        return listOf(
            action(R.drawable.ic_pip_replay, getString(R.string.seek_backward), "seek_backward", 914),
            playback,
            action(R.drawable.ic_pip_forward, getString(R.string.seek_forward), "seek_forward", 915)
        )
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(buildPipParams(TabManager.activeTab()?.id)) }
    }

    private fun pipAspectRatio(tabId: String?): Rational {
        val (rawWidth, rawHeight) =
            if (tabId != null) BrowserMediaController.videoAspect(tabId) else 16 to 9
        return clampAspect(rawWidth, rawHeight)
    }

    private fun clampAspect(rawWidth: Int, rawHeight: Int): Rational {
        val width = rawWidth.coerceAtLeast(1)
        val height = rawHeight.coerceAtLeast(1)
        val ratio = width.toDouble() / height.toDouble()

        // Android PiP acepta ratios entre ~1:2.39 y 2.39:1; dejamos margen.
        return when {
            ratio < 0.42 -> Rational(42, 100)
            ratio > 2.38 -> Rational(238, 100)
            else -> Rational(width, height)
        }
    }

    override fun onUserLeaveHint() {
        val tab = TabManager.activeTab()

        /*
         * Decidir ANTES de tocar el estado multimedia si vamos
         * realmente a Android PiP. PiP permanece visible y no
         * necesita foreground service ni reintentos de play().
         */
        val willEnterPip =
            !suppressAutoPipOnce &&
                !isInPictureInPictureMode &&
                BrowserPrefs.smartPip(this) &&
                tab != null &&
                hasMiniVideo(tab) &&
                isMiniPlaybackActive(tab)

        // El hold queda reservado para segundo plano real (sin PiP).
        backgroundPlaybackWasActive =
            !willEnterPip &&
                tab != null &&
                BrowserPrefs.backgroundMedia(this) &&
                BrowserMediaController.isPlaybackActive(tab.id)

        if (backgroundPlaybackWasActive && tab != null) {
            BrowserMediaController.holdPlayback(tab.id, 10_000L)
            MediaPlaybackService.sync(this)
        }

        if (willEnterPip) {
            startMiniPlayer(silent = true, requirePlayback = true, delayMs = 0L)
        }

        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ExtensionManager.setPipMode(
            attachedSession,
            isInPictureInPictureMode,
            resumePlayback = pipPlaybackWasActive
        )

        val tab = TabManager.activeTab()

        if (isInPictureInPictureMode) applyPipCrop() else releaseNativePip()

        when {
            isInPictureInPictureMode -> {
                /*
                 * GeckoView mantiene la sesión/compositor activos.
                 * No forzar MediaSession.play(): en YouTube puede
                 * reiniciar el decoder y producir frames negros.
                 */
                TabManager.prepareForPictureInPicture()
            }

            activityStopped -> {
                // El usuario cerró la ventana mini (la X o arrastrándola).
                pipPlaybackWasActive = false
                if (tab != null && BrowserPrefs.backgroundMedia(this) &&
                    BrowserMediaController.isPlaybackActive(tab.id)
                ) {
                    // Seguir como audio en segundo plano, con notificación.
                    BrowserMediaController.holdPlayback(tab.id, 10_000L)
                    TabManager.prepareForBackground()
                    MediaPlaybackService.sync(this)
                } else {
                    if (tab != null &&
                        !ExtensionManager.sendPipCommand(tab.session, PIP_COMMAND_PAUSE)
                    ) {
                        BrowserMediaController.pause(tab.id)
                    }
                    TabManager.prepareForBackground()
                }
            }

            else -> {
                // Volvió a pantalla completa desde la ventana mini.
                pipPlaybackWasActive = false
                TabManager.resumeActive()
            }
        }

        runCatching {
            attachedSession?.compositorController?.onPipModeChanged(isInPictureInPictureMode)
        }
        applyChromeVisibility()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_NOTIFICATIONS) MediaPlaybackService.sync(this)
    }

    override fun onDestroy() {
        pendingPipEntry?.let { geckoView.removeCallbacks(it) }
        if (!isInPictureInPictureMode) ExtensionManager.setPipMode(attachedSession, false)
        runCatching { unregisterReceiver(pipControlReceiver) }
        BrowserMediaController.attach(null)
        ExtensionManager.attachPromptActivity(null)
        TabManager.attach(null)
        if (attachedSession != null) {
            geckoView.releaseSession()
            attachedSession = null
        }
        // No cerrar las GeckoSession al recrear o destruir solo la Activity.
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (TabManager.exitFullScreen()) return
        if (TabManager.canGoBackOrPrevious()) TabManager.goBackOrPrevious()
        else super.onBackPressed()
    }

    companion object {
        const val EXTRA_OPEN_URL = "nexo.open_url"
        private const val REQUEST_MEDIA_NOTIFICATIONS = 912
        private const val REQUEST_PIP_CONTROL = 913

        private const val ACTION_PIP_CONTROL = "com.ejemplo.navegador.action.PIP_CONTROL"
        private const val EXTRA_PIP_COMMAND = "command"
        private const val PIP_COMMAND_PLAY = "play"
        private const val PIP_COMMAND_PAUSE = "pause"

        /** Alias no exportado declarado en AndroidManifest.xml. */
        private val INTERNAL_OPEN_ALIAS =
            MainActivity::class.java.name.substringBeforeLast('.') + ".InternalOpenActivity"


        /**
         * Intent para que las pantallas propias abran una URL en el navegador.
         * Pasa por el alias no exportado, que es lo que permite URLs internas.
         */
        fun openUrlIntent(context: Context, url: String): Intent =
            Intent().apply {
                component = ComponentName(context, INTERNAL_OPEN_ALIAS)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_URL, url)
            }
    }
}
