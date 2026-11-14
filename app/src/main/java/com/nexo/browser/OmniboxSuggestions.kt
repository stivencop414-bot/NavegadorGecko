package com.nexo.browser

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Una fila del panel: abre una dirección del historial o ejecuta una búsqueda. */
sealed class OmniboxSuggestion {
    data class History(val entry: HistoryEntry) : OmniboxSuggestion()
    data class Search(val query: String) : OmniboxSuggestion()
}

/**
 * Panel encima de la barra: hasta dos sugerencias del buscador arriba y tres
 * coincidencias del historial debajo. El historial sale al momento (índice en
 * memoria, fuera del hilo principal); el buscador llega después, desde dos
 * caracteres y tras 250 ms sin escribir. Las respuestas desordenadas se
 * descartan y, si el buscador falla, quedan los resultados locales.
 */
class OmniboxSuggestions(
    private val activity: Activity,
    private val panel: LinearLayout,
    private val onPick: (OmniboxSuggestion) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "NexoSuggest").apply { isDaemon = true } }
    private var generation = 0
    private var query = ""
    private var local: List<HistoryEntry> = emptyList()
    private var remote: List<String> = emptyList()
    private val remoteTask = Runnable { requestRemote() }

    /** Texto de la barra con foco; vacío o null oculta el panel. */
    fun update(text: String?, remoteAllowed: Boolean) {
        val current = text?.trim().orEmpty()
        val id = ++generation
        main.removeCallbacks(remoteTask)
        RemoteSuggestions.cancel()
        if (current.isEmpty()) { hide(); return }
        if (current != query) remote = emptyList()
        query = current
        val app = activity.applicationContext
        worker.execute {
            val found = HistoryStore.index(app).search(current, 3)
            main.post {
                if (id != generation) return@post
                local = found
                render()
            }
        }
        if (remoteAllowed && current.length >= 2 && !UrlSafety.isPrivateQuery(current)) {
            RemoteSuggestions.cached(BrowserPrefs.searchEngine(app), current)?.let { remote = it; render() }
                ?: main.postDelayed(remoteTask, 250L)
        } else remote = emptyList()
    }

    private fun requestRemote() {
        val id = generation
        val asked = query
        RemoteSuggestions.fetch(BrowserPrefs.searchEngine(activity.applicationContext), asked) { answered, values ->
            main.post {
                // Respuesta de una consulta anterior: se descarta.
                if (id != generation || answered != query) return@post
                remote = values
                render()
            }
        }
    }

    fun hide() {
        ++generation
        main.removeCallbacks(remoteTask)
        RemoteSuggestions.cancel()
        local = emptyList(); remote = emptyList(); query = ""
        panel.removeAllViews()
        panel.visibility = View.GONE
    }

    private fun render() {
        panel.removeAllViews()
        val rows = remote.take(2).map { OmniboxSuggestion.Search(it) } + local.take(3).map { OmniboxSuggestion.History(it) }
        if (rows.isEmpty()) { panel.visibility = View.GONE; return }
        val palette = ThemeManager.palette(activity)
        panel.background = ThemeManager.rounded(activity, palette.elevated, 18f, palette.outline)
        rows.forEach { panel.addView(row(it)) }
        panel.visibility = View.VISIBLE
    }

    private fun row(item: OmniboxSuggestion): View {
        val p = ThemeManager.palette(activity)
        val (icon, title, subtitle) = when (item) {
            is OmniboxSuggestion.Search -> Triple(R.drawable.ic_search, item.query, null)
            is OmniboxSuggestion.History -> Triple(R.drawable.ic_menu_history,
                item.entry.title.ifBlank { item.entry.url }, item.entry.url.substringAfter("://").removePrefix("www."))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = NexoUi.dp(activity, 48f)
            setPadding(NexoUi.dp(activity, 12f), NexoUi.dp(activity, 6f), NexoUi.dp(activity, 12f), NexoUi.dp(activity, 6f))
            background = ThemeManager.ripple(activity, null, 14f)
            isClickable = true
            contentDescription = if (item is OmniboxSuggestion.Search) "Buscar $title" else "Abrir $title"
            setOnClickListener { onPick(item) }
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(p.muted)
            }, LinearLayout.LayoutParams(NexoUi.dp(activity, 20f), NexoUi.dp(activity, 20f)).apply { marginEnd = NexoUi.dp(activity, 12f) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = title; textSize = 15f; setTextColor(p.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                subtitle?.let {
                    addView(TextView(activity).apply {
                        text = it; textSize = 12f; setTextColor(p.muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
