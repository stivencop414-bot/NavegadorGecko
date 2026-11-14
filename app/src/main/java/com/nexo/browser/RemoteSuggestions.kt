package com.nexo.browser

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Sugerencias del buscador elegido (formato OpenSearch: ["consulta", ["a", "b"]]).
 * Una sola consulta en vuelo (la anterior se cancela), sin cookies, sin
 * redirecciones, solo HTTPS, respuesta limitada a 64 KB y caché breve.
 * Quien llama decide si la consulta puede salir (privado, Burner, proxy…).
 */
object RemoteSuggestions {
    private const val MAX_BYTES = 64L * 1024
    private const val TTL_MS = 10 * 60_000L

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val cache = object : LinkedHashMap<String, Pair<Long, List<String>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<String>>>?) = size > 64
    }
    private var inFlight: Call? = null

    /** Dirección de sugerencias del buscador, o null si no tiene (Startpage). Solo HTTPS. */
    fun endpoint(engine: String, query: String): String? {
        val q = URLEncoder.encode(query, "UTF-8")
        return when (engine) {
            BrowserPrefs.ENGINE_DDG -> "https://duckduckgo.com/ac/?q=$q&type=list"
            BrowserPrefs.ENGINE_BING -> "https://api.bing.com/osjson.aspx?query=$q"
            BrowserPrefs.ENGINE_BRAVE -> "https://search.brave.com/api/suggest?q=$q"
            BrowserPrefs.ENGINE_STARTPAGE -> null
            else -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
        }
    }

    /** Respuesta OpenSearch → hasta [limit] textos distintos de la consulta, sin repetir. */
    fun parse(body: String, query: String, limit: Int = 2): List<String> {
        val list = runCatching { JSONArray(body).optJSONArray(1) }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        val folded = SuggestionIndex.fold(query.trim())
        val out = ArrayList<String>(limit)
        for (i in 0 until minOf(list.length(), 20)) {
            val text = list.optString(i).trim().take(200)
            if (text.isEmpty() || text.any { it < ' ' }) continue
            val key = SuggestionIndex.fold(text)
            if (key == folded || !seen.add(key)) continue
            out += text
            if (out.size == limit) break
        }
        return out
    }

    @Synchronized
    fun cached(engine: String, query: String): List<String>? {
        val hit = cache[engine + "\u0000" + query] ?: return null
        return if (System.currentTimeMillis() - hit.first < TTL_MS) hit.second else null
    }

    @Synchronized
    private fun store(engine: String, query: String, values: List<String>) {
        cache[engine + "\u0000" + query] = System.currentTimeMillis() to values
    }

    /** Cancela la consulta en vuelo (el usuario sigue escribiendo o cerró la barra). */
    @Synchronized
    fun cancel() { inFlight?.cancel(); inFlight = null }

    /**
     * [onResult] corre en un hilo de OkHttp con la consulta original: quien la
     * recibe descarta respuestas que lleguen desordenadas.
     */
    fun fetch(engine: String, query: String, onResult: (String, List<String>) -> Unit) {
        cached(engine, query)?.let { onResult(query, it); return }
        val url = endpoint(engine, query) ?: run { onResult(query, emptyList()); return }
        if (!url.startsWith("https://")) { onResult(query, emptyList()); return }
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        synchronized(this) { inFlight?.cancel(); inFlight = call }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onResult(query, emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { onResult(query, emptyList()); return }
                    val body = it.body?.source()?.let { source ->
                        source.request(MAX_BYTES + 1)
                        if (source.buffer.size > MAX_BYTES) null else source.buffer.readUtf8()
                    }
                    val values = body?.let { text -> parse(text, query) } ?: emptyList()
                    store(engine, query, values)
                    if (!call.isCanceled()) onResult(query, values)
                }
            }
        })
    }
}
