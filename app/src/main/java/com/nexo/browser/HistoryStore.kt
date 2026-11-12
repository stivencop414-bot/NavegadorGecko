package com.nexo.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val url: String,
    val title: String,
    val timestamp: Long
)

object HistoryStore {
    private const val FILE = "nexo_history"
    private const val KEY = "entries"
    private const val MAX = 500

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "NexoHistory").apply { isDaemon = true }
    }

    /** Copia en memoria: la página de inicio y cada carga no vuelven a leer el JSON. */
    @Volatile private var cache: List<HistoryEntry>? = null

    fun flush() { io.submit {}.get() }

    /** Se llama al terminar cada carga: no bloquear el hilo principal. */
    fun add(context: Context, rawUrl: String, title: String) {
        val app = context.applicationContext
        io.execute { addNow(app, rawUrl, title) }
    }

    @Synchronized
    private fun addNow(context: Context, rawUrl: String, title: String) {
        // Pages belonging to Nexo/extensions are tools, not browsing history.
        if (!UrlSafety.isWebUrl(rawUrl)) return

        // No guardar en disco tokens OAuth, ids de sesión, firmas, etc.
        val url = UrlSafety.redactSensitiveQuery(rawUrl)

        val items = list(context).toMutableList()
        items.removeAll { it.url == url }
        items.add(0, HistoryEntry(url, title.ifBlank { url }, System.currentTimeMillis()))
        save(context, items.take(MAX))
    }

    @Synchronized
    fun list(context: Context): List<HistoryEntry> {
        cache?.let { return it }
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"

        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        HistoryEntry(
                            o.optString("url"),
                            o.optString("title"),
                            o.optLong("timestamp")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .filter { UrlSafety.isWebUrl(it.url) }
            .also { cache = it }
    }

    @Synchronized
    fun clear(context: Context) {
        cache = emptyList()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    /** Borra una sola entrada (mantener pulsado en Historial). */
    @Synchronized
    fun remove(context: Context, url: String) {
        val items = list(context).filterNot { it.url == url }
        save(context, items)
    }

    private fun save(context: Context, items: List<HistoryEntry>) {
        cache = items
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("url", it.url)
                put("title", it.title)
                put("timestamp", it.timestamp)
            })
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
