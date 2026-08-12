package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.WebExtension

class ExtensionsActivity : Activity() {
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar
    private var extensions: List<WebExtension> = emptyList()
    private val importRequestCode = 701

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extensions)

        listView = findViewById(R.id.extensionsList)
        progress = findViewById(R.id.extensionsProgress)

        findViewById<Button>(R.id.storeButton).setOnClickListener {
            startActivity(Intent(this, ExtensionStoreActivity::class.java))
        }

        findViewById<Button>(R.id.importButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/x-xpinstall",
                        "application/zip",
                        "application/octet-stream"
                    )
                )
            }
            startActivityForResult(intent, importRequestCode)
        }

        findViewById<Button>(R.id.urlInstallButton).setOnClickListener {
            showUrlInstaller()
        }

        listView.setOnItemClickListener { _, view, position, _ ->
            showExtensionMenu(view, extensions[position])
        }

        applyTheme()
        load()
    }

    override fun onResume() {
        super.onResume()
        ExtensionManager.attachPromptActivity(this)
        load()
    }

    override fun onDestroy() {
        ExtensionManager.attachPromptActivity(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == importRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            setLoading(true)

            ExtensionManager.importXpi(this, uri) { ok, message ->
                setLoading(false)
                toast(message)
                if (ok) load()
            }
        }
    }

    private fun load() {
        setLoading(true)

        ExtensionManager.list(
            onSuccess = { items ->
                extensions = items.sortedBy {
                    it.metaData?.name ?: it.id
                }

                runOnUiThread {
                    listView.adapter = ThemeManager.listAdapter(this, extensions.map { extension ->
                            val meta = extension.metaData
                            val system =
                                if (extension.id == ExtensionManager.BRIDGE_ID) " · Sistema"
                                else ""
                            val state =
                                if (meta?.enabled == false) "Deshabilitada"
                                else "Habilitada"

                            "${meta?.name ?: extension.id}$system\n" +
                                "v${meta?.version ?: "?"} · $state"
                        }
                    )
                    setLoading(false)
                }
            },
            onError = {
                runOnUiThread {
                    setLoading(false)
                    toast("No se pudieron listar: ${it.message}")
                }
            }
        )
    }

    private fun showExtensionMenu(anchor: View, extension: WebExtension) {
        val meta = extension.metaData
        val popup = PopupMenu(this, anchor)

        if (extension.id == ExtensionManager.BRIDGE_ID) {
            popup.menu.add("Puente interno de Nexo")
        } else {
            popup.menu.add(
                if (meta?.enabled == false) "Habilitar" else "Deshabilitar"
            )
            popup.menu.add(
                if (meta?.allowedInPrivateBrowsing == true) {
                    "Bloquear en privado"
                } else {
                    "Permitir en privado"
                }
            )

            if (!meta?.optionsPageUrl.isNullOrBlank()) {
                popup.menu.add("Opciones")
            }

            popup.menu.add("Desinstalar")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Habilitar" -> {
                    ExtensionManager.setEnabled(this, extension, true) { ok, msg ->
                        toast(msg)
                        if (ok) load()
                    }
                    true
                }
                "Deshabilitar" -> {
                    ExtensionManager.setEnabled(this, extension, false) { ok, msg ->
                        toast(msg)
                        if (ok) load()
                    }
                    true
                }
                "Permitir en privado" -> {
                    ExtensionManager.setPrivateAllowed(this, extension, true) { ok, msg ->
                        toast(msg)
                        if (ok) load()
                    }
                    true
                }
                "Bloquear en privado" -> {
                    ExtensionManager.setPrivateAllowed(this, extension, false) { ok, msg ->
                        toast(msg)
                        if (ok) load()
                    }
                    true
                }
                "Opciones" -> {
                    meta?.optionsPageUrl?.let { openInBrowser(it) }
                    true
                }
                "Desinstalar" -> {
                    AlertDialog.Builder(this)
                        .setTitle("Desinstalar")
                        .setMessage(meta?.name ?: extension.id)
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Desinstalar") { _, _ ->
                            ExtensionManager.uninstall(this, extension) { ok, msg ->
                                toast(msg)
                                if (ok) load()
                            }
                        }
                        .show()
                    true
                }
                else -> true
            }
        }

        popup.show()
    }

    private fun showUrlInstaller() {
        val input = EditText(this).apply {
            hint = "https://.../extension.xpi"
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Instalar desde URL")
            .setMessage("Debe ser un XPI compatible y firmado por Mozilla.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Instalar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    setLoading(true)
                    ExtensionManager.installUrl(this, url) { ok, message ->
                        setLoading(false)
                        toast(message)
                        if (ok) load()
                    }
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

    private fun setLoading(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.extensionsRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById<TextView>(R.id.extensionsTitle))
        ThemeManager.styleText(
            this,
            findViewById<TextView>(R.id.extensionsInfo),
            muted = true
        )
        ThemeManager.styleButton(this, findViewById(R.id.storeButton), true)
        ThemeManager.styleButton(this, findViewById(R.id.importButton))
        ThemeManager.styleButton(this, findViewById(R.id.urlInstallButton))
    }
}
