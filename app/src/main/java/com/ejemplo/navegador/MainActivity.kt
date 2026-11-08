package com.ejemplo.navegador

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
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
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var topBar: View
    private lateinit var navBar: View
    private lateinit var viewModel: BrowserViewModel
    private var attachedSession: GeckoSession? = null
    private var settingsFingerprint = ""
    private var mediaNotificationPermissionAsked = false
    private var suppressAutoPipOnce = false

    // Estado capturado ANTES de que Android/YouTube cambien
    // la Activity de foreground a PiP/background.
    private var pipPlaybackWasActive = false
    private var backgroundPlaybackWasActive = false

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
        TabManager.initialize(this)
        TabManager.attach(this)
        ExtensionManager.attachPromptActivity(this)

        // Al recrear la Activity (rotación, cambio de tema) no repetir la
        // navegación del intent original.
        if (savedInstanceState == null) handleNavigationIntent(intent)
    }

    private fun bindViews() {
        root = findViewById(R.id.rootView)
        topBar = findViewById(R.id.topBar)
        navBar = findViewById(R.id.navBar)
        geckoView = findViewById(R.id.geckoView)
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
            if (focused) {
                omnibox.selectAll()
                clearOmniboxButton.visibility =
                    if (omnibox.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            } else clearOmniboxButton.visibility = View.GONE
        }
    }

    private fun navigateOmnibox() {
        viewModel.navigate(omnibox.text?.toString().orEmpty())
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(omnibox.windowToken, 0)
        omnibox.clearFocus()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val current = TabManager.activeTab()
        val menu = popup.menu

        menu.add(Menu.NONE, MENU_NEW_TAB, Menu.NONE, "Nueva pestaña")
        menu.add(Menu.NONE, MENU_NEW_PRIVATE_TAB, Menu.NONE, "Nueva pestaña privada")
        menu.add(Menu.NONE, MENU_DESKTOP, Menu.NONE, "Sitio de escritorio").apply {
            isCheckable = true
            isChecked = current?.desktopMode == true
        }
        menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, "Compartir página")
        menu.add(Menu.NONE, MENU_TRANSLATE, Menu.NONE, "Traducir página")
        menu.add(Menu.NONE, MENU_EXTENSIONS, Menu.NONE, "Extensiones")
        menu.add(Menu.NONE, MENU_HISTORY, Menu.NONE, "Historial")
        menu.add(Menu.NONE, MENU_DOWNLOADS, Menu.NONE, "Descargas")
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, "Configuración")
        menu.add(Menu.NONE, MENU_CLOSE_TAB, Menu.NONE, "Cerrar pestaña")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_NEW_TAB -> viewModel.newTab(false)
                MENU_NEW_PRIVATE_TAB -> viewModel.newTab(true)
                MENU_DESKTOP -> TabManager.setDesktopMode(!(current?.desktopMode ?: false))
                MENU_SHARE -> shareCurrent()
                MENU_TRANSLATE -> TranslatorManager.translateActive(this) { _, message ->
                    runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
                }
                MENU_EXTENSIONS -> openScreen(ExtensionStoreActivity::class.java)
                MENU_HISTORY -> openScreen(HistoryActivity::class.java)
                MENU_DOWNLOADS -> openScreen(DownloadsActivity::class.java)
                MENU_SETTINGS -> openScreen(SettingsActivity::class.java)
                MENU_CLOSE_TAB -> viewModel.closeCurrentTab()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun openScreen(screen: Class<out Activity>) {
        suppressAutoPipOnce = true
        startActivity(Intent(this, screen))
    }

    private fun captureCurrentTabPreview() {
        val tab = TabManager.activeTab() ?: return
        if (
            tab.isPrivate ||
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
        val p = ThemeManager.palette(this)
        var dialog: AlertDialog? = null
        val d = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            setBackgroundColor(p.surface)
        }

        TabManager.allTabs().forEach { tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ThemeManager.rounded(this@MainActivity, p.elevated, 18f)
                setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * d).toInt() }
            }

            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((92 * d).toInt(), (70 * d).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bitmap = if (tab.isPrivate) null else TabPreviewStore.load(this@MainActivity, tab.id)
                if (bitmap != null) setImageBitmap(bitmap) else setImageResource(R.drawable.ic_nexo_logo)
                setBackgroundColor(p.surface)
            }

            val text = TextView(this).apply {
                this.text = (if (tab.isPrivate) "◉ Privada · " else "") + tab.title.take(70)
                textSize = 14.5f
                maxLines = 2
                setTextColor(p.text)
                setPadding((10 * d).toInt(), 0, (8 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val close = Button(this).apply {
                this.text = "×"
                textSize = 20f
                minWidth = 0
                layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
                ThemeManager.styleButton(this@MainActivity, this)
                setOnClickListener {
                    TabManager.closeTab(tab.id)
                    dialog?.dismiss()
                    showTabsDialog()
                }
            }

            row.setOnClickListener {
                captureCurrentTabPreview()
                TabManager.switchTo(tab.id)
                dialog?.dismiss()
            }
            preview.setOnClickListener { row.performClick() }
            text.setOnClickListener { row.performClick() }
            row.addView(preview)
            row.addView(text)
            row.addView(close)
            container.addView(row)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Pestañas (${TabManager.allTabs().size})")
            .setView(container)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Privada") { _, _ -> viewModel.newTab(true) }
            .setPositiveButton("Nueva") { _, _ -> viewModel.newTab(false) }
            .create()
        dialog?.show()
    }

    override fun onContextElement(element: GeckoSession.ContentDelegate.ContextElement) {
        val link = element.linkUri.orEmpty()
        val image = element.srcUri.orEmpty()
        val options = mutableListOf<String>()

        if (link.isNotBlank()) options += listOf(
            "Abrir enlace", "Abrir enlace en nueva pestaña", "Copiar enlace", "Descargar enlace"
        )
        if (image.isNotBlank()) options += listOf(
            "Abrir imagen", "Abrir imagen en nueva pestaña", "Copiar URL de imagen", "Descargar imagen"
        )
        if (options.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Acciones")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Abrir enlace" -> TabManager.navigate(link)
                    "Abrir enlace en nueva pestaña" -> TabManager.createTab(link)
                    "Copiar enlace" -> copyText("Enlace", link)
                    "Descargar enlace" -> downloadUrl(link)
                    "Abrir imagen" -> TabManager.navigate(image)
                    "Abrir imagen en nueva pestaña" -> TabManager.createTab(image)
                    "Copiar URL de imagen" -> copyText("Imagen", image)
                    "Descargar imagen" -> downloadUrl(image)
                }
            }.show()
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
        topBar.setBackgroundColor(p.surface)
        navBar.setBackgroundColor(p.surface)
        ThemeManager.styleEdit(this, omnibox)
        ThemeManager.styleButton(this, miniPlayerButton, primary = true)
        listOf(
            R.id.tabButton,
            R.id.menuButton,
            R.id.clearOmniboxButton
        ).forEach { ThemeManager.styleButton(this, findViewById(it)) }

        listOf(
            backButton,
            forwardButton,
            findViewById<ImageButton>(R.id.homeButton),
            findViewById<ImageButton>(R.id.reloadButton),
            findViewById<ImageButton>(R.id.newTabButton)
        ).forEach { ThemeManager.styleIconButton(this, it) }

        privateBanner.setBackgroundColor(p.accent)
        privateBanner.setTextColor(p.onAccent)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
        settingsFingerprint = fingerprint()
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

        TabManager.navigate(raw)
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
        renderTab(tab)
        updatePipParams()
    }

    override fun onTabChanged(tab: BrowserTab) {
        if (tab.id == TabManager.activeTab()?.id) renderTab(tab)
    }

    override fun onProgress(tab: BrowserTab, progressValue: Int, loading: Boolean) {
        if (tab.id != TabManager.activeTab()?.id) return
        progress.progress = progressValue
        progress.visibility = if (loading) View.VISIBLE else View.GONE
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
    }

    private fun renderTab(tab: BrowserTab) {
        if (!omnibox.hasFocus()) omnibox.setText(tab.url)
        backButton.isEnabled = TabManager.canGoBackOrPrevious()
        forwardButton.isEnabled = tab.canGoForward
        privateBanner.visibility =
            if (!isInPictureInPictureMode && tab.isPrivate) View.VISIBLE else View.GONE
        title = if (tab.isPrivate) "Privado · ${tab.title}" else tab.title
        updateMiniPlayerChip()
    }

    override fun onStart() {
        super.onStart()
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
            if (!isInPictureInPictureMode && isMiniVideoAvailable()) View.VISIBLE
            else View.GONE
    }

    private fun startMiniPlayer(
        silent: Boolean = false,
        requirePlayback: Boolean = false,
        delayMs: Long = 120L
    ) {
        if (isInPictureInPictureMode) return

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

        // Pedir al bridge JS que aísle únicamente el video HTML5.
        ExtensionManager.setPipMode(session, true)

        miniPlayerButton.visibility = View.GONE

        val enterPip = Runnable {
            val entered = runCatching {
                enterPictureInPictureMode(buildPipParams(tab.id))
            }.getOrDefault(false)

            if (!entered) {
                pipPlaybackWasActive = false
                ExtensionManager.setPipMode(session, false)
                updateMiniPlayerChip()
            }
        }

        /*
         * Botón manual: 120 ms para que JS prepare el aislamiento.
         * Home / cambio de app: delay 0 para evitar carrera con onStop().
         */
        if (delayMs <= 0L) enterPip.run() else geckoView.postDelayed(enterPip, delayMs)
    }

    private fun buildPipParams(tabId: String?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio(tabId))

        if (geckoView.width > 0 && geckoView.height > 0) {
            // El hint debe ir en coordenadas de ventana.
            val rect = Rect()
            if (geckoView.getGlobalVisibleRect(rect)) builder.setSourceRectHint(rect)
        }

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

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(buildPipParams(TabManager.activeTab()?.id)) }
    }

    private fun pipAspectRatio(tabId: String?): Rational {
        val (rawWidth, rawHeight) =
            if (tabId != null) BrowserMediaController.videoAspect(tabId) else 16 to 9

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
        ExtensionManager.setPipMode(attachedSession, isInPictureInPictureMode)

        if (isInPictureInPictureMode) {
            /*
             * GeckoView mantiene la sesión/compositor activos.
             * No forzar MediaSession.play(): en YouTube puede
             * reiniciar el decoder y producir frames negros.
             */
            TabManager.prepareForPictureInPicture()
        } else {
            pipPlaybackWasActive = false
            TabManager.resumeActive()
        }

        runCatching {
            attachedSession?.compositorController?.onPipModeChanged(isInPictureInPictureMode)
        }
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        navBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        progress.visibility = View.GONE
        val current = TabManager.activeTab()
        privateBanner.visibility =
            if (!isInPictureInPictureMode && current?.isPrivate == true) View.VISIBLE else View.GONE
        updateMiniPlayerChip()
        root.requestLayout()
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
        if (TabManager.canGoBackOrPrevious()) TabManager.goBackOrPrevious()
        else super.onBackPressed()
    }

    companion object {
        const val EXTRA_OPEN_URL = "nexo.open_url"
        private const val REQUEST_MEDIA_NOTIFICATIONS = 912

        /** Alias no exportado declarado en AndroidManifest.xml. */
        private val INTERNAL_OPEN_ALIAS =
            MainActivity::class.java.name.substringBeforeLast('.') + ".InternalOpenActivity"

        private const val MENU_NEW_TAB = 1
        private const val MENU_NEW_PRIVATE_TAB = 2
        private const val MENU_DESKTOP = 3
        private const val MENU_SHARE = 4
        private const val MENU_TRANSLATE = 5
        private const val MENU_EXTENSIONS = 6
        private const val MENU_HISTORY = 7
        private const val MENU_DOWNLOADS = 8
        private const val MENU_SETTINGS = 9
        private const val MENU_CLOSE_TAB = 10

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
