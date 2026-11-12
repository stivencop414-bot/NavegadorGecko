package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider

class DownloadsActivity : Activity() {
    private lateinit var listView: ListView
    private var entries: List<DownloadEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        listView = findViewById(R.id.listView)
        listView.emptyView = findViewById<TextView>(R.id.listEmpty).apply {
            setText(R.string.empty_downloads)
            ThemeManager.styleText(this@DownloadsActivity, this, muted = true)
        }
        findViewById<TextView>(R.id.listTitle).text = "Descargas"

        findViewById<Button>(R.id.listAction).apply {
            text = "Limpiar lista"
            setOnClickListener {
                DownloadStore.clearList(this@DownloadsActivity)
                refresh()
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in entries.indices) open(entries[position])
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position !in entries.indices) return@setOnItemLongClickListener true
            val entry = entries[position]
            AlertDialog.Builder(this)
                .setTitle("Eliminar descarga")
                .setMessage(entry.fileName)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar") { _, _ ->
                    DownloadStore.remove(this, entry.id)
                    refresh()
                }
                .show()
            true
        }

        applyTheme()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val updated = DownloadStore.list(this)
        if (listView.adapter != null && updated == entries) return
        entries = updated
        findViewById<Button>(R.id.listAction).isEnabled = entries.isNotEmpty()
        listView.adapter = ThemeManager.rowsAdapter(
            this,
            entries.map {
                val ext = it.fileName.substringAfterLast('.', "").uppercase().take(4)
                val source = runCatching { android.net.Uri.parse(it.sourceUrl).host }
                    .getOrNull()
                    ?.removePrefix("www.")
                    .orEmpty()
                ListRow(
                    title = it.fileName,
                    subtitle = listOf(Formatter.formatShortFileSize(this, it.size), source)
                        .filter { part -> part.isNotBlank() }
                        .joinToString(" · "),
                    meta = android.text.format.DateFormat.format("dd/MM/yyyy · HH:mm", it.timestamp).toString(),
                    badge = ext.ifBlank { "↓" }
                )
            }
        )
    }

    private fun open(entry: DownloadEntry) {
        val file = DownloadStore.fileFor(this, entry)
        if (file == null) {
            Toast.makeText(this, "El archivo ya no existe", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.files",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    uri,
                    entry.mimeType.ifBlank { "application/octet-stream" }
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)
        }.onFailure {
            Toast.makeText(
                this,
                "No se pudo abrir este archivo con una aplicación instalada",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.listRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.listTitle))
        ThemeManager.styleButton(this, findViewById(R.id.listAction))
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
    }
}
