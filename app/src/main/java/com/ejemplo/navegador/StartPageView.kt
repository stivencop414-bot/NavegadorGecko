package com.ejemplo.navegador

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Página de inicio nativa de Nexo (nueva pestaña): logo, buscador y accesos
 * rápidos a los sitios más visitados. En pestañas privadas explica qué se
 * guarda y qué no.
 */
@SuppressLint("ViewConstructor")
class StartPageView(
    private val activity: Activity,
    private val onSearch: () -> Unit,
    private val onOpen: (String) -> Unit
) : ScrollView(activity) {

    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /** Vuelve a dibujar con el tema actual y el historial más reciente. */
    fun render(isPrivate: Boolean) {
        val p = ThemeManager.palette(activity)
        setBackgroundColor(p.background)
        content.removeAllViews()
        content.setPadding(dp(20f), dp(48f), dp(20f), dp(32f))

        content.addView(ImageView(activity).apply {
            setImageResource(if (isPrivate) R.drawable.ic_menu_private else R.drawable.ic_nexo_logo)
            if (isPrivate) {
                imageTintList = ColorStateList.valueOf(p.accent)
                background = ThemeManager.rounded(activity, p.accentSoft, 28f)
                val pad = dp(14f)
                setPadding(pad, pad, pad, pad)
            }
            layoutParams = LinearLayout.LayoutParams(dp(68f), dp(68f))
        })
        content.addView(TextView(activity).apply {
            text = if (isPrivate) "Navegación privada" else "Nexo"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, dp(22f))
        })

        // Buscador grande: abre el omnibox.
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), 0, dp(20f), 0)
            background = ThemeManager.ripple(
                activity,
                ThemeManager.rounded(activity, p.surface, 28f, p.outline),
                28f
            )
            elevation = dp(2f).toFloat()
            isClickable = true
            setOnClickListener { onSearch() }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(p.muted)
                layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(12f) }
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.omnibox_hint)
                textSize = 15.5f
                setTextColor(p.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)))

        if (isPrivate) {
            content.addView(TextView(activity).apply {
                text = "Nexo no guarda el historial, las cookies ni las miniaturas de las pestañas " +
                    "privadas. Al cerrarlas se borra todo. Tu proveedor de internet y los sitios que " +
                    "visitas pueden seguir viendo tu actividad."
                textSize = 14f
                setTextColor(p.muted)
                setLineSpacing(0f, 1.2f)
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(24f), dp(8f), 0)
            })
            return
        }

        val sites = shortcuts()
        content.addView(TextView(activity).apply {
            text = "Accesos rápidos"
            textSize = 13f
            ThemeManager.styleSectionLabel(activity, this)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4f), dp(30f), dp(4f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        sites.chunked(4).forEach { row ->
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
            }
            row.forEach { (label, url) -> line.addView(tile(label, url), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            content.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun tile(label: String, url: String): View {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8f), 0, dp(10f))
            background = ThemeManager.ripple(activity, null, 20f)
            isClickable = true
            contentDescription = label
            setOnClickListener { onOpen(url) }
            addView(TextView(activity).apply {
                text = label.take(1).uppercase()
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(p.accent)
                background = ThemeManager.rounded(activity, p.surface, 20f, p.outline)
                layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f))
            })
            addView(TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(p.text)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4f), dp(6f), dp(4f), 0)
            })
        }
    }

    /** Sitios recientes del historial (uno por dominio) completados con sugerencias. */
    private fun shortcuts(): List<Pair<String, String>> {
        val seen = mutableSetOf<String>()
        val recent = HistoryStore.list(activity).mapNotNull { entry ->
            val host = runCatching { android.net.Uri.parse(entry.url).host }.getOrNull()
                ?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
                ?: return@mapNotNull null
            if (!seen.add(host)) return@mapNotNull null
            prettyName(host) to "https://$host/"
        }.take(8)

        val defaults = listOf(
            "YouTube" to "https://m.youtube.com/",
            "Wikipedia" to "https://es.wikipedia.org/",
            "GitHub" to "https://github.com/",
            "Extensiones" to "https://addons.mozilla.org/android/"
        ).filter { (_, url) ->
            val host = android.net.Uri.parse(url).host?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("es.")
            seen.none { it.endsWith(host.orEmpty()) }
        }
        return (recent + defaults).take(8)
    }

    private fun prettyName(host: String): String {
        if (host.matches(Regex("""^[0-9.:]+$"""))) return host
        val labels = host.split('.').filter { it.isNotBlank() }
        val main = if (labels.size >= 2) labels[labels.size - 2] else labels.firstOrNull().orEmpty()
        return main.replaceFirstChar { it.uppercase() }
    }
}
