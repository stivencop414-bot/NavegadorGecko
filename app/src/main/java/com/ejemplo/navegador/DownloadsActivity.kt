package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class DownloadsActivity : Activity() {
    private lateinit var listView: ListView
    private var entries: List<DownloadEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        listView = findViewById(R.id.listView)
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
        refresh()
    }

    private fun refresh() {
        entries = DownloadStore.list(this)
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            if (entries.isEmpty()) {
                listOf("No hay descargas guardadas")
            } else {
                entries.map {
                    "${it.fileName}\n${Formatter.formatShortFileSize(this, it.size)} · ${it.mimeType}"
                }
            }
        )
    }

    private fun open(entry: DownloadEntry) {
        val file = File(entry.filePath)
        if (!file.exists()) {
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
    }
}
