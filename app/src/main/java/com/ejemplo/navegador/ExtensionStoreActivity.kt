package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
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
    private var addons: List<StoreAddon> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extension_store)

        query = findViewById(R.id.storeSearch)
        listView = findViewById(R.id.storeList)
        progress = findViewById(R.id.storeProgress)

        findViewById<Button>(R.id.storeSearchButton).setOnClickListener {
            search()
        }

        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                search()
                true
            } else false
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            showAddon(addons[position])
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
        progress.visibility = View.VISIBLE

        AmoClient.search(query.text.toString().trim()) { result ->
            progress.visibility = View.GONE

            result.onSuccess { values ->
                addons = values

                listView.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    values.map {
                        "${it.name}\n${it.summary.take(110)}" +
                            if (it.users > 0) " · ${it.users} usuarios/día" else ""
                    }
                )

                if (values.isEmpty()) {
                    Toast.makeText(
                        this,
                        "No se encontraron extensiones Android",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure {
                Toast.makeText(
                    this,
                    "Error de tienda: ${it.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showAddon(addon: StoreAddon) {
        AlertDialog.Builder(this)
            .setTitle(addon.name)
            .setMessage(
                "${addon.summary}\n\nVersión: ${addon.version}\n" +
                    "Usuarios/día: ${addon.users}\n\n" +
                    "GeckoView validará la firma y la instalación."
            )
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Ver ficha") { _, _ ->
                if (addon.detailUrl.isNotBlank()) openInBrowser(addon.detailUrl)
            }
            .setPositiveButton("Instalar") { _, _ ->
                progress.visibility = View.VISIBLE

                ExtensionManager.installUrl(this, addon.xpiUrl) { _, message ->
                    progress.visibility = View.GONE
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
            .show()
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
        ThemeManager.styleText(this, findViewById<TextView>(R.id.storeTitle))
        ThemeManager.styleEdit(this, query)
        ThemeManager.styleButton(this, findViewById(R.id.storeSearchButton), true)
    }
}
