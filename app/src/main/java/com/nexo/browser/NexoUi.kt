package com.nexo.browser

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Piezas comunes de las hojas inferiores (herramientas de pestaña, grupos,
 * Burner, DNS): todas usan dp, la paleta del tema y esquinas redondeadas,
 * para que lo nuevo se vea igual que el resto de Nexo.
 */
object NexoUi {
    fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun bold(activity: Activity): Typeface =
        Typeface.create(activity.resources.getFont(R.font.inter_variable), Typeface.BOLD)

    /** Contenedor vertical con los márgenes laterales de las hojas. */
    fun column(activity: Activity, horizontalDp: Float = 20f): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, horizontalDp), 0, dp(activity, horizontalDp), dp(activity, 4f))
        }

    /** Título de sección pequeño, en mayúsculas y color secundario. */
    fun section(activity: Activity, label: String): TextView {
        val p = ThemeManager.palette(activity)
        return TextView(activity).apply {
            text = label.uppercase()
            textSize = 11.5f
            letterSpacing = 0.08f
            typeface = bold(activity)
            setTextColor(p.muted)
            setPadding(dp(activity, 4f), dp(activity, 18f), 0, dp(activity, 8f))
        }
    }

    /** Texto de ayuda breve. */
    fun note(activity: Activity, value: String): TextView {
        val p = ThemeManager.palette(activity)
        return TextView(activity).apply {
            text = value
            textSize = 12.5f
            setLineSpacing(0f, 1.15f)
            setTextColor(p.muted)
            setPadding(dp(activity, 4f), dp(activity, 6f), dp(activity, 4f), dp(activity, 4f))
        }
    }

    /** Tarjeta: superficie elevada redondeada con borde fino. */
    fun card(activity: Activity): LinearLayout {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, p.elevated, 18f, p.outline)
            setPadding(dp(activity, 14f), dp(activity, 12f), dp(activity, 14f), dp(activity, 12f))
        }
    }

    /** Píldora seleccionable. [dotColor] dibuja un punto de color (grupos). */
    fun chip(activity: Activity, label: String, selected: Boolean, dotColor: Int? = null, onClick: () -> Unit): TextView {
        val p = ThemeManager.palette(activity)
        return TextView(activity).apply {
            text = label
            textSize = 13.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(activity, 200f)
            gravity = Gravity.CENTER
            minHeight = dp(activity, 38f)
            setPadding(dp(activity, if (dotColor != null) 12f else 16f), 0, dp(activity, 16f), 0)
            typeface = if (selected) bold(activity) else Typeface.DEFAULT
            setTextColor(if (selected) (if (p.isDark) p.text else p.accent) else p.text)
            val fill = if (selected) p.accentSoft else p.elevated
            val stroke = if (selected) ColorUtils.setAlphaComponent(p.accent, 140) else p.outline
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, fill, 19f, stroke), 19f)
            if (dotColor != null) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(dotColor)
                        setSize(dp(activity, 10f), dp(activity, 10f))
                    }, null, null, null
                )
                compoundDrawablePadding = dp(activity, 8f)
            }
            isClickable = true
            isFocusable = true
            contentDescription = label + if (selected) " (seleccionado)" else ""
            setOnClickListener { onClick() }
        }
    }

    /** Fila horizontal desplazable de píldoras. */
    fun chipRow(activity: Activity, chips: List<View>, sidePaddingDp: Float = 0f): HorizontalScrollView {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, sidePaddingDp), 0, dp(activity, sidePaddingDp), 0)
        }
        chips.forEachIndexed { index, chip ->
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 38f)).apply {
                if (index > 0) marginStart = dp(activity, 8f)
            })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(row)
        }
    }

    /** Campo de texto con etiqueta encima. */
    fun field(activity: Activity, label: String, hint: String, value: String, numeric: Boolean = false): Pair<View, EditText> {
        val p = ThemeManager.palette(activity)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(p.muted)
            setPadding(dp(activity, 6f), dp(activity, 6f), 0, dp(activity, 4f))
        })
        val edit = EditText(activity).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            textSize = 15f
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ThemeManager.styleEdit(activity, this)
            setPadding(dp(activity, 16f), 0, dp(activity, 16f), 0)
        }
        box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48f)))
        return box to edit
    }

    /** Dos vistas en la misma fila con pesos. */
    fun pair(activity: Activity, first: View, firstWeight: Float, second: View, secondWeight: Float): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, firstWeight))
            addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, secondWeight).apply {
                marginStart = dp(activity, 10f)
            })
        }

    /** Fila con título, descripción e interruptor. */
    fun switchRow(activity: Activity, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val p = ThemeManager.palette(activity)
        val toggle = Switch(activity).apply {
            isChecked = checked
            contentDescription = title
            ThemeManager.styleSwitch(activity, this)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 52f)
            isClickable = true
            setOnClickListener { toggle.toggle() }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply { text = title; textSize = 14.5f; setTextColor(p.text) })
                if (!subtitle.isNullOrBlank()) addView(TextView(activity).apply {
                    text = subtitle; textSize = 12f; setTextColor(p.muted); setPadding(0, dp(activity, 2f), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        }
    }

    /** Fila de acción: icono en burbuja, título, descripción opcional y chevron. */
    fun actionRow(
        activity: Activity,
        icon: Int,
        title: String,
        subtitle: String? = null,
        danger: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout {
        val p = ThemeManager.palette(activity)
        val tint = if (danger) p.danger else if (p.isDark) p.text else p.accent
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 60f)
            setPadding(dp(activity, 10f), dp(activity, 8f), dp(activity, 12f), dp(activity, 8f))
            background = ThemeManager.ripple(activity, null, 16f)
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { onClick() }
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(activity, 9f), dp(activity, 9f), dp(activity, 9f), dp(activity, 9f))
                background = ThemeManager.rounded(activity, if (danger) ColorUtils.setAlphaComponent(p.danger, 30) else p.accentSoft, 13f)
            }, LinearLayout.LayoutParams(dp(activity, 40f), dp(activity, 40f)).apply { marginEnd = dp(activity, 14f) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = title; textSize = 14.5f; maxLines = 2
                    setTextColor(if (danger) p.danger else p.text)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                if (!subtitle.isNullOrBlank()) addView(TextView(activity).apply {
                    text = subtitle; textSize = 12f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(p.muted); setPadding(0, dp(activity, 2f), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply { text = "›"; textSize = 22f; setTextColor(p.muted) })
        }
    }

    /** Botón ancho (principal o tonal) de 50dp. */
    fun button(activity: Activity, label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 15f
            minHeight = 0
            minimumHeight = 0
            ThemeManager.styleButton(activity, this, primary = primary, danger = danger)
            setOnClickListener { onClick() }
        }

    fun buttonParams(activity: Activity, topDp: Float = 10f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50f)).apply {
            topMargin = dp(activity, topDp)
        }

    /** Pide un nombre en una hoja con campo y botón Guardar. */
    fun askText(
        activity: Activity,
        title: String,
        hint: String,
        initial: String = "",
        action: String = "Guardar",
        onSave: (String) -> Unit
    ) {
        val box = column(activity)
        lateinit var sheet: android.app.Dialog
        val (fieldView, edit) = field(activity, hint, hint, initial)
        box.addView(fieldView)
        box.addView(button(activity, action, primary = true) {
            val value = edit.text.toString()
            if (value.isBlank()) { edit.error = "Escribe un nombre."; return@button }
            sheet.dismiss(); onSave(value)
        }, buttonParams(activity, 16f))
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, title), box))
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        edit.requestFocus()
        edit.setSelection(edit.text.length)
    }
}
