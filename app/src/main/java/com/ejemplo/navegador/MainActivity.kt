package com.ejemplo.navegador

import android.app.Activity
import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
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
        handleNavigationIntent(intent)
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
                actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
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
        popup.menu.add("Nueva pestaña")
        popup.menu.add("Nueva pestaña privada")
        val desktop = popup.menu.add("Sitio de escritorio")
        desktop.isCheckable = true
        desktop.isChecked = current?.desktopMode == true
        popup.menu.add("Compartir página")
        popup.menu.add("Traducir página")
        popup.menu.add("Extensiones")
        popup.menu.add("Historial")
        popup.menu.add("Descargas")
        popup.menu.add("Configuración")
        popup.menu.add("Cerrar pestaña")

        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Nueva pestaña" -> { viewModel.newTab(false); true }
                "Nueva pestaña privada" -> { viewModel.newTab(true); true }
                "Sitio de escritorio" -> {
                    TabManager.setDesktopMode(!(current?.desktopMode ?: false)); true
                }
                "Compartir página" -> { shareCurrent(); true }
                "Traducir página" -> {
                    TranslatorManager.translateActive(
                        this
                    ) { _, message ->
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    true
                }
                "Extensiones" -> { suppressAutoPipOnce = true; startActivity(Intent(this, ExtensionStoreActivity::class.java)); true }
                "Historial" -> { suppressAutoPipOnce = true; startActivity(Intent(this, HistoryActivity::class.java)); true }
                "Descargas" -> { suppressAutoPipOnce = true; startActivity(Intent(this, DownloadsActivity::class.java)); true }
                "Configuración" -> { suppressAutoPipOnce = true; startActivity(Intent(this, SettingsActivity::class.java)); true }
                "Cerrar pestaña" -> { viewModel.closeCurrentTab(); true }
                else -> false
            }
        }
        popup.show()
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
            setPadding((10*d).toInt(), (8*d).toInt(), (10*d).toInt(), (8*d).toInt())
            setBackgroundColor(p.surface)
        }

        TabManager.allTabs().forEach { tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ThemeManager.rounded(this@MainActivity, p.elevated, 18f)
                setPadding((8*d).toInt(), (8*d).toInt(), (8*d).toInt(), (8*d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8*d).toInt() }
            }

            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((92*d).toInt(), (70*d).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bitmap = if (tab.isPrivate) null else TabPreviewStore.load(this@MainActivity, tab.id)
                if (bitmap != null) setImageBitmap(bitmap) else setImageResource(R.drawable.ic_nexo_logo)
                setBackgroundColor(p.surface)
            }

            val text = TextView(this).apply {
                this.text = (if (tab.isPrivate) "◉ Privada · " else "") +
                    tab.title.take(70)
                textSize = 14.5f
                maxLines = 2
                setTextColor(p.text)
                setPadding((10*d).toInt(), 0, (8*d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val close = Button(this).apply {
                this.text = "×"
                textSize = 20f
                minWidth = 0
                layoutParams = LinearLayout.LayoutParams((44*d).toInt(), (44*d).toInt())
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
            Toast.makeText(
                this,
                "Abriendo instalador de extensión…",
                Toast.LENGTH_SHORT
            ).show()

            ExtensionManager.installUrl(this, url) { _, message ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "Este recurso no se puede descargar directamente", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val uri = Uri.parse(url)
            val name = uri.lastPathSegment?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() } ?: "nexo_${System.currentTimeMillis()}"
            val request = DownloadManager.Request(uri)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setTitle(name)
                .setDescription(uri.host ?: "Nexo Browser")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .addRequestHeader("User-Agent", GeckoSession.getDefaultUserAgent())
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "No se pudo descargar: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareCurrent() {
        suppressAutoPipOnce = true
        val tab = TabManager.activeTab() ?: return
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
        ThemeManager.styleButton(
            this,
            miniPlayerButton,
            primary = true
        )
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

    private fun handleNavigationIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN_URL)?.takeIf { it.isNotBlank() }?.let {
            TabManager.navigate(it)
        }
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
            val tab =
                TabManager.activeTab()

            TabManager.prepareForBackground()

            /*
             * Si el usuario salió mientras multimedia estaba
             * reproduciéndose, damos a Gecko una segunda
             * oportunidad de continuar después de la transición.
             */
            if (
                backgroundPlaybackWasActive &&
                BrowserPrefs.backgroundMedia(this) &&
                tab != null
            ) {
                geckoView.postDelayed(
                    {
                        BrowserMediaController
                            .resumeIfRecent(
                                tab.id,
                                8_000L
                            )
                    },
                    180L
                )

                geckoView.postDelayed(
                    {
                        BrowserMediaController
                            .resumeIfRecent(
                                tab.id,
                                8_000L
                            )
                    },
                    650L
                )
            }
        }

        super.onStop()
    }

    private fun isYoutubeVideoPage(
    url: String
): Boolean {
    val uri =
        runCatching {
            Uri.parse(url)
        }.getOrNull()
            ?: return false

    val host =
        uri.host
            ?.lowercase()
            .orEmpty()

    val path =
        uri.path
            ?.lowercase()
            .orEmpty()

    return when (host) {
        "youtu.be" ->
            path.length > 1

        "youtube.com",
        "www.youtube.com",
        "m.youtube.com" ->
            path == "/watch" ||
                path.startsWith("/shorts/") ||
                path.startsWith("/live/")

        else ->
            false
    }
}

private fun isMiniPlaybackActive(
    tab: BrowserTab
): Boolean =
    BrowserMediaController
        .isVideoPlaying(tab.id) ||
        BrowserMediaController
            .isPlaying(tab.id)

private fun hasMiniVideo(
    tab: BrowserTab
): Boolean =
    BrowserMediaController
        .isVideoPresent(tab.id) ||
        BrowserMediaController
            .isVideoPlaying(tab.id) ||
        (
            isYoutubeVideoPage(tab.url) &&
                BrowserMediaController
                    .isPlaying(tab.id)
        )

private fun isMiniVideoAvailable(): Boolean {
    val tab =
        TabManager.activeTab()
            ?: return false

    return BrowserPrefs
        .smartPip(this) &&
        hasMiniVideo(tab)
}

private fun updateMiniPlayerChip() {
    miniPlayerButton.visibility =
        if (
            !isInPictureInPictureMode &&
            isMiniVideoAvailable()
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
}

private fun startMiniPlayer(
    silent: Boolean = false,
    requirePlayback: Boolean = false,
    delayMs: Long = 120L
) {
    if (Build.VERSION.SDK_INT < 26) {
        if (!silent) {
            Toast.makeText(
                this,
                "Mini reproductor requiere Android 8 o superior",
                Toast.LENGTH_SHORT
            ).show()
        }

        return
    }

    if (isInPictureInPictureMode) {
        return
    }

    val tab =
        TabManager.activeTab()
            ?: return

    val session =
        tab.session
            ?: return

    /*
     * Capturar el estado antes de tocar CSS, GeckoSession
     * o Picture-in-Picture.
     */
    pipPlaybackWasActive =
        isMiniPlaybackActive(tab)

    if (!hasMiniVideo(tab)) {
        if (!silent) {
            Toast.makeText(
                this,
                "No se detectó un video para abrir Mini.",
                Toast.LENGTH_SHORT
            ).show()
        }

        updateMiniPlayerChip()
        return
    }

    if (
        requirePlayback &&
        !isMiniPlaybackActive(tab)
    ) {
        return
    }

    /*
     * Mantener GeckoSession activa durante
     * la transición a Android PiP.
     */
    TabManager.prepareForPictureInPicture()

    /*
     * Pedir al bridge JS que aisle
     * únicamente el video HTML5.
     */
    ExtensionManager.setPipMode(
        session,
        true
    )

    miniPlayerButton.visibility =
        View.GONE

    val enterPip =
        Runnable {
            val builder =
                PictureInPictureParams
                    .Builder()
                    .setAspectRatio(
                        pipAspectRatio(
                            tab.id
                        )
                    )

            if (
                geckoView.width > 0 &&
                geckoView.height > 0
            ) {
                builder.setSourceRectHint(
                    Rect(
                        geckoView.left,
                        geckoView.top,
                        geckoView.right,
                        geckoView.bottom
                    )
                )
            }

            if (
                Build.VERSION.SDK_INT >= 31
            ) {
                /*
                 * Nexo controla manualmente el PiP
                 * desde onUserLeaveHint().
                 *
                 * Se mantiene autoEnter desactivado
                 * para no activar PiP al entrar en
                 * Configuración, Historial, etc.
                 */
                builder
                    .setAutoEnterEnabled(false)
                    .setSeamlessResizeEnabled(true)
            }

            val entered =
                runCatching {
                    enterPictureInPictureMode(
                        builder.build()
                    )
                }.getOrDefault(false)

            if (!entered) {
                pipPlaybackWasActive = false

                ExtensionManager.setPipMode(
                    session,
                    false
                )

                updateMiniPlayerChip()
            } else if (
                pipPlaybackWasActive
            ) {
                /*
                 * Algunos sitios (especialmente YouTube) envían
                 * una pausa transitoria al cambiar de ventana.
                 * Reanudamos únicamente porque sabemos que
                 * estaba reproduciéndose antes de entrar a PiP.
                 */
                geckoView.postDelayed(
                    {
                        BrowserMediaController
                            .resumeIfRecent(
                                tab.id,
                                8_000L
                            )
                    },
                    180L
                )

                geckoView.postDelayed(
                    {
                        BrowserMediaController
                            .resumeIfRecent(
                                tab.id,
                                8_000L
                            )
                    },
                    650L
                )
            }
        }

    /*
     * Botón manual:
     * 120 ms para permitir que JS prepare
     * el aislamiento.
     *
     * Home / cambio de app:
     * delay 0 para evitar carrera con onStop().
     */
    if (delayMs <= 0L) {
        enterPip.run()
    } else {
        geckoView.postDelayed(
            enterPip,
            delayMs
        )
    }
}

private fun updatePipParams() {
    if (Build.VERSION.SDK_INT < 26) {
        return
    }

    val tab =
        TabManager.activeTab()

    val builder =
        PictureInPictureParams
            .Builder()
            .setAspectRatio(
                pipAspectRatio(
                    tab?.id
                )
            )

    if (
        geckoView.width > 0 &&
        geckoView.height > 0
    ) {
        builder.setSourceRectHint(
            Rect(
                geckoView.left,
                geckoView.top,
                geckoView.right,
                geckoView.bottom
            )
        )
    }

    if (
        Build.VERSION.SDK_INT >= 31
    ) {
        builder
            .setAutoEnterEnabled(false)
            .setSeamlessResizeEnabled(true)
    }

    runCatching {
        setPictureInPictureParams(
            builder.build()
        )
    }
}

private fun pipAspectRatio(
    tabId: String?
): Rational {
    val (rawWidth, rawHeight) =
        if (tabId != null) {
            BrowserMediaController
                .videoAspect(tabId)
        } else {
            16 to 9
        }

    val width =
        rawWidth.coerceAtLeast(1)

    val height =
        rawHeight.coerceAtLeast(1)

    val ratio =
        width.toDouble() /
            height.toDouble()

    /*
     * Android PiP acepta aproximadamente
     * ratios entre 1:2.39 y 2.39:1.
     *
     * Dejamos margen de seguridad.
     */
    return when {
        ratio < 0.42 ->
            Rational(
                42,
                100
            )

        ratio > 2.38 ->
            Rational(
                238,
                100
            )

        else ->
            Rational(
                width,
                height
            )
    }
}

override fun onUserLeaveHint() {
    val tab =
        TabManager.activeTab()

    /*
     * También cubre modo solo audio / segundo plano.
     * Se captura antes de que el sitio reciba eventos de
     * lifecycle y pueda marcarse temporalmente como pausado.
     */
    backgroundPlaybackWasActive =
        tab != null &&
        BrowserPrefs.backgroundMedia(this) &&
        BrowserMediaController
            .isPlaybackActive(tab.id)

    if (
        !suppressAutoPipOnce &&
        !isInPictureInPictureMode &&
        BrowserPrefs.smartPip(this) &&
        tab != null &&
        hasMiniVideo(tab) &&
        isMiniPlaybackActive(tab)
    ) {
        startMiniPlayer(
            silent = true,
            requirePlayback = true,
            delayMs = 0L
        )
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
            isInPictureInPictureMode
        )

        if (isInPictureInPictureMode) {
            TabManager.prepareForPictureInPicture()

            val tab =
                TabManager.activeTab()

            if (
                pipPlaybackWasActive &&
                tab != null
            ) {
                geckoView.postDelayed(
                    {
                        BrowserMediaController
                            .resumeIfRecent(
                                tab.id,
                                8_000L
                            )
                    },
                    140L
                )
            }
        } else {
            pipPlaybackWasActive = false
            TabManager.resumeActive()
        }

        runCatching {
            attachedSession
                ?.compositorController
                ?.onPipModeChanged(isInPictureInPictureMode)
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
    }
}
