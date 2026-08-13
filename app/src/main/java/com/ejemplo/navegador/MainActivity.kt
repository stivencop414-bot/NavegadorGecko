package com.ejemplo.navegador

import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity(), TabManager.Listener {
    private lateinit var geckoView: GeckoView
    private lateinit var omnibox: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: Button
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var clearOmniboxButton: Button
    private lateinit var privateBanner: TextView
    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var navBar: View
    private lateinit var viewModel: BrowserViewModel
    private var attachedSession: GeckoSession? = null
    private var settingsFingerprint = ""

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
    }

    private fun configureUi() {
        findViewById<TextView>(R.id.brandText).setOnClickListener { viewModel.goHome() }
        findViewById<Button>(R.id.reloadButton).setOnClickListener { TabManager.reload() }
        backButton.setOnClickListener { TabManager.goBackOrPrevious() }
        forwardButton.setOnClickListener { TabManager.goForward() }
        findViewById<Button>(R.id.homeButton).setOnClickListener { viewModel.goHome() }
        findViewById<Button>(R.id.newTabButton).setOnClickListener { viewModel.newTab(false) }
        tabButton.setOnClickListener { showTabsDialog() }
        findViewById<Button>(R.id.menuButton).setOnClickListener { showMenu(it) }

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
                "Extensiones" -> { startActivity(Intent(this, ExtensionsActivity::class.java)); true }
                "Historial" -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
                "Descargas" -> { startActivity(Intent(this, DownloadsActivity::class.java)); true }
                "Configuración" -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                "Cerrar pestaña" -> { viewModel.closeCurrentTab(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun captureCurrentTabPreview() {
        val tab = TabManager.activeTab() ?: return
        if (tab.isPrivate || geckoView.width <= 0 || geckoView.height <= 0) return
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
                    tab.title.take(50) + "\n" + tab.url.take(72)
                textSize = 13.5f
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
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "No se pudo descargar: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareCurrent() {
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
        listOf(
            R.id.tabButton, R.id.menuButton, R.id.clearOmniboxButton,
            R.id.backButton, R.id.forwardButton, R.id.reloadButton,
            R.id.homeButton, R.id.newTabButton
        ).forEach { ThemeManager.styleButton(this, findViewById(it)) }

        privateBanner.setBackgroundColor(p.accent)
        privateBanner.setTextColor(p.onAccent)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
        settingsFingerprint = fingerprint()
    }

    private fun fingerprint(): String =
        listOf(
            BrowserPrefs.theme(this), BrowserPrefs.accent(this),
            BrowserPrefs.searchEngine(this), BrowserPrefs.freeSearch(this).toString(),
            BrowserPrefs.homePage(this), BrowserPrefs.trackingProtection(this).toString(),
            BrowserPrefs.maxLiveTabs(this).toString(), BrowserPrefs.showBridgeBadge(this).toString(),
            BrowserPrefs.dnsProvider(this), BrowserPrefs.cookieMode(this),
            BrowserPrefs.httpsOnly(this).toString(), BrowserPrefs.globalPrivacyControl(this).toString()
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

    private fun renderTab(tab: BrowserTab) {
        if (!omnibox.hasFocus()) omnibox.setText(tab.url)
        backButton.isEnabled = TabManager.canGoBackOrPrevious()
        forwardButton.isEnabled = tab.canGoForward
        privateBanner.visibility = if (tab.isPrivate) View.VISIBLE else View.GONE
        title = if (tab.isPrivate) "Privado · ${tab.title}" else tab.title
    }

    override fun onStart() {
        super.onStart()
        val tab = TabManager.activeTab()
        if (tab != null && tab.session == null) TabManager.switchTo(tab.id)
        else TabManager.resumeActive()
    }

    override fun onResume() {
        super.onResume()
        ExtensionManager.attachPromptActivity(this)
        if (settingsFingerprint.isNotBlank() && settingsFingerprint != fingerprint()) {
            ThemeManager.applyWindow(this)
            applyTheme()
            GeckoRuntimeHolder.applyRuntimePrefs(this)
            TabManager.reapplySettings()
            ExtensionManager.sendBrowserState(this)
        }
    }

    override fun onStop() {
        captureCurrentTabPreview()
        super.onStop()
    }

    override fun onDestroy() {
        ExtensionManager.attachPromptActivity(null)
        TabManager.attach(null)
        if (attachedSession != null) {
            geckoView.releaseSession()
            attachedSession = null
        }
        if (isFinishing) TabManager.closeAllSessionsKeepingState()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (TabManager.canGoBackOrPrevious()) TabManager.goBackOrPrevious()
        else super.onBackPressed()
    }

    companion object {
        const val EXTRA_OPEN_URL = "nexo.open_url"
    }
}
