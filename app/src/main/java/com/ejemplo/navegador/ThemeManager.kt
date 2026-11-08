package com.ejemplo.navegador

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils

data class BrowserPalette(
    val background: Int,
    val surface: Int,
    val elevated: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int
)

object ThemeManager {
    private fun resolvedTheme(activity: Activity): String {
        val selected = BrowserPrefs.theme(activity)
        if (selected != BrowserPrefs.THEME_SYSTEM) return selected
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) {
            BrowserPrefs.THEME_MIDNIGHT
        } else BrowserPrefs.THEME_LIGHT
    }

    fun palette(activity: Activity): BrowserPalette {
        val accent = when (BrowserPrefs.accent(activity)) {
            BrowserPrefs.ACCENT_CYAN -> Color.rgb(0, 207, 255)
            BrowserPrefs.ACCENT_LIME -> Color.rgb(67, 220, 96)
            BrowserPrefs.ACCENT_ORANGE -> Color.rgb(255, 152, 54)
            BrowserPrefs.ACCENT_PINK -> Color.rgb(255, 79, 154)
            BrowserPrefs.ACCENT_RED -> Color.rgb(255, 78, 78)
            else -> Color.rgb(129, 91, 255)
        }

        return when (resolvedTheme(activity)) {
            BrowserPrefs.THEME_LIGHT -> BrowserPalette(
                Color.rgb(246, 247, 251), Color.WHITE, Color.rgb(233, 235, 243),
                Color.rgb(25, 27, 35), Color.rgb(95, 99, 112), accent, UiContrast.foreground(accent)
            )
            BrowserPrefs.THEME_OLED -> BrowserPalette(
                Color.BLACK, Color.rgb(8, 8, 10), Color.rgb(22, 22, 25),
                Color.WHITE, Color.rgb(170, 170, 178), accent, UiContrast.foreground(accent)
            )
            else -> BrowserPalette(
                Color.rgb(17, 18, 27), Color.rgb(25, 27, 39), Color.rgb(37, 39, 54),
                Color.rgb(245, 245, 250), Color.rgb(175, 178, 194), accent, UiContrast.foreground(accent)
            )
        }
    }

    fun applyWindow(activity: Activity) {
        val p = palette(activity)
        activity.window.statusBarColor = p.surface
        activity.window.navigationBarColor = p.surface
        val light = resolvedTheme(activity) == BrowserPrefs.THEME_LIGHT
        activity.window.decorView.systemUiVisibility =
            if (light) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            } else 0
    }

    fun rounded(activity: Activity, color: Int, radiusDp: Float = 14f): GradientDrawable {
        val density = activity.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
        }
    }

    private fun controlColors(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(ColorUtils.setAlphaComponent(disabled, 110), enabled)
    )

    private fun interactiveBackground(activity: Activity, color: Int, foreground: Int) =
        RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(foreground, 36)),
            rounded(activity, color, 16f),
            rounded(activity, Color.WHITE, 16f)
        )

    fun styleButton(activity: Activity, button: Button, primary: Boolean = false) {
        val p = palette(activity)
        val foreground = if (primary) p.onAccent else p.text
        button.background = interactiveBackground(activity, if (primary) p.accent else p.elevated, foreground)
        button.setTextColor(controlColors(foreground, p.muted))
        button.compoundDrawableTintList = controlColors(foreground, p.muted)
        button.backgroundTintList = null
        button.stateListAnimator = null
    }

    fun styleIconButton(activity: Activity, button: ImageButton, active: Boolean = false) {
        val p = palette(activity)
        button.background = interactiveBackground(activity, if (active) p.accent else p.surface, p.text)
        button.imageTintList = controlColors(if (active) p.onAccent else p.text, p.muted)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
    }

    fun styleText(activity: Activity, text: TextView, muted: Boolean = false) {
        val p = palette(activity)
        text.setTextColor(if (muted) p.muted else p.text)
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
                    val d = resources.displayMetrics.density
                    setPadding((14*d).toInt(), (11*d).toInt(), (14*d).toInt(), (11*d).toInt())
                    background = rounded(activity, if (dropdown) p.surface else p.elevated, 12f)
                }
                return view
            }
        }

    fun listAdapter(activity: Activity, items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val p = palette(activity)
                (v as? TextView)?.apply {
                    setTextColor(p.text)
                    val label = items[position]
                    val titleEnd = label.indexOf('\n').let { if (it < 0) label.length else it }
                    text = SpannableString(label).apply {
                        setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (titleEnd < length) {
                            setSpan(ForegroundColorSpan(p.muted), titleEnd, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(RelativeSizeSpan(0.85f), titleEnd, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    textSize = 16f
                    setLineSpacing(3 * resources.displayMetrics.density, 1f)
                    val d = resources.displayMetrics.density
                    setPadding((16*d).toInt(), (14*d).toInt(), (16*d).toInt(), (14*d).toInt())
                    background = rounded(activity, p.surface, 16f)
                }
                return v
            }
        }

    fun styleSpinner(activity: Activity, spinner: Spinner) {
        spinner.background = rounded(activity, palette(activity).elevated, 12f)
        spinner.backgroundTintList = null
    }

    fun styleSwitch(activity: Activity, toggle: Switch) {
        val p = palette(activity)
        toggle.setTextColor(p.text)
        toggle.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(p.accent, p.muted)
        )
    }

    fun styleEdit(activity: Activity, edit: EditText) {
        val p = palette(activity)
        edit.background = rounded(activity, p.elevated, 18f)
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.muted)
        edit.backgroundTintList = ColorStateList.valueOf(p.elevated)
    }
}
