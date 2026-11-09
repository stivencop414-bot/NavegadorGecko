package com.ejemplo.navegador

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Acción de una hoja: icono, texto y si está activa (resaltada). */
data class SheetAction(
    val icon: Int,
    val label: String,
    val active: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Hojas inferiores redondeadas (estilo de los navegadores actuales) que
 * sustituyen al menú emergente y a los diálogos de lista del sistema.
 */
object NexoSheets {
    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /** Muestra [content] en una hoja anclada abajo con esquinas superiores de 28dp. */
    fun show(activity: Activity, content: View): Dialog {
        val p = ThemeManager.palette(activity)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val radius = dp(activity, 28f).toFloat()
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.surface)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            setPadding(0, dp(activity, 10f), 0, dp(activity, 16f))
        }

        // Asa superior.
        sheet.addView(
            View(activity).apply {
                background = ThemeManager.rounded(activity, p.outline, 3f)
                layoutParams = LinearLayout.LayoutParams(dp(activity, 36f), dp(activity, 4f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(activity, 10f)
                }
            }
        )

        sheet.addView(
            MaxHeightScrollView(activity, (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(content)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            navigationBarColor = p.surface
        }
        dialog.show()
        return dialog
    }

    /** Encabezado de hoja: título y subtítulo opcionales. */
    fun header(activity: Activity, title: String, subtitle: String? = null): View {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24f), dp(activity, 4f), dp(activity, 24f), dp(activity, 12f))
            addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTextColor(p.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(p.muted)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(0, dp(activity, 2f), 0, 0)
                })
            }
        }
    }

    /** Cuadrícula de acciones con iconos en burbujas redondeadas. */
    fun grid(
        activity: Activity,
        actions: List<SheetAction>,
        columns: Int = 4,
        dismiss: () -> Unit
    ): View {
        val p = ThemeManager.palette(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12f), 0, dp(activity, 12f), 0)
        }

        actions.chunked(columns).forEach { rowActions ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = columns.toFloat()
            }
            rowActions.forEach { action ->
                val tint = when {
                    action.danger -> p.danger
                    action.active -> p.onAccent
                    else -> p.text
                }
                val bubbleColor = when {
                    action.active -> p.accent
                    action.danger -> ColorUtilsCompat.withAlpha(p.danger, 30)
                    else -> p.elevated
                }

                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(activity, 8f), 0, dp(activity, 10f))
                    background = ThemeManager.ripple(activity, null, 20f)
                    isClickable = true
                    contentDescription = action.label
                    setOnClickListener {
                        dismiss()
                        action.onClick()
                    }
                }
                cell.addView(ImageView(activity).apply {
                    setImageResource(action.icon)
                    imageTintList = ColorStateList.valueOf(tint)
                    scaleType = ImageView.ScaleType.CENTER
                    background = ThemeManager.rounded(activity, bubbleColor, 18f)
                    layoutParams = LinearLayout.LayoutParams(dp(activity, 52f), dp(activity, 52f))
                })
                cell.addView(TextView(activity).apply {
                    text = action.label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(if (action.danger) p.danger else p.text)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(activity, 2f), dp(activity, 6f), dp(activity, 2f), 0)
                })
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(row)
        }
        return root
    }

    /** Lista vertical de acciones con icono a la izquierda. */
    fun list(
        activity: Activity,
        actions: List<SheetAction>,
        dismiss: () -> Unit
    ): View {
        val p = ThemeManager.palette(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8f), 0, dp(activity, 8f), 0)
        }
        actions.forEach { action ->
            val color = if (action.danger) p.danger else if (action.active) p.accent else p.text
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 52f)
                setPadding(dp(activity, 16f), 0, dp(activity, 16f), 0)
                background = ThemeManager.ripple(activity, null, 16f)
                isClickable = true
                setOnClickListener {
                    dismiss()
                    action.onClick()
                }
            }
            row.addView(ImageView(activity).apply {
                setImageResource(action.icon)
                imageTintList = ColorStateList.valueOf(if (action.danger) p.danger else p.muted)
                layoutParams = LinearLayout.LayoutParams(dp(activity, 22f), dp(activity, 22f)).apply {
                    marginEnd = dp(activity, 18f)
                }
            })
            row.addView(TextView(activity).apply {
                text = action.label
                textSize = 15f
                setTextColor(color)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
        }
        return root
    }

    fun column(activity: Activity, vararg views: View): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { addView(it) }
        }
}

/** ScrollView con altura máxima: la hoja nunca tapa toda la pantalla. */
private class MaxHeightScrollView(context: android.content.Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (size > 0) minOf(size, maxHeight) else maxHeight
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
    }
}

/** Utilidad mínima de color sin depender de androidx en este archivo. */
private object ColorUtilsCompat {
    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
