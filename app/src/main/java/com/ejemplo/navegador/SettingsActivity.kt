package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
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
    private lateinit var home: EditText
    private lateinit var liveTabs: Spinner
    private lateinit var restore: Switch
    private lateinit var tracking: Switch
    private lateinit var badge: Switch

    private val themes = listOf(
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
    private val liveOptions = listOf(1, 2, 3, 4, 5, 6)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        theme = findViewById(R.id.themeSpinner)
        accent = findViewById(R.id.accentSpinner)
        search = findViewById(R.id.searchSpinner)
        home = findViewById(R.id.homePageEdit)
        liveTabs = findViewById(R.id.liveTabsSpinner)
        restore = findViewById(R.id.restoreTabsSwitch)
        tracking = findViewById(R.id.trackingSwitch)
        badge = findViewById(R.id.badgeSwitch)

        populate()
        applyTheme()

        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener {
            save()
        }

        findViewById<Button>(R.id.clearDataButton).setOnClickListener {
            confirmClear()
        }
    }

    private fun populate() {
        theme.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            themes.map { it.first }
        )
        accent.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            accents.map { it.first }
        )
        search.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            engines.map { it.first }
        )
        liveTabs.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            liveOptions.map(Int::toString)
        )

        theme.setSelection(
            themes.indexOfFirst { it.second == BrowserPrefs.theme(this) }.coerceAtLeast(0)
        )
        accent.setSelection(
            accents.indexOfFirst { it.second == BrowserPrefs.accent(this) }.coerceAtLeast(0)
        )
        search.setSelection(
            engines.indexOfFirst { it.second == BrowserPrefs.searchEngine(this) }.coerceAtLeast(0)
        )
        liveTabs.setSelection(
            liveOptions.indexOf(BrowserPrefs.maxLiveTabs(this)).coerceAtLeast(0)
        )

        home.setText(BrowserPrefs.homePage(this))
        restore.isChecked = BrowserPrefs.restoreTabs(this)
        tracking.isChecked = BrowserPrefs.trackingProtection(this)
        badge.isChecked = BrowserPrefs.showBridgeBadge(this)
    }

    private fun save() {
        BrowserPrefs.setTheme(this, themes[theme.selectedItemPosition].second)
        BrowserPrefs.setAccent(this, accents[accent.selectedItemPosition].second)
        BrowserPrefs.setSearchEngine(this, engines[search.selectedItemPosition].second)
        BrowserPrefs.setHomePage(
            this,
            home.text.toString().trim().ifBlank { BrowserPrefs.LOCAL_HOME }
        )
        BrowserPrefs.setMaxLiveTabs(
            this,
            liveOptions[liveTabs.selectedItemPosition]
        )
        BrowserPrefs.setRestoreTabs(this, restore.isChecked)
        BrowserPrefs.setTrackingProtection(this, tracking.isChecked)
        BrowserPrefs.setShowBridgeBadge(this, badge.isChecked)

        ExtensionManager.sendBrowserState(this)

        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Borrar datos")
            .setMessage(
                "Se cerrarán las sesiones y se borrarán cookies, caché, " +
                    "almacenamiento de sitios e historial."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                TabManager.closeAllSessionsKeepingState()

                GeckoRuntimeHolder.get(this)
                    .storageController
                    .clearData(StorageController.ClearFlags.ALL)
                    .accept(
                        {
                            HistoryStore.clear(this)
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "Datos de navegación borrados",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        { error ->
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "Error: ${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
            }
            .show()
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<android.view.View>(R.id.settingsRoot)
            .setBackgroundColor(p.background)

        ThemeManager.styleText(
            this,
            findViewById<TextView>(R.id.settingsTitle)
        )
        ThemeManager.styleEdit(this, home)
        ThemeManager.styleButton(
            this,
            findViewById(R.id.saveSettingsButton),
            primary = true
        )
        ThemeManager.styleButton(
            this,
            findViewById(R.id.clearDataButton)
        )
    }
}
