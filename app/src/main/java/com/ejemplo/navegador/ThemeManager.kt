package com.ejemplo.navegador

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils

data class BrowserPalette(
    val background: Int,
    val surface: Int,
    val elevated: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    /** Bordes finos y separadores. */
    val outline: Int = ColorUtils.setAlphaComponent(muted, 46),
    /** Fondo suave del acento (chips, insignias, estados activos). */
    val accentSoft: Int = ColorUtils.setAlphaComponent(accent, 36),
    val danger: Int = Color.rgb(220, 38, 38),
    val isDark: Boolean = false
)

/** Fila de lista con insignia, título, subtítulo y dato secundario. */
data class ListRow(
    val title: String,
    val subtitle: String = "",
    val meta: String = "",
    val badge: String = ""
)

/**
 * Sistema visual de Nexo: paleta, tema de Android (para que diálogos, menús,
 * cursor e interruptores usen el acento elegido) y estilos de componentes
 * (botones planos con ripple, campos tipo píldora, tarjetas redondeadas).
 */
object ThemeManager {
    private fun resolvedTheme(activity: Activity): String {
        val selected = BrowserPrefs.theme(activity)
        if (selected != BrowserPrefs.THEME_SYSTEM) return selected
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) {
            BrowserPrefs.THEME_MIDNIGHT
        } else BrowserPrefs.THEME_LIGHT
    }

    fun isDark(activity: Activity): Boolean =
        resolvedTheme(activity) != BrowserPrefs.THEME_LIGHT

    /** Identifica tema + acento: si cambia, hay que recrear la Activity. */
    fun themeKey(activity: Activity): String =
        resolvedTheme(activity) + "|" + BrowserPrefs.accent(activity)

    private fun accentColor(activity: Activity): Int =
        when (BrowserPrefs.accent(activity)) {
            BrowserPrefs.ACCENT_CYAN -> Color.rgb(6, 182, 212)
            BrowserPrefs.ACCENT_LIME -> Color.rgb(34, 197, 94)
            BrowserPrefs.ACCENT_ORANGE -> Color.rgb(249, 115, 22)
            BrowserPrefs.ACCENT_PINK -> Color.rgb(236, 72, 153)
            BrowserPrefs.ACCENT_RED -> Color.rgb(239, 68, 68)
            else -> Color.rgb(124, 58, 237)
        }

    fun palette(activity: Activity): BrowserPalette {
        val accent = accentColor(activity)
        val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.42) {
            Color.rgb(15, 17, 26)
        } else {
            Color.WHITE
        }

        return when (resolvedTheme(activity)) {
            BrowserPrefs.THEME_LIGHT -> BrowserPalette(
                background = Color.rgb(246, 247, 251),
                surface = Color.WHITE,
                elevated = Color.rgb(237, 239, 245),
                text = Color.rgb(15, 23, 42),
                muted = Color.rgb(100, 116, 139),
                accent = accent,
                onAccent = onAccent,
                outline = Color.rgb(226, 230, 238),
                accentSoft = ColorUtils.setAlphaComponent(accent, 28),
                isDark = false
            )
            BrowserPrefs.THEME_OLED -> BrowserPalette(
                background = Color.BLACK,
                surface = Color.rgb(16, 16, 20),
                elevated = Color.rgb(30, 31, 38),
                text = Color.rgb(248, 250, 252),
                muted = Color.rgb(148, 163, 184),
                accent = accent,
                onAccent = onAccent,
                outline = Color.rgb(38, 40, 48),
                accentSoft = ColorUtils.setAlphaComponent(accent, 52),
                danger = Color.rgb(248, 113, 113),
                isDark = true
            )
            else -> BrowserPalette(
                background = Color.rgb(14, 16, 23),
                surface = Color.rgb(23, 26, 36),
                elevated = Color.rgb(34, 38, 52),
                text = Color.rgb(241, 245, 249),
                muted = Color.rgb(148, 163, 184),
                accent = accent,
                onAccent = onAccent,
                outline = Color.rgb(43, 48, 64),
                accentSoft = ColorUtils.setAlphaComponent(accent, 52),
                danger = Color.rgb(248, 113, 113),
                isDark = true
            )
        }
    }

    private fun themeRes(activity: Activity): Int {
        val dark = isDark(activity)
        return when (BrowserPrefs.accent(activity)) {
            BrowserPrefs.ACCENT_CYAN -> if (dark) R.style.Theme_Nexo_Dark_Cyan else R.style.Theme_Nexo_Light_Cyan
            BrowserPrefs.ACCENT_LIME -> if (dark) R.style.Theme_Nexo_Dark_Lime else R.style.Theme_Nexo_Light_Lime
            BrowserPrefs.ACCENT_ORANGE -> if (dark) R.style.Theme_Nexo_Dark_Orange else R.style.Theme_Nexo_Light_Orange
            BrowserPrefs.ACCENT_PINK -> if (dark) R.style.Theme_Nexo_Dark_Pink else R.style.Theme_Nexo_Light_Pink
            BrowserPrefs.ACCENT_RED -> if (dark) R.style.Theme_Nexo_Dark_Red else R.style.Theme_Nexo_Light_Red
            else -> if (dark) R.style.Theme_Nexo_Dark_Violet else R.style.Theme_Nexo_Light_Violet
        }
    }

    /**
     * Llamar ANTES de super.onCreate(): fija el tema de Android (claro/oscuro
     * + acento) y los colores de las barras del sistema.
     */
    fun applyWindow(activity: Activity) {
        activity.setTheme(themeRes(activity))

        val p = palette(activity)
        val window = activity.window
        window.statusBarColor = p.surface
        window.navigationBarColor = p.surface
        window.decorView.setBackgroundColor(p.background)

        val light = !p.isDark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = if (light) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else 0
            window.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else 0
        }
    }

    // ---------------------------------------------------------------
    // Primitivas
    // ---------------------------------------------------------------

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    fun rounded(
        activity: Activity,
        color: Int,
        radiusDp: Float = 14f,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 1f
    ): GradientDrawable {
        val density = activity.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            if (strokeColor != null) {
                setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
    }

    /** Fondo con efecto de pulsación (ripple) recortado a esquinas redondeadas. */
    fun ripple(
        activity: Activity,
        content: Drawable?,
        radiusDp: Float,
        rippleColor: Int = ColorUtils.setAlphaComponent(palette(activity).text, 30)
    ): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            content,
            rounded(activity, Color.WHITE, radiusDp)
        )

    private fun tint(color: Int): ColorStateList =
        ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(color, 90), color)
        )

    // ---------------------------------------------------------------
    // Componentes
    // ---------------------------------------------------------------

    /**
     * Botón de texto. [primary] = relleno con el acento; si no, botón tonal.
     * [danger] = acción destructiva (texto en rojo).
     */
    fun styleButton(
        activity: Activity,
        button: Button,
        primary: Boolean = false,
        danger: Boolean = false
    ) {
        val p = palette(activity)
        val foreground = when {
            primary -> p.onAccent
            danger -> p.danger
            else -> p.text
        }
        val bgColor = if (primary) p.accent else p.elevated
        val rippleColor = ColorUtils.setAlphaComponent(foreground, 40)

        button.background = ripple(activity, rounded(activity, bgColor, 26f), 26f, rippleColor)
        button.setTextColor(tint(foreground))
        button.compoundDrawableTintList = tint(foreground)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
        button.isAllCaps = false
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Icono plano con ripple circular; [active] lo muestra resaltado con el acento. */
    fun styleIconButton(activity: Activity, button: ImageButton, active: Boolean = false) {
        val p = palette(activity)
        val content = if (active) rounded(activity, p.accentSoft, 24f) else null
        button.background = ripple(activity, content, 24f)
        button.imageTintList = tint(if (active) p.accent else p.text)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
    }

    /** Botón de texto que actúa como icono (sin caja), p. ej. el menú ⋮. */
    fun styleFlatButton(activity: Activity, button: Button) {
        val p = palette(activity)
        button.background = ripple(activity, null, 24f)
        button.setTextColor(tint(p.text))
        button.compoundDrawableTintList = tint(p.text)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
        button.isAllCaps = false
    }

    fun styleText(activity: Activity, text: TextView, muted: Boolean = false) {
        val p = palette(activity)
        text.setTextColor(if (muted) p.muted else p.text)
    }

    /** Título de sección (pequeño, en el color de acento). */
    fun styleSectionLabel(activity: Activity, text: TextView) {
        val p = palette(activity)
        text.setTextColor(p.accent)
        text.isAllCaps = true
        text.letterSpacing = 0.06f
    }

    /** Contenedor tipo tarjeta: superficie redondeada con borde fino. */
    fun styleCard(activity: Activity, view: View, radiusDp: Float = 20f) {
        val p = palette(activity)
        view.background = rounded(activity, p.surface, radiusDp, if (p.isDark) p.outline else null)
        view.clipToOutline = true
    }

    /** Fondo de barra (superficie) con una línea fina en el borde indicado. */
    fun barBackground(activity: Activity, lineOnTop: Boolean): Drawable {
        val p = palette(activity)
        return LayerDrawable(
            arrayOf(ColorDrawable(p.surface), ColorDrawable(p.outline))
        ).apply {
            setLayerHeight(1, dp(activity, 1f).coerceAtLeast(1))
            setLayerGravity(1, if (lineOnTop) Gravity.TOP else Gravity.BOTTOM)
        }
    }

    /** Contador de pestañas: cuadro con contorno, como en los navegadores actuales. */
    fun styleTabCounter(activity: Activity, button: Button) {
        val p = palette(activity)
        val inset = dp(activity, 11f)
        val box = InsetDrawable(
            rounded(activity, Color.TRANSPARENT, 7f, p.text, 1.8f),
            inset
        )
        button.background = ripple(activity, box, 24f)
        button.setTextColor(p.text)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
    }

    fun styleDivider(activity: Activity, view: View) {
        view.setBackgroundColor(palette(activity).outline)
    }

    /** Barra superior de pantallas secundarias: botón volver + título. */
    fun styleBackButton(activity: Activity, button: ImageButton) {
        styleIconButton(activity, button)
        button.setOnClickListener { activity.finish() }
    }

    /**
     * Evita que el primer campo de texto reciba el foco al abrir la pantalla
     * (cursor parpadeando y, en algunos equipos, el teclado abierto).
     */
    fun takeInitialFocus(root: View) {
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    fun spinnerAdapter(activity: Activity, items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, items) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                paint(super.getView(position, convertView, parent), false)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                paint(super.getDropDownView(position, convertView, parent), true)

            private fun paint(view: View, dropdown: Boolean): View {
                val p = palette(activity)
                (view as? TextView)?.apply {
                    setTextColor(p.text)
                    textSize = 15f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    val d = resources.displayMetrics.density
                    if (dropdown) {
                        setPadding((18 * d).toInt(), (14 * d).toInt(), (18 * d).toInt(), (14 * d).toInt())
                        setBackgroundColor(p.surface)
                    } else {
                        // El fondo y la flecha los pone styleSpinner.
                        setPadding((16 * d).toInt(), 0, (40 * d).toInt(), 0)
                        background = null
                        gravity = Gravity.CENTER_VERTICAL
                    }
                }
                return view
            }
        }

    /** Lista simple: la primera línea es el título; el resto, texto secundario. */
    fun listAdapter(activity: Activity, items: List<String>): BaseAdapter =
        rowsAdapter(
            activity,
            items.map { item ->
                val lines = item.split('\n')
                ListRow(
                    title = lines.firstOrNull().orEmpty(),
                    subtitle = lines.getOrNull(1).orEmpty(),
                    meta = lines.drop(2).joinToString(" · ")
                )
            }
        )

    /** Lista en tarjetas con insignia circular, título, subtítulo y dato extra. */
    fun rowsAdapter(activity: Activity, rows: List<ListRow>): BaseAdapter =
        object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val holder = (convertView?.tag as? RowHolder) ?: createRow(activity)
                holder.bind(activity, rows[position])
                return holder.root
            }
        }

    private class RowHolder(
        val root: LinearLayout,
        val badge: TextView,
        val title: TextView,
        val subtitle: TextView,
        val meta: TextView
    ) {
        fun bind(activity: Activity, row: ListRow) {
            title.text = row.title
            subtitle.text = row.subtitle
            subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
            meta.text = row.meta
            meta.visibility = if (row.meta.isBlank()) View.GONE else View.VISIBLE

            val label = row.badge.ifBlank { row.title.trim().take(1).uppercase() }.take(4)
            badge.text = label
            badge.textSize = if (label.length > 2) 10.5f else 15f
        }
    }

    private fun createRow(activity: Activity): RowHolder {
        val p = palette(activity)
        val d = activity.resources.displayMetrics.density

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            minimumHeight = (64 * d).toInt()
            background = ripple(
                activity,
                rounded(activity, p.surface, 18f, if (p.isDark) p.outline else null),
                18f
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val badge = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(p.accent)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(activity, p.accentSoft, 14f)
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply {
                marginEnd = (14 * d).toInt()
            }
        }

        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun line(size: Float, color: Int, bold: Boolean) = TextView(activity).apply {
            textSize = size
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        val title = line(15f, p.text, true)
        val subtitle = line(13f, p.muted, false).apply { setPadding(0, (2 * d).toInt(), 0, 0) }
        val meta = line(12f, p.muted, false).apply {
            setPadding(0, (2 * d).toInt(), 0, 0)
            alpha = 0.85f
        }

        texts.addView(title)
        texts.addView(subtitle)
        texts.addView(meta)
        root.addView(badge)
        root.addView(texts)

        return RowHolder(root, badge, title, subtitle, meta).also { root.tag = it }
    }

    /** Selector con forma de campo redondeado y flecha a la derecha. */
    fun styleSpinner(activity: Activity, spinner: Spinner) {
        val p = palette(activity)
        val field = rounded(activity, p.elevated, 14f)
        val chevron = ChevronDrawable(p.muted, dp(activity, 2f).toFloat())

        spinner.background = LayerDrawable(arrayOf(field, chevron)).apply {
            val size = dp(activity, 12f)
            setLayerSize(1, size, size)
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInsetEnd(1, dp(activity, 18f))
        }
        spinner.backgroundTintList = null
        spinner.setPopupBackgroundDrawable(rounded(activity, p.surface, 16f, p.outline))
    }

    fun styleSwitch(activity: Activity, toggle: Switch) {
        val p = palette(activity)
        toggle.setTextColor(p.text)
        toggle.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(p.accent, if (p.isDark) Color.rgb(203, 213, 225) else Color.rgb(148, 163, 184))
        )
        toggle.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                ColorUtils.setAlphaComponent(p.accent, 120),
                ColorUtils.setAlphaComponent(p.muted, if (p.isDark) 110 else 60)
            )
        )
    }

    /**
     * Campo de texto tipo píldora; al enfocarlo muestra un borde con el
     * acento. El cursor y la selección usan el acento.
     */
    fun styleEdit(activity: Activity, edit: EditText) {
        val p = palette(activity)
        val normal = rounded(activity, p.elevated, 24f)
        val focused = rounded(activity, if (p.isDark) p.surface else Color.WHITE, 24f, p.accent, 1.5f)

        edit.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.muted)
        edit.highlightColor = ColorUtils.setAlphaComponent(p.accent, 80)
        edit.backgroundTintList = null
        edit.gravity = Gravity.CENTER_VERTICAL
        edit.compoundDrawableTintList = ColorStateList.valueOf(p.muted)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            edit.textCursorDrawable = GradientDrawable().apply {
                setColor(p.accent)
                setSize(dp(activity, 2f), dp(activity, 20f))
            }
        }
    }
}

/** Flecha "⌄" dibujada con el color exacto de la paleta (sin depender de tints). */
private class ChevronDrawable(color: Int, strokeWidth: Float) : Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        path.reset()
        path.moveTo(b.left + w * 0.1f, b.top + h * 0.32f)
        path.lineTo(b.left + w * 0.5f, b.top + h * 0.72f)
        path.lineTo(b.left + w * 0.9f, b.top + h * 0.32f)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
