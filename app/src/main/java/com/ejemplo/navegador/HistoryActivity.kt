package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView

class HistoryActivity : Activity() {
    private lateinit var listView: ListView
    private var entries: List<HistoryEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        listView = findViewById(R.id.listView)
        listView.emptyView = findViewById<TextView>(R.id.listEmpty).apply {
            setText(R.string.empty_history)
            ThemeManager.styleText(this@HistoryActivity, this, muted = true)
        }
        findViewById<TextView>(R.id.listTitle).text = "Historial"

        findViewById<Button>(R.id.listAction).apply {
            text = "Limpiar"
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("Limpiar historial")
                    .setMessage("Esto no elimina descargas.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Limpiar") { _, _ ->
                        HistoryStore.clear(this@HistoryActivity)
                        refresh()
                    }.show()
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { open(it.url) }
        }
        applyTheme()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val updated = HistoryStore.list(this)
        if (listView.adapter != null && updated == entries) return
        entries = updated
        findViewById<Button>(R.id.listAction).isEnabled = entries.isNotEmpty()
        listView.adapter = ThemeManager.rowsAdapter(
            this,
            entries.map {
                val host = runCatching { android.net.Uri.parse(it.url).host }
                    .getOrNull()
                    ?.removePrefix("www.")
                    .orEmpty()
                ListRow(
                    title = it.title.ifBlank { host.ifBlank { "Página web" } },
                    subtitle = host.ifBlank { it.url },
                    meta = DateFormat.format("dd/MM/yyyy · HH:mm", it.timestamp).toString(),
                    badge = siteInitial(host)
                )
            }
        )
    }

    /** Inicial del dominio principal: es.wikipedia.org → W, m.youtube.com → Y. */
    private fun siteInitial(host: String): String {
        val labels = host.split('.').filter { it.isNotBlank() }
        val main = if (labels.size >= 2) labels[labels.size - 2] else labels.firstOrNull().orEmpty()
        return main.take(1).uppercase()
    }

    private fun open(url: String) {
        startActivity(MainActivity.openUrlIntent(this, url))
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.listRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.listTitle))
        ThemeManager.styleButton(this, findViewById(R.id.listAction))
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
    }
}
