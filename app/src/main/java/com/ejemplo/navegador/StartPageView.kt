package com.ejemplo.navegador

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Native rendering of the supplied Nexo prototype; it never fabricates protection counters. */
@SuppressLint("ViewConstructor")
class StartPageView(
    private val activity: Activity,
    private val onOpen: (String) -> Unit,
    private val onPrivate: () -> Unit,
    private val onShield: () -> Unit,
    private val onExtensions: () -> Unit
) : ScrollView(activity) {
    private data class Link(val label: String, val url: String = "", val kind: String = "web") {
        val id get() = if (kind == "web") url else kind
    }
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
    private val prefs get() = activity.getSharedPreferences("nexo_quick_access", Context.MODE_PRIVATE)
    private val emerald = Color.rgb(47, 214, 160)
    private var renderedPrivate = false
    private var renderedShieldToken: String? = null
    private val delayedShieldRefresh = Runnable { refreshShieldIfChanged() }

    private fun shieldToken() =
        "${ExtensionManager.installedPhantom()?.metaData?.enabled}|${ExtensionManager.phantomBadge(global = true)}"

    private fun refreshShieldIfChanged() {
        if (isShown && hasWindowFocus() && renderedShieldToken != shieldToken()) render(renderedPrivate)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        removeCallbacks(delayedShieldRefresh)
        if (hasWindowFocus) {
            refreshShieldIfChanged()
            // One recheck lets a batched extension badge arrive after closing its panel.
            postDelayed(delayedShieldRefresh, 1200L)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(delayedShieldRefresh)
        super.onDetachedFromWindow()
    }
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun params(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(width, height)
    private fun text(label: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = label; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(resources.getFont(R.font.inter_variable), Typeface.BOLD)
    }
    private fun bento() = ThemeManager.palette(activity).let { p -> GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.surface, p.surface)
    ).apply { cornerRadius = dp(18f).toFloat(); setStroke(dp(1f), p.outline) } }
    init { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }

    fun render(isPrivate: Boolean) {
        renderedPrivate = isPrivate
        renderedShieldToken = shieldToken()
        val p = ThemeManager.palette(activity)
        setBackgroundColor(p.background); content.removeAllViews(); content.setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val badge = ExtensionManager.phantomBadge(global = true)
        val available = ExtensionManager.installedPhantom()?.metaData?.enabled == true && badge != "OFF" && badge != "PAUSE"
        val shieldColor = if (available) { if (p.isDark) 0xFF7EEAC6.toInt() else 0xFF0A7656.toInt() } else p.muted
        top.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44f); setPadding(dp(10f), 0, dp(10f), 0)
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.surface, 22f, p.outline), 22f)
            isClickable = true; isFocusable = true; contentDescription = "Abrir panel de PhantomShield"
            setOnClickListener { onShield() }
            addView(View(activity).apply { background = ThemeManager.rounded(activity, shieldColor, 4f) }, params(dp(6f), dp(6f)).apply { marginEnd = dp(7f) })
            addView(text("PhantomShield", 11f, p.text, true))
            addView(text(when (badge) { "OFF" -> "Pausado"; "PAUSE" -> "Pausado"; null -> "Panel"; else -> "$badge total" }, 10.5f, shieldColor, true).apply {
                typeface = resources.getFont(R.font.jetbrains_mono)
                setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
                background = ThemeManager.rounded(activity, androidx.core.graphics.ColorUtils.setAlphaComponent(emerald, 24), 12f)
            }, params().apply { width = ViewGroup.LayoutParams.WRAP_CONTENT; marginStart = dp(8f) })
        })
        top.addView(View(activity), params(0, dp(1f)).apply { weight = 1f })
        top.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_menu_private); imageTintList = ColorStateList.valueOf(p.muted)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f)); background = ThemeManager.ripple(activity, bento(), 22f)
            contentDescription = "Abrir pestaña privada"; isClickable = true; isFocusable = true
            setOnClickListener { onPrivate() }
        }, params(dp(44f), dp(44f)))
        content.addView(top, params())

        content.addView(ImageView(activity).apply {
            setImageResource(if (isPrivate) R.drawable.ic_menu_private else R.drawable.ic_nexo_emblem)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            imageTintList = if (isPrivate) ColorStateList.valueOf(Color.WHITE) else null
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(androidx.core.graphics.ColorUtils.blendARGB(p.accent, Color.WHITE, 0.24f), p.accent, androidx.core.graphics.ColorUtils.blendARGB(p.accent, Color.BLACK, 0.2f))).apply {
                cornerRadius = dp(22f).toFloat(); setStroke(dp(1f), 0x55FFFFFF)
            }
            elevation = dp(2f).toFloat()
        }, params(dp(64f), dp(64f)).apply { topMargin = dp(28f); bottomMargin = dp(16f) })
        content.addView(text(if (isPrivate) "Navegación privada" else "Nexo", 34f, p.text, true).apply { gravity = Gravity.START })
        content.addView(text(if (isPrivate) "Un espacio separado para navegar" else "Navegación rápida, privacidad bajo tu control", 12f, p.muted).apply {
            gravity = Gravity.START; setPadding(0, dp(6f), 0, dp(24f))
        }, params())
        if (isPrivate) {
            content.addView(text("Estas pestañas no se guardan en el historial ni en las miniaturas. Sus cookies se eliminan al cerrar todas las pestañas privadas. Los archivos que descargues permanecen guardados. Tu proveedor de internet y los sitios que visitas pueden seguir viendo tu actividad.", 14f, p.muted).apply {
                setLineSpacing(0f, 1.2f); setPadding(dp(4f), dp(16f), dp(4f), dp(16f))
            }, params())
        } else {
            val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(text("Accesos rápidos", 13f, p.muted, true), params(0).apply { weight = 1f })
            heading.addView(text("Editar", 12f, if (p.isDark) androidx.core.graphics.ColorUtils.blendARGB(p.accent, p.text, 0.5f) else p.accent, true).apply {
                minimumHeight = dp(44f); gravity = Gravity.CENTER; setPadding(dp(12f), 0, 0, 0)
                isClickable = true; isFocusable = true; setOnClickListener { editLinks() }
            })
            content.addView(heading, params())
            val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
            val links = allLinks().filterNot { it.id in hidden }.take(7) + Link("Añadir", kind = "add")
            links.chunked(4).forEach { group ->
                val row = LinearLayout(activity).apply { weightSum = 4f }
                group.forEach { row.addView(tile(it), params(0).apply { weight = 1f }) }
                content.addView(row, params())
            }
        }
        content.addView(View(activity), params(1, 0).apply { weight = 1f })
        content.addView(privacyCard(), params().apply { topMargin = dp(22f) })
    }

    private fun tile(link: Link): View {
        val p = ThemeManager.palette(activity)
        val host = BrowserPagePresentation.shortcut(link.url)?.identity.orEmpty()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(5f), 0, dp(12f))
            background = ThemeManager.ripple(activity, null, 18f); isClickable = true; isFocusable = true; contentDescription = link.label
            setOnClickListener { when (link.kind) { "shield" -> onShield(); "extensions" -> onExtensions(); "add" -> addLink(); else -> onOpen(link.url) } }
            val square = FrameLayout(activity).apply { background = bento() }
            val iconRes = when { link.kind == "shield" -> R.drawable.ic_quick_shield; link.kind == "extensions" -> R.drawable.ic_extension_placeholder; link.kind == "add" -> R.drawable.ic_nav_add; host.startsWith("youtube.com:") -> R.drawable.ic_quick_youtube; host.startsWith("google.com:") -> R.drawable.ic_quick_google; host.startsWith("github.com:") -> R.drawable.ic_quick_github; else -> null }
            if (iconRes != null) {
                square.addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    if (link.kind == "add" || link.kind == "extensions") imageTintList = ColorStateList.valueOf(if (link.kind == "extensions") 0xFF22D3EE.toInt() else p.muted)
                }, FrameLayout.LayoutParams(dp(25f), dp(25f), Gravity.CENTER))
            } else {
                square.addView(text(if (host.contains("wikipedia.org")) "W" else link.label.take(1).uppercase(), 22f, p.text, true).apply {
                    gravity = Gravity.CENTER
                    if (host.contains("wikipedia.org")) typeface = Typeface.create("serif", Typeface.BOLD)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            if (link.kind == "web" && iconRes == null && !renderedPrivate) {
                val origin = BrowserPagePresentation.shortcut(link.url)?.origin
                if (origin != null) SiteIconStore.load(activity, origin) { bitmap ->
                    square.removeAllViews()
                    square.addView(ImageView(activity).apply {
                        setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.CENTER))
                }
            }
            addView(square, params(dp(56f), dp(56f)))
            addView(text(link.label, 11f, if (link.kind == "shield") { if (p.isDark) 0xFF7EEAC6.toInt() else 0xFF0A7656.toInt() } else p.text).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2f), dp(6f), dp(2f), 0)
            }, params())
        }
    }

    private fun privacyCard(): View {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(76f); setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.surface, 18f, p.outline), 18f)
            isClickable = true; isFocusable = true; contentDescription = "Abrir protección PhantomShield"
            setOnClickListener { onShield() }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_quick_shield); setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                background = ThemeManager.rounded(activity, androidx.core.graphics.ColorUtils.setAlphaComponent(emerald, 32), 12f)
            }, params(dp(38f), dp(38f)).apply { marginEnd = dp(12f) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val badge = ExtensionManager.phantomBadge(global = true)
                val status = when (badge) { "OFF" -> "PAUSADA"; "PAUSE" -> "PAUSADA"; null -> "PANEL"; else -> "ACTIVA" }
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text("Protección PhantomShield", 12f, p.text, true), params(0).apply { weight = 1f })
                    addView(text(status, 8.5f, if (p.isDark) 0xFF7EEAC6.toInt() else 0xFF0A7656.toInt(), true).apply {
                        setPadding(dp(5f), dp(3f), dp(5f), dp(3f)); typeface = resources.getFont(R.font.jetbrains_mono)
                        background = ThemeManager.rounded(activity, androidx.core.graphics.ColorUtils.setAlphaComponent(emerald, 28), 5f)
                    })
                })
                addView(text(if (badge != null && badge.matches(Regex("[0-9]{1,3}\\+?"))) "$badge bloqueos en total" else "Consulta la protección de cada sitio", 10.5f, p.muted).apply { setPadding(0, dp(4f), 0, 0) })
            }, params(0).apply { weight = 1f })
            addView(text("›", 22f, p.muted))
        }
    }

    private fun allLinks(): List<Link> {
        val links = ArrayList<Link>()
        try {
            val stored = JSONArray(prefs.getString("custom", "[]"))
            for (index in 0 until stored.length()) {
                val shortcut = BrowserPagePresentation.shortcut(stored.getJSONObject(index).optString("url"))
                if (shortcut != null) links.add(Link(shortcut.title, shortcut.origin))
            }
        } catch (_: org.json.JSONException) {
            // An invalid saved list must not prevent showing the built-in shortcuts.
        }
        links.addAll(listOf(
            Link("YouTube", "https://m.youtube.com/"),
            Link("Google", "https://www.google.com/"),
            Link("Shield", kind = "shield"),
            Link("Wikipedia", "https://es.wikipedia.org/"),
            Link("GitHub", "https://github.com/"),
            Link("Extensiones", kind = "extensions")
        ))
        for (entry in HistoryStore.list(activity)) {
            val shortcut = BrowserPagePresentation.shortcut(entry.url)
            if (shortcut != null) links.add(Link(shortcut.title, shortcut.origin))
        }
        val seen = HashSet<String>()
        val unique = ArrayList<Link>()
        for (link in links) {
            val identity = if (link.kind == "web") {
                BrowserPagePresentation.shortcut(link.url)?.identity ?: continue
            } else link.id
            if (seen.add(identity)) unique.add(link)
        }
        return unique
    }

    private fun addLink() {
        val input = EditText(activity).apply {
            hint = "https://ejemplo.com"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ThemeManager.styleEdit(activity, this); setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Añadir acceso rápido").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Añadir", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = input.text.toString().trim(); val candidate = if (raw.contains("://")) raw else "https://$raw"
            val link = BrowserPagePresentation.shortcut(candidate)
            if (link == null) { input.error = "Introduce una dirección web válida"; return@setOnClickListener }
            val saved = runCatching { JSONArray(prefs.getString("custom", "[]")) }.getOrDefault(JSONArray())
            saved.put(JSONObject().put("url", link.origin))
            prefs.edit().putString("custom", saved.toString()).apply()
            dialog.dismiss(); render(false)
        } }
        dialog.show()
    }

    private fun editLinks() {
        val links = allLinks().take(20)
        val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
        AlertDialog.Builder(activity).setTitle("Mostrar accesos rápidos")
            .setMultiChoiceItems(links.map { it.label }.toTypedArray(), links.map { it.id !in hidden }.toBooleanArray()) { _, index, checked ->
                if (checked) hidden.remove(links[index].id) else hidden.add(links[index].id)
            }.setNegativeButton("Cancelar", null).setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putStringSet("hidden", hidden).apply(); render(false)
            }.show()
    }
}
