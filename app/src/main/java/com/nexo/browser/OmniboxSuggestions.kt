package com.nexo.browser

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A five-row panel. History is independent of the cancellable remote request. */
class OmniboxSuggestions(private val activity: Activity, private val input: EditText,
    private val panel: LinearLayout, private val select: (OmniboxResult) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val app = activity.applicationContext
    @Volatile private var generation = 0
    private var call: Call? = null
    private var pending: Runnable? = null
    private var index = OmniboxIndex(emptyList())
    private var revision = -1L
    private var local = emptyList<OmniboxResult.History>()
    private var remote = emptyList<OmniboxResult.Search>()
    private var tabId: String? = null
    private val cache = object : LinkedHashMap<String, Pair<Long, List<OmniboxResult.Search>>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<OmniboxResult.Search>>>?) = size > 64
    }

    fun update() {
        hide()
        val tab = TabManager.activeTab() ?: return
        val query = input.text.toString().trim().take(256)
        if (!input.hasFocus() || query.isBlank() || tab.isPrivate || tab.burnerId != null) return
        val token = generation
        tabId = tab.id
        worker.execute {
            if (token != generation) return@execute
            if (revision != HistoryStore.revision) {
                val entries = HistoryStore.list(app)
                // If history changes while building, rebuild on the next query.
                val current = HistoryStore.revision
                index = OmniboxIndex(entries)
                revision = if (entries === HistoryStore.list(app)) current else -1
            }
            val matches = index.find(query)
            main.post { if (valid(token)) { local = matches; render() } }
        }
        if (tab.networkProfile != null || !OmniboxIndex.canQueryRemotely(query)) return
        val engine = BrowserPrefs.searchEngine(app)
        val key = "$engine\u0000$query"
        cache[key]?.takeIf { SystemClock.elapsedRealtime() - it.first < 300_000 }?.let {
            remote = it.second; render(); return
        }
        pending = Runnable {
            if (!valid(token) || TabManager.activeTab()?.networkProfile != null) return@Runnable
            val request = Request.Builder().url(endpoint(engine, query)).header("Accept", "application/json").build()
            call = client.newCall(request).also { current ->
                current.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = Unit
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                if (!it.isSuccessful) emptyList() else parse(it.body?.boundedText(32 * 1024).orEmpty(), query)
                            }
                        }.getOrDefault(emptyList())
                        main.post {
                            if (valid(token) && TabManager.activeTab()?.networkProfile == null) {
                                cache[key] = SystemClock.elapsedRealtime() to result
                                remote = result; render()
                            }
                        }
                    }
                })
            }
        }.also { main.postDelayed(it, 250) }
    }

    private fun valid(token: Int) = token == generation && input.hasFocus() &&
        !activity.isDestroyed && !activity.isFinishing && TabManager.activeTab()?.let {
            it.id == tabId && !it.isPrivate && it.burnerId == null
        } == true

    private fun render() {
        panel.removeAllViews()
        val p = ThemeManager.palette(activity)
        panel.background = ThemeManager.rounded(activity, p.surface, 16f)
        val results = remote + local
        results.forEach { result ->
            panel.addView(TextView(activity).apply {
                val textValue = when (result) {
                    is OmniboxResult.Search -> "⌕  ${result.query}"
                    is OmniboxResult.History -> "◷  ${result.title}\n${result.url}"
                }
                text = textValue
                textSize = 14f; setTextColor(p.text); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(NexoUi.dp(activity, 14f), 0, NexoUi.dp(activity, 14f), 0)
                contentDescription = when (result) {
                    is OmniboxResult.Search -> "Buscar ${result.query}"
                    is OmniboxResult.History -> "Historial: ${result.title}, ${result.url}"
                }
                isFocusable = true
                setOnClickListener { hide(); select(result) }
            }, LinearLayout.LayoutParams(-1, NexoUi.dp(activity, 48f)))
        }
        panel.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
    }

    fun hide() {
        generation++
        pending?.let { main.removeCallbacks(it) }; pending = null
        call?.cancel(); call = null
        local = emptyList(); remote = emptyList()
        panel.visibility = View.GONE
    }
    fun destroy() { hide(); cache.clear(); worker.shutdownNow() }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        internal fun endpoint(engine: String, query: String): String {
            val q = URLEncoder.encode(query, "UTF-8")
            return when (engine) {
                BrowserPrefs.ENGINE_DDG -> "https://duckduckgo.com/ac/?type=list&q=$q"
                BrowserPrefs.ENGINE_BING -> "https://api.bing.com/osjson.aspx?query=$q"
                BrowserPrefs.ENGINE_BRAVE -> "https://search.brave.com/api/suggest?q=$q"
                BrowserPrefs.ENGINE_STARTPAGE -> "https://www.startpage.com/suggestions?format=opensearch&segment=startpage.defaultffx&q=$q"
                else -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
            }
        }
        internal fun parse(body: String, query: String): List<OmniboxResult.Search> {
            val arr = JSONArray(body).optJSONArray(1) ?: return emptyList()
            return (0 until minOf(arr.length(), 20)).mapNotNull { arr.opt(it) as? String }
                .map { it.trim() }.filter { it.length in 1..200 && !it.any(Char::isISOControl) && !it.equals(query, true) }
                .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(2).map { OmniboxResult.Search(it) }
        }
    }
}
