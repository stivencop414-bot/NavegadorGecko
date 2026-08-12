package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
        privateBanner = findViewById(R.id.privateBanner)
    }

    private fun configureUi() {
        findViewById<TextView>(R.id.brandText).setOnClickListener {
            viewModel.goHome()
        }

        findViewById<Button>(R.id.reloadButton).setOnClickListener {
            TabManager.reload()
        }

        backButton.setOnClickListener { TabManager.goBackOrPrevious() }
        forwardButton.setOnClickListener { TabManager.goForward() }

        findViewById<Button>(R.id.homeButton).setOnClickListener {
            viewModel.goHome()
        }

        findViewById<Button>(R.id.newTabButton).setOnClickListener {
            viewModel.newTab(false)
        }

        tabButton.setOnClickListener { showTabsDialog() }

        findViewById<Button>(R.id.menuButton).setOnClickListener {
            showMenu(it)
        }

        omnibox.setOnEditorActionListener { _, actionId, event ->
            val enter =
                event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN

            if (
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                enter
            ) {
                navigateOmnibox()
                true
            } else false
        }

        omnibox.setOnFocusChangeListener { _, focused ->
            if (focused) omnibox.selectAll()
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
                "Nueva pestaña" -> {
                    viewModel.newTab(false)
                    true
                }
                "Nueva pestaña privada" -> {
                    viewModel.newTab(true)
                    true
                }
                "Sitio de escritorio" -> {
                    TabManager.setDesktopMode(!(current?.desktopMode ?: false))
                    true
                }
                "Compartir página" -> {
                    shareCurrent()
                    true
                }
                "Extensiones" -> {
                    startActivity(Intent(this, ExtensionsActivity::class.java))
                    true
                }
                "Historial" -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                "Descargas" -> {
                    startActivity(Intent(this, DownloadsActivity::class.java))
                    true
                }
                "Configuración" -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                "Cerrar pestaña" -> {
                    viewModel.closeCurrentTab()
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    private fun showTabsDialog() {
        val p = ThemeManager.palette(this)
        var tabsDialog: AlertDialog? = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = resources.displayMetrics.density
            setPadding((10*d).toInt(), (6*d).toInt(), (10*d).toInt(), (6*d).toInt())
            setBackgroundColor(p.surface)
        }

        TabManager.allTabs().forEach { tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val tabText = TextView(this).apply {
                this.text =
                    (if (tab.isPrivate) "◉ " else "") +
                    tab.title.take(46) + "\n" + tab.url.take(78)
                textSize = 14f
                setTextColor(p.text)
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener {
                    TabManager.switchTo(tab.id)
                    tabsDialog?.dismiss()
                }
            }

            val close = Button(this).apply {
                text = "×"
                minWidth = 0
                ThemeManager.styleButton(this@MainActivity, this)
                setOnClickListener {
                    TabManager.closeTab(tab.id)
                }
            }

            row.addView(tabText)
            row.addView(close)
            container.addView(row)
        }

        tabsDialog = AlertDialog.Builder(this)
            .setTitle("Pestañas (${TabManager.allTabs().size})")
            .setView(container)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Privada") { _, _ -> viewModel.newTab(true) }
            .setPositiveButton("Nueva") { _, _ -> viewModel.newTab(false) }
            .create()

        tabsDialog?.show()
    }

    private fun shareCurrent() {
        val tab = TabManager.activeTab() ?: return

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
            putExtra(Intent.EXTRA_TEXT, "${tab.title}\n${tab.url}")
        }

        startActivity(Intent.createChooser(intent, "Compartir página"))
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)

        root.setBackgroundColor(p.background)
        topBar.setBackgroundColor(p.surface)
        navBar.setBackgroundColor(p.surface)
        ThemeManager.styleEdit(this, omnibox)

        listOf(
            R.id.tabButton,
            R.id.menuButton,
            R.id.backButton,
            R.id.forwardButton,
            R.id.reloadButton,
            R.id.homeButton,
            R.id.newTabButton
        ).forEach { id ->
            ThemeManager.styleButton(this, findViewById(id))
        }

        findViewById<TextView>(R.id.brandText).setTextColor(p.accent)
        privateBanner.setBackgroundColor(p.accent)
        privateBanner.setTextColor(p.onAccent)
        progress.progressTintList =
            android.content.res.ColorStateList.valueOf(p.accent)

        settingsFingerprint = fingerprint()
    }

    private fun fingerprint(): String =
        listOf(
            BrowserPrefs.theme(this),
            BrowserPrefs.accent(this),
            BrowserPrefs.searchEngine(this),
            BrowserPrefs.homePage(this),
            BrowserPrefs.trackingProtection(this).toString(),
            BrowserPrefs.maxLiveTabs(this).toString(),
            BrowserPrefs.showBridgeBadge(this).toString()
        ).joinToString("|")

    private fun handleNavigationIntent(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_OPEN_URL)
        if (!url.isNullOrBlank()) TabManager.navigate(url)
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
        if (tab != null && tab.session == null) {
            TabManager.switchTo(tab.id)
        } else {
            TabManager.resumeActive()
        }
    }

    override fun onResume() {
        super.onResume()
        ExtensionManager.attachPromptActivity(this)

        if (settingsFingerprint.isNotBlank() && settingsFingerprint != fingerprint()) {
            ThemeManager.applyWindow(this)
            applyTheme()
            TabManager.reapplySettings()
            ExtensionManager.sendBrowserState(this)
        }
    }

    override fun onStop() {
        TabManager.suspendForBackground()
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
        if (TabManager.canGoBackOrPrevious()) {
            TabManager.goBackOrPrevious()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_OPEN_URL = "nexo.open_url"
    }
}
