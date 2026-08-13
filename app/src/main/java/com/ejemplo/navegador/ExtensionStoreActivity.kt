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
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class ExtensionStoreActivity : Activity() {
    private lateinit var query: EditText
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extension_store)

        query = findViewById(R.id.storeSearch)
        listView = findViewById(R.id.storeList)
        progress = findViewById(R.id.storeProgress)
        status = findViewById(R.id.storeStatus)

        findViewById<Button>(R.id.installedExtensionsButton).setOnClickListener {
            startActivity(Intent(this, ExtensionsActivity::class.java))
        }

        findViewById<Button>(R.id.storeSearchButton).setOnClickListener { search() }
        findViewById<Button>(R.id.recommendedButton).setOnClickListener {
            query.setText("")
            search()
        }
        findViewById<Button>(R.id.privacyButton).setOnClickListener {
            query.setText("privacy")
            search()
        }
        findViewById<Button>(R.id.blockersButton).setOnClickListener {
            query.setText("ad blocker")
            search()
        }

        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                search()
                true
            } else false
        }

        applyTheme()
        search()
    }

    override fun onResume() {
        super.onResume()
        ExtensionManager.attachPromptActivity(this)
    }

    override fun onDestroy() {
        ExtensionManager.attachPromptActivity(null)
        super.onDestroy()
    }

    private fun search() {
        setLoading(true)
        val term = query.text.toString().trim()

        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()

        AmoClient.search(term) { result ->
            setLoading(false)

            result.onSuccess { values ->
                status.text = when {
                    values.isEmpty() -> "No se encontraron extensiones compatibles"
                    term.isBlank() -> "${values.size} recomendadas para Android"
                    else -> "${values.size} resultados para “$term”"
                }

                listView.adapter = StoreAddonAdapter(
                    this,
                    values,
                    onInstall = ::install,
                    onDetails = ::showAddon
                )
                listView.divider = null
                listView.dividerHeight =
                    (resources.displayMetrics.density * 9).toInt()
            }.onFailure {
                status.text = "No se pudo cargar la tienda"
                Toast.makeText(
                    this,
                    "Error de tienda: ${it.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun install(addon: StoreAddon) {
        setLoading(true)
        status.text = "Instalando ${addon.name}…"

        ExtensionManager.installUrl(this, addon.xpiUrl) { ok, message ->
            runOnUiThread {
                setLoading(false)
                status.text = if (ok) "Extensión instalada" else "No se pudo instalar"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddon(addon: StoreAddon) {
        val rating = if (addon.rating > 0.0) "%.1f / 5".format(addon.rating)
        else "Sin puntuación"

        AlertDialog.Builder(this)
            .setTitle(addon.name)
            .setMessage(
                buildString {
                    if (addon.author.isNotBlank()) append("Por ${addon.author}\n\n")
                    append(addon.summary)
                    append("\n\nVersión: ${addon.version.ifBlank { "?" }}")
                    append("\nPuntuación: $rating")
                    if (addon.users > 0) append("\nUsuarios: %,d".format(addon.users))
                    append("\n\nGeckoView verificará compatibilidad, firma y permisos.")
                }
            )
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Ver en Mozilla") { _, _ ->
                if (addon.detailUrl.isNotBlank()) openInBrowser(addon.detailUrl)
            }
            .setPositiveButton("Instalar") { _, _ -> install(addon) }
            .show()
    }

    private fun setLoading(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun openInBrowser(url: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_OPEN_URL, url)
            }
        )
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.storeRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.storeTitle))
        ThemeManager.styleText(this, findViewById(R.id.storeSubtitle), muted = true)
        ThemeManager.styleText(this, status, muted = true)
        ThemeManager.styleEdit(this, query)
        ThemeManager.styleButton(this, findViewById(R.id.installedExtensionsButton))
        ThemeManager.styleButton(this, findViewById(R.id.storeSearchButton), true)
        ThemeManager.styleButton(this, findViewById(R.id.recommendedButton))
        ThemeManager.styleButton(this, findViewById(R.id.privacyButton))
        ThemeManager.styleButton(this, findViewById(R.id.blockersButton))
    }
}
