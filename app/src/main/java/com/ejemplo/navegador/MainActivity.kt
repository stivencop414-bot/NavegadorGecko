package com.ejemplo.navegador

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.SearchManager
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

class MainActivity :
    Activity(),
    TabManager.Listener,
    BrowserMediaController.Listener,
    LiveCaptions.Listener,
    BrowserHost {
    private lateinit var captionsButton: Button
    private lateinit var captionView: TextView
    private var lastCaptionMessage: String? = null
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
    private lateinit var browserFrame: PullRefreshLayout
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
    private var wasBackgrounded = false

    // Selector de archivos y permisos pedidos por páginas web.
    private var filePickerCallback: ((List<Uri>) -> Unit)? = null
    private val permissionCallbacks = mutableMapOf<Int, (Boolean) -> Unit>()
    private var nextPermissionRequest = REQUEST_SITE_PERMISSIONS

    // Buscar en la página.
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    /** Última miniatura automática por pestaña: dirección y momento. */
    private val lastAutoPreview = mutableMapOf<String, Pair<String, Long>>()
    private val idlePreview = Runnable { captureCurrentTabPreview() }

    override val hostActivity: Activity get() = this

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
            // Tras actualizar se muestra el cierre guardado por la versión
            // anterior: indicarlo para no confundirlo con un fallo nuevo.
            val current = CrashLog.isFromCurrentVersion(this, crash)
            AlertDialog.Builder(this)
                .setTitle(
                    if (current) "Nexo detectó un cierre inesperado"
                    else "Cierre de una versión anterior de Nexo"
                )
                .setMessage(
                    if (current) crash.take(8000)
                    else "Este informe es de antes de actualizar; la versión instalada ya puede " +
                        "incluir la corrección.\n\n" + crash.take(8000)
                )
                .setNegativeButton("Cerrar", null)
                .setPositiveButton("Copiar") { _, _ ->
                    copyText("Nexo crash", crash)
                }.show()
        }

        viewModel = BrowserViewModel(applicationContext)
        BrowserHosts.attach(this)
        bindViews()
        BrowserMediaController.attach(this)
        LiveCaptions.attach(this)
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
            onOpen = { url -> TabManager.navigate(url) },
            onPrivate = { viewModel.newTab(true) },
            onShield = { openShieldPanel() },
            onExtensions = { openScreen(ExtensionStoreActivity::class.java) },
            onAction = { action -> startPageAction(action) }
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
        browserFrame.enabledForPage = {
            BrowserPrefs.pullToRefresh(this) && !isInPictureInPictureMode && !fullScreenActive &&
                startPage.visibility != View.VISIBLE && findBar.visibility != View.VISIBLE &&
                TabManager.activeTab()?.let { !BrowserPrefs.isNexoHome(it.url) } == true
        }
        browserFrame.onRefresh = { TabManager.reload() }
        omnibox = findViewById(R.id.omnibox)
        progress = findViewById(R.id.pageProgress)
        tabButton = findViewById(R.id.tabButton)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        clearOmniboxButton = findViewById(R.id.clearOmniboxButton)
        privateBanner = findViewById(R.id.privateBanner)
        miniPlayerButton = findViewById(R.id.miniPlayerButton)
        captionsButton = findViewById(R.id.captionsButton)
        captionView = findViewById(R.id.captionView)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
    }

    private fun configureUi() {
        findViewById<TextView>(R.id.brandText).setOnClickListener { viewModel.goHome() }
        findViewById<ImageButton>(R.id.reloadButton).apply {
            setOnClickListener { TabManager.reload() }
            // Mantener pulsado: recarga completa, sin caché.
            setOnLongClickListener {
                TabManager.reload(bypassCache = true)
                Toast.makeText(this@MainActivity, "Recarga completa (sin caché)", Toast.LENGTH_SHORT).show()
                true
            }
        }
        backButton.setOnClickListener { TabManager.goBackOrPrevious() }
        forwardButton.setOnClickListener { TabManager.goForward() }
        findViewById<ImageButton>(R.id.homeButton).setOnClickListener { viewModel.goHome() }
        findViewById<ImageButton>(R.id.newTabButton).apply {
            setOnClickListener { viewModel.newTab(false) }
            // Mantener pulsado: pestaña privada.
            setOnLongClickListener { viewModel.newTab(true); true }
        }
        tabButton.setOnClickListener { showTabsDialog() }
        tabButton.setOnLongClickListener { showTabQuickMenu(); true }
        // Gestos de la barra: a los lados cambia de pestaña, hacia arriba abre el selector.
        findViewById<GestureDock>(R.id.browserDock).apply {
            gesturesEnabled = { !omnibox.hasFocus() && !fullScreenActive && !isInPictureInPictureMode }
            onSwipeTab = { offset -> switchAdjacentTab(offset) }
            onSwipeUp = { showTabsDialog() }
        }
        findViewById<ImageButton>(R.id.tabToolsButton).apply {
            setOnClickListener { TabTools.show(this@MainActivity) }
            // Pulsación larga: el buzón de la identidad actual. Nunca crea una identidad nueva por accidente.
            setOnLongClickListener {
                val burner = TabManager.activeTab()?.burnerId
                if (burner != null) BurnerMailbox.show(this@MainActivity, burner) else TabTools.show(this@MainActivity)
                true
            }
        }
        findViewById<Button>(R.id.menuButton).setOnClickListener { showMenu(it) }
        privateBanner.setOnClickListener {
            TabManager.activeTab()?.burnerId?.let { BurnerMailbox.show(this, it) }
        }
        miniPlayerButton.setOnClickListener { startMiniPlayer() }
        captionsButton.setOnClickListener { toggleCaptions() }
        captionsButton.setOnLongClickListener {
            CaptionsUi.showSheet(this) { refreshCaptionsUi() }
            true
        }

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

        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = findInPage(restart = true)
        })
        findInput.setOnEditorActionListener { _, _, _ ->
            findInPage()
            true
        }
        findViewById<ImageButton>(R.id.findPrev).setOnClickListener { findInPage(backwards = true) }
        findViewById<ImageButton>(R.id.findNext).setOnClickListener { findInPage() }
        findViewById<ImageButton>(R.id.findClose).setOnClickListener { hideFindBar() }
    }

    // ---------------------------------------------------------------
    // Buscar en la página
    // ---------------------------------------------------------------

    private fun showFindBar() {
        if (BrowserPrefs.isNexoHome(TabManager.activeTab()?.url)) {
            Toast.makeText(this, "Abre una página para buscar en ella", Toast.LENGTH_SHORT).show()
            return
        }
        findBar.visibility = View.VISIBLE
        findCount.text = ""
        findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(findInput, 0)
        if (!findInput.text.isNullOrEmpty()) findInPage(restart = true)
    }

    private fun hideFindBar() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
        runCatching { TabManager.activeSession()?.finder?.clear() }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(findInput.windowToken, 0)
        findInput.clearFocus()
    }

    private fun findInPage(backwards: Boolean = false, restart: Boolean = false) {
        val finder = TabManager.activeSession()?.finder ?: return
        val term = findInput.text?.toString().orEmpty()
        if (term.isEmpty()) {
            runCatching { finder.clear() }
            findCount.text = ""
            return
        }
        runCatching {
            finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or
                GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
            // Repetir el mismo texto avanza al siguiente resultado.
            if (restart) finder.clear()
            finder.find(term, if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0).accept(
                { result ->
                    findCount.text = when {
                        result == null || !result.found -> "0/0"
                        else -> "${result.current}/${result.total}"
                    }
                },
                { findCount.text = "" }
            )
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

        val primaryActions = listOf(
            SheetAction(R.drawable.ic_nav_add, "Nueva pestaña") { viewModel.newTab(false) },
            SheetAction(R.drawable.ic_menu_private, "Privada") { viewModel.newTab(true) },
            SheetAction(R.drawable.ic_menu_burner, "Burner", subtitle = "Aislada + correo") { BurnerMailbox.open(this) }
        )
        val onPage = current != null && !BrowserPrefs.isNexoHome(current.url)
        val reopen = TabManager.lastClosed()
        val actions = listOf(
            SheetAction(R.drawable.ic_menu_history, "Historial") { openScreen(HistoryActivity::class.java) },
            SheetAction(R.drawable.ic_menu_download, "Descargas", active = DownloadStore.activeDownloads().isNotEmpty()) {
                openScreen(DownloadsActivity::class.java)
            },
            SheetAction(R.drawable.ic_menu_translate, "Traducir") {
                TranslatorManager.translateActive(this) { _, message ->
                    runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
                }
            },
            SheetAction(R.drawable.ic_menu_desktop, "Escritorio", active = current?.desktopMode == true) {
                TabManager.setDesktopMode(!(current?.desktopMode ?: false))
            },
            SheetAction(R.drawable.ic_menu_copy, "Copiar URL") { current?.let { copyText("Enlace", it.url) } },
            SheetAction(R.drawable.ic_menu_share, "Compartir") { shareCurrent() },
            SheetAction(R.drawable.ic_menu_find, "Buscar") { showFindBar() },
            SheetAction(R.drawable.ic_menu_pin, "Fijar en inicio") { pinCurrentToHome() },
            SheetAction(R.drawable.ic_menu_screenshot, "Captura") { saveScreenshot() },
            SheetAction(R.drawable.ic_menu_pdf, "Guardar PDF") { savePageAsPdf() },
            if (reopen != null) SheetAction(R.drawable.ic_menu_reopen, "Reabrir cerrada") { TabManager.reopenClosed() }
            else SheetAction(R.drawable.ic_tab_tools, "Herramientas") { TabTools.show(this) },
            SheetAction(R.drawable.ic_menu_settings, "Ajustes") { openScreen(SettingsActivity::class.java) }
        ).filter { onPage || it.icon !in PAGE_ONLY_ACTIONS }
        val menuHeader = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(NexoSheets.header(
                this@MainActivity, "Menú de Nexo",
                if (BrowserPrefs.isNexoHome(current?.url)) "Página de inicio" else current?.url?.let { displayUrl(it) }
            ), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_menu_close)
                contentDescription = "Cerrar menú"
                ThemeManager.styleIconButton(this@MainActivity, this)
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt()).apply {
                marginEnd = (16 * resources.displayMetrics.density).toInt()
            })
        }
        val content = NexoSheets.column(
            this, menuHeader, NexoSheets.primaryActions(this, primaryActions, dismiss)
        )
        content.addView(NexoSheets.grid(this, actions, dismiss = dismiss))
        extensionShortcuts(dismiss)?.let { content.addView(it) }
        sheet = NexoSheets.show(this, content)
    }

    /**
     * Controles de extensiones: PhantomShield con su interruptor y, debajo, una
     * fila deslizable con todas las demás (icono real e insignia de cada una).
     */
    private fun extensionShortcuts(dismiss: () -> Unit): View? {
        val phantom = ExtensionManager.installedPhantom()
        val others = ExtensionManager.actionableExtensions().filterNot { it.id == ExtensionManager.PHANTOMSHIELD_ID }
        val items = listOfNotNull(phantom)
        val p = ThemeManager.palette(this)
        val d = resources.displayMetrics.density
        fun dp(value: Float) = (value * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(10f))
            addView(TextView(this@MainActivity).apply {
                text = "EXTENSIONES  ›"; textSize = 10f; letterSpacing = 0.08f
                typeface = resources.getFont(R.font.inter_variable); setTextColor(if (p.isDark) 0xFFC4B5FD.toInt() else p.accent)
                minimumHeight = dp(40f); gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true; contentDescription = "Administrar extensiones"
                setOnClickListener { dismiss(); openScreen(ExtensionsActivity::class.java) }
            })
            if (items.isEmpty() && others.isEmpty()) addView(TextView(this@MainActivity).apply {
                text = "Administrar extensiones"; textSize = 13f; setTextColor(p.muted)
                minimumHeight = dp(48f); gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true; setOnClickListener { dismiss(); openScreen(ExtensionsActivity::class.java) }
            })
            items.forEach { extension ->
                val isShield = extension.id == ExtensionManager.PHANTOMSHIELD_ID
                val badge = if (isShield) ExtensionManager.phantomBadge() else null
                val icon = ImageView(this@MainActivity).apply {
                    setImageResource(if (isShield) R.drawable.ic_quick_shield else R.drawable.ic_extension_placeholder)
                    if (!isShield) imageTintList = android.content.res.ColorStateList.valueOf(p.accent)
                    setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                    background = ThemeManager.rounded(this@MainActivity, if (isShield) 0x2210B981 else p.accentSoft, 12f)
                }
                if (!isShield) ExtensionManager.loadIcon(extension, dp(40f)) { bitmap ->
                    if (bitmap != null) runOnUiThread { icon.imageTintList = null; icon.setImageBitmap(bitmap) }
                }
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(76f); setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                    val surface = if (isShield) androidx.core.graphics.ColorUtils.blendARGB(p.surface, 0xFF10B981.toInt(), if (p.isDark) 0.10f else 0.05f) else p.elevated
                    background = ThemeManager.ripple(this@MainActivity, ThemeManager.rounded(this@MainActivity, surface, 16f, if (isShield) 0x5510B981 else p.outline), 16f)
                    isClickable = true; isFocusable = true
                    contentDescription = "Abrir panel de ${extension.metaData.name ?: "extensión"}"
                    setOnClickListener {
                        dismiss()
                        if (!extension.metaData.enabled) openScreen(ExtensionsActivity::class.java)
                        else ExtensionManager.runAction(extension) { ok, message ->
                            if (!ok) runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
                        }
                    }
                    addView(icon, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginEnd = dp(12f) })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text = extension.metaData.name ?: "Extensión"; textSize = 12.5f
                            typeface = android.graphics.Typeface.create(resources.getFont(R.font.inter_variable), android.graphics.Typeface.BOLD)
                            setTextColor(p.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = when {
                                !extension.metaData.enabled -> "Extensión desactivada"
                                badge == "OFF" -> "Bloqueo pausado"
                                badge == "PAUSE" -> "Pausada en esta página"
                                badge != null -> "$badge bloqueos en esta página"
                                else -> "Abrir controles y ajustes"
                            }
                            textSize = 10.5f; setTextColor(p.muted); setPadding(0, dp(4f), 0, 0)
                            if (badge?.matches(Regex("[0-9]{1,3}\\+?")) == true) typeface = resources.getFont(R.font.jetbrains_mono)
                        })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    if (isShield) addView(android.widget.Switch(this@MainActivity).apply {
                        isChecked = extension.metaData.enabled
                        contentDescription = "Habilitar extensión PhantomShield"; ThemeManager.styleSwitch(this@MainActivity, this)
                        setOnCheckedChangeListener { _, enabled ->
                            dismiss()
                            ExtensionManager.setEnabled(this@MainActivity, extension, enabled) { ok, message ->
                                runOnUiThread {
                                    if (!ok) Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                    TabManager.activeTab()?.takeIf { BrowserPrefs.isNexoHome(it.url) }?.let { startPage.render(it.isPrivate) }
                                }
                            }
                        }
                    }) else addView(TextView(this@MainActivity).apply { text = "›"; textSize = 22f; setTextColor(p.muted) })
                }
                addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
            }
            // Resto de extensiones: todas a mano en una fila, más "Añadir".
            val strip = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            others.forEach { extension -> strip.addView(extensionTile(extension, dismiss)) }
            strip.addView(extensionTile(null, dismiss))
            addView(android.widget.HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(strip)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Botón de una extensión en el menú (null = añadir extensiones). */
    private fun extensionTile(extension: org.mozilla.geckoview.WebExtension?, dismiss: () -> Unit): View {
        val p = ThemeManager.palette(this)
        val d = resources.displayMetrics.density
        fun dp(value: Float) = (value * d).toInt()
        val name = extension?.metaData?.name ?: "Añadir"
        val badge = extension?.let { ExtensionManager.badge(it) }
        val icon = ImageView(this).apply {
            setImageResource(if (extension == null) R.drawable.ic_nav_add else R.drawable.ic_extension_placeholder)
            imageTintList = android.content.res.ColorStateList.valueOf(if (extension == null) p.muted else p.accent)
            setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
            background = ThemeManager.rounded(this@MainActivity, if (extension == null) p.elevated else p.accentSoft, 14f, p.outline)
        }
        if (extension != null) ExtensionManager.loadIcon(extension, dp(44f)) { bitmap ->
            if (bitmap != null) runOnUiThread { icon.imageTintList = null; icon.setImageBitmap(bitmap) }
        }
        val square = android.widget.FrameLayout(this).apply {
            addView(icon, android.widget.FrameLayout.LayoutParams(dp(46f), dp(46f), android.view.Gravity.CENTER))
            if (badge != null) addView(TextView(this@MainActivity).apply {
                text = badge; textSize = 9f; setTextColor(android.graphics.Color.WHITE)
                typeface = resources.getFont(R.font.jetbrains_mono)
                setPadding(dp(4f), dp(1f), dp(4f), dp(1f))
                background = ThemeManager.rounded(this@MainActivity, 0xFFDC2626.toInt(), 8f)
            }, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            ))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            background = ThemeManager.ripple(this@MainActivity, null, 16f)
            isClickable = true; isFocusable = true
            contentDescription = if (extension == null) "Añadir extensiones" else "Abrir $name" + (badge?.let { " · $it" } ?: "")
            setOnClickListener {
                dismiss()
                if (extension == null) openScreen(ExtensionStoreActivity::class.java)
                else ExtensionManager.runAction(extension) { ok, message ->
                    if (!ok) runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
                }
            }
            // Mantener pulsado: administrar (permisos, privado, desinstalar).
            setOnLongClickListener { dismiss(); openScreen(ExtensionsActivity::class.java); true }
            addView(square, LinearLayout.LayoutParams(dp(54f), dp(54f)))
            addView(TextView(this@MainActivity).apply {
                text = name; textSize = 10.5f; setTextColor(p.text); gravity = android.view.Gravity.CENTER
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(72f)
                setPadding(0, dp(4f), 0, 0)
            }, LinearLayout.LayoutParams(dp(72f), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Pestaña siguiente/anterior con el gesto de la barra; el texto de la dirección se desliza. */
    private fun switchAdjacentTab(offset: Int) {
        val id = TabManager.adjacentTabId(offset)
        val shift = 40f * resources.displayMetrics.density * (if (offset > 0) 1 else -1)
        if (id == null) {
            // Sin más pestañas en esa dirección: un pequeño rebote.
            omnibox.animate().cancel()
            omnibox.animate().translationX(-shift / 3f).setDuration(90L).withEndAction {
                omnibox.animate().translationX(0f).setDuration(140L).start()
            }.start()
            return
        }
        TabManager.switchTo(id)
        omnibox.animate().cancel()
        omnibox.translationX = shift
        omnibox.alpha = 0.2f
        omnibox.animate().translationX(0f).alpha(1f).setDuration(200L).start()
    }

    /** Pulsación larga en el contador de pestañas: acciones rápidas. */
    private fun showTabQuickMenu() {
        val current = TabManager.activeTab()
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val actions = mutableListOf(
            SheetAction(R.drawable.ic_nav_add, "Nueva pestaña") { viewModel.newTab(false) },
            SheetAction(R.drawable.ic_menu_private, "Nueva pestaña privada") { viewModel.newTab(true) },
            SheetAction(R.drawable.ic_menu_burner, "Nueva identidad Burner") { BurnerMailbox.open(this) }
        )
        TabManager.lastClosed()?.let { closed ->
            actions += SheetAction(R.drawable.ic_menu_reopen, "Reabrir: " + closed.title.ifBlank { closed.url }.take(60)) {
                TabManager.reopenClosed()
            }
        }
        if (current != null) actions += SheetAction(R.drawable.ic_menu_close, "Cerrar esta pestaña", danger = true) {
            TabManager.closeTab(current.id)
        }
        sheet = NexoSheets.show(this, NexoSheets.column(this,
            NexoSheets.header(this, "Pestañas", "Desliza la barra inferior para cambiar de pestaña"),
            NexoSheets.list(this, actions, dismiss)))
    }

    private fun startPageAction(action: String) {
        when (action) {
            "downloads" -> openScreen(DownloadsActivity::class.java)
            "history" -> openScreen(HistoryActivity::class.java)
            "burner" -> BurnerMailbox.open(this)
            "settings" -> openScreen(SettingsActivity::class.java)
        }
    }

    /** Añade el sitio actual a los accesos rápidos de la página de inicio. */
    private fun pinCurrentToHome() {
        val tab = TabManager.activeTab() ?: return
        if (tab.isPrivate) {
            Toast.makeText(this, "Los sitios de pestañas privadas no se guardan en inicio", Toast.LENGTH_SHORT).show()
            return
        }
        val title = StartPageView.pin(this, tab.url)
        Toast.makeText(
            this,
            if (title != null) "«$title» fijado en la página de inicio" else "Este sitio ya está en inicio o no se puede fijar",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Captura de la página visible, guardada como PNG en Descargas. */
    private fun saveScreenshot() {
        val tab = TabManager.activeTab() ?: return
        if (BrowserPrefs.isNexoHome(tab.url) || geckoView.width <= 0 || geckoView.height <= 0) {
            Toast.makeText(this, "Abre una página para capturarla", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            geckoView.capturePixels().accept(
                { bitmap ->
                    if (bitmap == null) {
                        Toast.makeText(this, "No se pudo capturar la página", Toast.LENGTH_SHORT).show()
                    } else {
                        DownloadStore.saveBitmap(this, bitmap, "Captura ${displayUrl(tab.url)}") { _, message ->
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                { Toast.makeText(this, "No se pudo capturar la página", Toast.LENGTH_SHORT).show() }
            )
        }.onFailure { Toast.makeText(this, "No se pudo capturar la página", Toast.LENGTH_SHORT).show() }
    }

    /** La página completa como PDF (motor de impresión de Gecko), en Descargas. */
    private fun savePageAsPdf() {
        val tab = TabManager.activeTab() ?: return
        val session = tab.session ?: return
        if (BrowserPrefs.isNexoHome(tab.url)) {
            Toast.makeText(this, "Abre una página para guardarla", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Generando PDF…", Toast.LENGTH_SHORT).show()
        val name = FileNames.sanitize(tab.title.ifBlank { displayUrl(tab.url) }.take(80), "pagina") + ".pdf"
        runCatching {
            session.saveAsPdf().accept(
                { stream ->
                    if (stream == null) {
                        Toast.makeText(this, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
                    } else {
                        DownloadStore.saveStream(this, stream, name, "application/pdf", tab.url) { _, message ->
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                { error -> Toast.makeText(this, "No se pudo generar el PDF: ${error?.message ?: "error"}", Toast.LENGTH_LONG).show() }
            )
        }.onFailure { Toast.makeText(this, "Esta versión de Gecko no puede guardar PDF", Toast.LENGTH_LONG).show() }
    }

    private fun openShieldPanel() {
        val shield = ExtensionManager.actionableExtensions().firstOrNull { it.id == ExtensionManager.PHANTOMSHIELD_ID }
        if (shield == null) {
            openScreen(ExtensionsActivity::class.java)
            return
        }
        ExtensionManager.runAction(shield) { ok, message ->
            if (!ok) runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun openScreen(screen: Class<out Activity>) {
        suppressAutoPipOnce = true
        startActivity(Intent(this, screen))
    }

    /**
     * [force]: el usuario abre el selector, se captura aunque suene un video.
     * La imagen solo se guarda si al llegar sigue a la vista la misma pestaña
     * con la misma página: antes una captura tardía podía quedar asignada a la
     * pestaña equivocada o mostrar la página anterior.
     */
    private fun captureCurrentTabPreview(force: Boolean = false) {
        val tab = TabManager.activeTab() ?: return
        val session = attachedSession ?: return
        if (
            tab.isPrivate ||
            tab.session !== session ||
            BrowserPrefs.isNexoHome(tab.url) ||
            tab.isLoading ||
            isInPictureInPictureMode ||
            startPage.visibility == View.VISIBLE ||
            (!force && BrowserMediaController.isPlaying(tab.id)) ||
            geckoView.width <= 0 ||
            geckoView.height <= 0
        ) return
        val url = tab.url
        runCatching {
            geckoView.capturePixels().accept(
                { bitmap ->
                    if (bitmap == null) return@accept
                    if (attachedSession === session && tab.session === session && tab.url == url) {
                        TabPreviewStore.save(applicationContext, tab.id, bitmap)
                        lastAutoPreview[tab.id] = url to android.os.SystemClock.elapsedRealtime()
                    } else {
                        bitmap.recycle()
                    }
                },
                { _ -> Unit }
            )
        }
    }

    /**
     * Miniatura automática al terminar de cargar, con la página ya asentada:
     * siempre que cambia la página y, en la misma, como mucho una cada 30 s.
     */
    private fun scheduleIdlePreview(tab: BrowserTab) {
        geckoView.removeCallbacks(idlePreview)
        val (lastUrl, lastAt) = lastAutoPreview[tab.id] ?: ("" to 0L)
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastUrl == tab.url && now - lastAt < 30_000L) return
        geckoView.postDelayed(idlePreview, 1_200L)
    }

    private fun showTabsDialog() {
        captureCurrentTabPreview(force = true)
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
        if(tab?.networkProfile?.type?.let { it != "direct" } == true) {
            Toast.makeText(this,"Esta descarga requiere una conexión independiente. Ábrela directamente en la pestaña para conservar su ruta.",Toast.LENGTH_LONG).show();return
        }
        Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show()
        DownloadStore.download(
            this,
            url,
            isPrivate = tab?.isPrivate == true,
            referrer = tab?.url,
            parallel = tab?.burnerId == null
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
        findViewById<View>(R.id.browserDock).apply {
            background = ThemeManager.rounded(this@MainActivity, p.surface, 28f, p.outline)
            elevation = 8f * resources.displayMetrics.density
        }
        topBar.background = null
        navBar.background = null
        browserFrame.applyPalette(p.surface, p.accent, p.outline)
        ThemeManager.styleEdit(this, omnibox)
        ThemeManager.styleIconButton(this,findViewById(R.id.tabToolsButton),TabManager.activeTab()?.let { it.burnerId != null || it.networkProfile != null } == true)
        styleMiniPlayerChip()
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

        findBar.background = ThemeManager.barBackground(this, lineOnTop = false)
        ThemeManager.styleEdit(this, findInput)
        findCount.setTextColor(p.muted)
        listOf(R.id.findPrev, R.id.findNext, R.id.findClose).forEach {
            ThemeManager.styleIconButton(this, findViewById(it))
        }

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
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false)) return
        val internal = intent.component?.className == INTERNAL_OPEN_ALIAS

        val raw = (
            if (internal) intent.getStringExtra(EXTRA_OPEN_URL)
            else externalNavigationTarget(intent)
            )
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        // Consumir el intent para no reabrirlo al volver a la Activity.
        intent.removeExtra(EXTRA_OPEN_URL)
        intent.putExtra(EXTRA_CONSUMED, true)

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

    /**
     * Enlaces de otras apps: VIEW (Nexo como navegador predeterminado),
     * texto compartido y búsquedas web del sistema.
     */
    private fun externalNavigationTarget(intent: Intent): String? =
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                SHARED_URL.find(text)?.value ?: SearchResolver.resolve(this, text.take(500))
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH ->
                intent.getStringExtra(SearchManager.QUERY)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SearchResolver.resolve(this, it.take(500)) }
            else -> intent.getStringExtra(EXTRA_OPEN_URL)
        }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onActiveTabChanged(tab: BrowserTab, session: GeckoSession) {
        if (attachedSession !== session) {
            hideFindBar()
            // Los subtítulos son de la pestaña visible: no seguir transcribiendo otra.
            attachedSession?.let { LiveCaptions.stop(it) }
            if (attachedSession != null) geckoView.releaseSession()
            // Fondo del tema mientras la página pinta (evita el destello blanco).
            runCatching {
                session.compositorController.setClearColor(ThemeManager.palette(this).background)
            }
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

        findViewById<View>(R.id.browserDock).visibility = if (hideChrome) View.GONE else View.VISIBLE
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
        if (!loading) {
            browserFrame.finishRefresh()
            scheduleIdlePreview(tab)
        }
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
            when (UrlSafety.scheme(tab.url)) {
                "https" -> R.drawable.ic_lock
                "moz-extension" -> R.drawable.ic_extension_placeholder
                else -> R.drawable.ic_search
            }
        )
    }

    private fun setOmniboxIcon(icon: Int?) {
        val drawable = icon?.let { getDrawable(it)?.mutate() }
        omnibox.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
    }

    private fun displayUrl(url: String): String {
        if (UrlSafety.scheme(url) == "moz-extension") {
            val installedOptions = ExtensionManager.actionableExtensions().mapNotNull { candidate ->
                val options = candidate.metaData.optionsPageUrl ?: return@mapNotNull null
                val name = candidate.metaData.name ?: return@mapNotNull null
                options to name
            }
            return BrowserPagePresentation.extensionLabel(url, installedOptions) ?: "Página de extensión"
        }
        if (!UrlSafety.isWebUrl(url)) return "Página interna de Nexo"
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return url
        return host.removePrefix("www.").removePrefix("m.")
    }

    private fun renderTab(tab: BrowserTab) {
        // Burner: la franja muestra el correo temporal y abre su buzón.
        privateBanner.text = if (tab.burnerId != null) {
            BurnerMailbox.address(tab.burnerId)?.let { "Burner · $it  ›" } ?: "Burner · identidad efímera · buzón  ›"
        } else "Navegación privada"
        privateBanner.isClickable = tab.burnerId != null
        privateBanner.contentDescription = if (tab.burnerId != null) "Abrir el buzón de esta identidad Burner" else null
        ThemeManager.styleIconButton(this,findViewById(R.id.tabToolsButton),tab.burnerId != null || tab.networkProfile != null)
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
        else {
            TabManager.resumeActive()
            // La superficie de video se recrea al volver: el puente repara el reproductor si quedó congelado.
            val session = tab?.session
            if (session != null && wasBackgrounded) geckoView.postDelayed({ ExtensionManager.sendLifecycle(session, true) }, 250L)
        }
        wasBackgrounded = false
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
        BrowserHosts.attach(this)
        ExtensionManager.attachPromptActivity(this)
        TabManager.activeTab()?.takeIf { BrowserPrefs.isNexoHome(it.url) }?.let { startPage.render(it.isPrivate) }
        ExtensionManager.consumePendingAction { ok, message ->
            if (!ok) runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
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
        geckoView.removeCallbacks(idlePreview)

        if (!isInPictureInPictureMode) {
            val tab = TabManager.activeTab()

            ExtensionManager.sendLifecycle(tab?.session, false)
            wasBackgrounded = true
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

    /** Página (pestaña + dirección) donde se ocultó el botón Mini con una pulsación larga. */
    private var miniChipHiddenFor: String? = null

    private fun updateMiniPlayerChip() {
        val tab = TabManager.activeTab()
        val key = tab?.let { it.id + "|" + it.url }
        setMiniChipVisible(!immersive && !isInPictureInPictureMode && isMiniVideoAvailable() && key != miniChipHiddenFor)
        refreshCaptionsUi()
    }

    // ---------------------------------------------------------------
    // Subtítulos en vivo
    // ---------------------------------------------------------------

    override fun onCaptionsChanged(session: GeckoSession) {
        if (session !== attachedSession) return
        refreshCaptionsUi()
        val message = LiveCaptions.message(session)
        if (LiveCaptions.state(session) == LiveCaptions.State.ERROR && message != null && message != lastCaptionMessage) {
            lastCaptionMessage = message
            // Los subtítulos siguen activos: si el usuario enciende los del reproductor,
            // Nexo los toma solo. Mantener pulsado CC abre la configuración (clave).
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private val captionHintExpiry = Runnable { refreshCaptionsUi() }

    private fun toggleCaptions() {
        val session = attachedSession ?: return
        // Activos: la hoja rápida permite elegir idioma y modo, o apagarlos.
        if (LiveCaptions.isActive(session)) {
            CaptionsUi.showQuick(this, session, onChange = { refreshCaptionsUi() }) {
                LiveCaptions.stop(session)
                Toast.makeText(this, "Subtítulos desactivados", Toast.LENGTH_SHORT).show()
                refreshCaptionsUi()
            }
            return
        }
        // Sin clave también funciona con videos que traen subtítulos propios; si no, la página lo avisa.
        lastCaptionMessage = null
        LiveCaptions.toggle(this, session)
        val target = BrowserPrefs.captionTranslateTo(this)
        Toast.makeText(this,
            if (target.isBlank()) "Subtítulos activados · toca CC para traducir"
            else "Subtítulos activados · traducción: ${CaptionProtocol.languageLabel(target)}",
            Toast.LENGTH_SHORT).show()
        refreshCaptionsUi()
    }

    private fun refreshCaptionsUi() {
        if (!::captionsButton.isInitialized) return
        val tab = TabManager.activeTab()
        val session = attachedSession
        val active = LiveCaptions.isActive(session)
        val available = tab != null && (active || (BrowserPrefs.captionsEnabled(this) && hasMiniVideo(tab)))
        captionsButton.visibility = if (available && !immersive && !isInPictureInPictureMode) View.VISIBLE else View.GONE
        CaptionsUi.styleChip(this, captionsButton, active)
        val display = if (active) LiveCaptions.display(this, session) else null
        // Sin texto todavía: un aviso breve indica que CC está activo y qué espera.
        val hint = if (active && (display == null || display.isEmpty) && !isInPictureInPictureMode) {
            LiveCaptions.statusHint(session)
        } else null
        CaptionsUi.render(
            this, captionView, browserFrame.width, browserFrame.height,
            if (pipCrop != null || isInPictureInPictureMode) null else tab?.let { BrowserMediaController.videoRect(it.id) },
            display?.takeUnless { it.isEmpty } ?: hint?.let { CaptionDisplay("", it) },
            isInPictureInPictureMode,
            hint = hint != null
        )
        captionView.removeCallbacks(captionHintExpiry)
        if (hint != null) captionView.postDelayed(captionHintExpiry, LiveCaptions.HINT_MS + 150L)
    }

    /**
     * Píldora translúcida sobre el video (se lee igual en tema claro u
     * oscuro) con aparición suave en lugar de un botón que salta.
     */
    private fun styleMiniPlayerChip() {
        val density = resources.displayMetrics.density
        miniPlayerButton.background = ThemeManager.ripple(
            this,
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6111827.toInt())
                cornerRadius = 24f * density
                setStroke(density.toInt().coerceAtLeast(1), 0x33FFFFFF)
            },
            24f,
            0x40FFFFFF
        )
        miniPlayerButton.backgroundTintList = null
        miniPlayerButton.setTextColor(android.graphics.Color.WHITE)
        miniPlayerButton.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        miniPlayerButton.isAllCaps = false
        miniPlayerButton.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        miniPlayerButton.stateListAnimator = null
        miniPlayerButton.elevation = 8f * density
        miniPlayerButton.contentDescription = "Abrir el video en una ventana flotante. Mantén pulsado para ocultar este botón."
        miniPlayerButton.setOnLongClickListener {
            miniChipHiddenFor = TabManager.activeTab()?.let { it.id + "|" + it.url }
            setMiniChipVisible(false)
            Toast.makeText(this, "Botón Mini oculto en esta página.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun setMiniChipVisible(show: Boolean) {
        val visible = miniPlayerButton.visibility == View.VISIBLE
        // Se llama a menudo (cambios de medios): no reiniciar una animación ya en curso.
        val target = miniPlayerButton.getTag(R.id.miniPlayerButton) as? Boolean
        if (target == show && (!show || visible)) return
        miniPlayerButton.setTag(R.id.miniPlayerButton, show)
        miniPlayerButton.animate().cancel()
        if (show) {
            if (!visible) {
                miniPlayerButton.alpha = 0f
                miniPlayerButton.scaleX = 0.85f
                miniPlayerButton.scaleY = 0.85f
                miniPlayerButton.visibility = View.VISIBLE
            }
            miniPlayerButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        } else if (visible) {
            miniPlayerButton.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(140L).withEndAction {
                miniPlayerButton.visibility = View.GONE
                miniPlayerButton.alpha = 1f
                miniPlayerButton.scaleX = 1f
                miniPlayerButton.scaleY = 1f
            }.start()
        }
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

        miniPlayerButton.animate().cancel()
        miniPlayerButton.setTag(R.id.miniPlayerButton, false)
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

        if (Build.VERSION.SDK_INT >= 33) {
            // Título y sitio del video en el menú de la ventana flotante.
            TabManager.activeTab()?.takeIf { it.id == tabId }?.let { tab ->
                val site = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.")
                tab.title.takeIf { it.isNotBlank() }?.let { builder.setTitle(it.take(80)) }
                site?.takeIf { it.isNotBlank() }?.let { builder.setSubtitle(it) }
            }
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
                ExtensionManager.sendLifecycle(tab?.session, false)
                wasBackgrounded = true
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
                tab?.session?.let { session ->
                    geckoView.postDelayed({ ExtensionManager.sendLifecycle(session, true) }, 350L)
                }
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
        permissionCallbacks.remove(requestCode)?.invoke(
            grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        )
    }

    // ---------------------------------------------------------------
    // BrowserHost: lo que piden las páginas web
    // ---------------------------------------------------------------

    override fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val code = nextPermissionRequest++
        if (nextPermissionRequest > REQUEST_SITE_PERMISSIONS + 500) nextPermissionRequest = REQUEST_SITE_PERMISSIONS
        permissionCallbacks[code] = onResult
        suppressAutoPipOnce = true
        requestPermissions(permissions, code)
    }

    override fun pickFiles(mimeTypes: List<String>, multiple: Boolean, onResult: (List<Uri>) -> Unit) {
        // Un selector anterior sin respuesta se da por cancelado.
        filePickerCallback?.invoke(emptyList())
        filePickerCallback = onResult

        val types = mimeTypes.map { it.trim().lowercase() }.filter { '/' in it }.distinct()
        val picker = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        runCatching {
            suppressAutoPipOnce = true
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(picker, "Elegir archivo"), REQUEST_FILE_PICKER)
        }.onFailure {
            filePickerCallback = null
            onResult(emptyList())
            Toast.makeText(this, "No hay una app para elegir archivos", Toast.LENGTH_LONG).show()
        }
    }

    override fun launch(intent: Intent) {
        suppressAutoPipOnce = true
        startActivity(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE_PICKER) return
        val callback = filePickerCallback ?: return
        filePickerCallback = null

        val uris = mutableListOf<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
            }
            if (uris.isEmpty()) data.data?.let { uris += it }
        }
        callback(uris.distinct())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // La Activity gestiona la rotación: Gecko debe enterarse para
        // actualizar screen.orientation y las media queries.
        runCatching { GeckoRuntimeHolder.get(this).configurationChanged(newConfig) }
    }

    override fun onDestroy() {
        pendingPipEntry?.let { geckoView.removeCallbacks(it) }
        if (!isInPictureInPictureMode) ExtensionManager.setPipMode(attachedSession, false)
        runCatching { unregisterReceiver(pipControlReceiver) }
        BrowserMediaController.attach(null)
        LiveCaptions.attach(null)
        if (isFinishing && !isChangingConfigurations) LiveCaptions.stopAll()
        ExtensionManager.detachPromptActivity(this)
        BrowserHosts.detach(this)
        filePickerCallback?.invoke(emptyList())
        filePickerCallback = null
        permissionCallbacks.values.forEach { it(false) }
        permissionCallbacks.clear()
        geckoView.removeCallbacks(idlePreview)
        captionView.removeCallbacks(captionHintExpiry)
        TabManager.attach(null)
        if (attachedSession != null) {
            geckoView.releaseSession()
            attachedSession = null
        }
        // No cerrar las GeckoSession al recrear o destruir solo la Activity.
        if (isFinishing && !isChangingConfigurations && BrowserPrefs.clearOnExit(this)) {
            BrowsingData.clear(this)
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (findBar.visibility == View.VISIBLE) {
            hideFindBar()
            return
        }
        if (TabManager.exitFullScreen()) return
        if (TabManager.canGoBackOrPrevious()) TabManager.goBackOrPrevious()
        else super.onBackPressed()
    }

    companion object {
        const val EXTRA_OPEN_URL = "nexo.open_url"
        private const val EXTRA_CONSUMED = "nexo.intent_consumed"
        private const val REQUEST_MEDIA_NOTIFICATIONS = 912
        private const val REQUEST_PIP_CONTROL = 913
        private const val REQUEST_FILE_PICKER = 920
        private const val REQUEST_SITE_PERMISSIONS = 2000

        /** Acciones del menú que solo tienen sentido con una página abierta. */
        private val PAGE_ONLY_ACTIONS = setOf(
            R.drawable.ic_menu_copy, R.drawable.ic_menu_share, R.drawable.ic_menu_find,
            R.drawable.ic_menu_pin, R.drawable.ic_menu_screenshot, R.drawable.ic_menu_pdf,
            R.drawable.ic_menu_translate, R.drawable.ic_menu_desktop
        )

        /** Primer enlace web dentro de un texto compartido. */
        private val SHARED_URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

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
