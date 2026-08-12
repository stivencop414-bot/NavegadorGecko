package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
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
                    }
                    .show()
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            open(entries[position].url)
        }

        applyTheme()
        refresh()
    }

    private fun refresh() {
        entries = HistoryStore.list(this)
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            entries.map {
                "${it.title}\n${it.url}\n${DateFormat.format("dd/MM HH:mm", it.timestamp)}"
            }
        )
    }

    private fun open(url: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_OPEN_URL, url)
            }
        )
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.listRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.listTitle))
        ThemeManager.styleButton(this, findViewById(R.id.listAction))
    }
}
