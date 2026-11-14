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

    /** Cambia con cada modificación: el índice de sugerencias se reconstruye solo entonces. */
    @Volatile var version = 0L
        private set
    @Volatile private var index: Pair<Long, SuggestionIndex>? = null

    /** Índice en memoria de las mismas entradas; se reconstruye al cambiar el historial. */
    fun index(context: Context): SuggestionIndex {
        val entries = list(context)
        val current = version
        index?.takeIf { it.first == current }?.let { return it.second }
        return SuggestionIndex(entries).also { index = current to it }
    }

    fun flush() { io.submit {}.get() }

    /** Lee el historial y prepara el índice de sugerencias fuera del hilo principal (al arrancar). */
    fun prewarm(context: Context) {
        val app = context.applicationContext
        io.execute { runCatching { index(app) } }
    }

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
        val url = UrlSafety.forHistory(rawUrl)

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
            // Entradas guardadas por versiones anteriores: sin credenciales al leerlas.
            .map { if (UrlSafety.forHistory(it.url) == it.url) it else it.copy(url = UrlSafety.forHistory(it.url)) }
            .also { cache = it; version++ }
    }

    /** Sustituye el historial completo (al traer los datos de la app anterior). */
    @Synchronized
    fun replaceAll(context: Context, entries: JSONArray) {
        val items = (0 until entries.length()).mapNotNull { i ->
            // Mismo saneamiento que al navegar: el historial importado no trae credenciales.
            entries.optJSONObject(i)?.let { HistoryEntry(UrlSafety.forHistory(it.optString("url")), it.optString("title"), it.optLong("timestamp")) }
        }.filter { UrlSafety.isWebUrl(it.url) }.distinctBy { it.url }.take(MAX)
        cache = items; version++
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, JSONArray().apply {
            items.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("timestamp", it.timestamp)) }
        }.toString()).commit()
    }

    @Synchronized
    fun clear(context: Context) {
        cache = emptyList(); version++
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
        cache = items; version++
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
