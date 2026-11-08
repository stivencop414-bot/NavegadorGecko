package com.ejemplo.navegador

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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
            BrowserPrefs.ACCENT_CYAN -> Color.rgb(6, 182, 212)
            BrowserPrefs.ACCENT_LIME -> Color.rgb(34, 197, 94)
            BrowserPrefs.ACCENT_ORANGE -> Color.rgb(249, 115, 22)
            BrowserPrefs.ACCENT_PINK -> Color.rgb(236, 72, 153)
            BrowserPrefs.ACCENT_RED -> Color.rgb(239, 68, 68)
            else -> Color.rgb(124, 58, 237)
        }

        val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.42) {
            Color.rgb(15, 17, 26)
        } else {
            Color.WHITE
        }

        return when (resolvedTheme(activity)) {
            BrowserPrefs.THEME_LIGHT -> BrowserPalette(
                background = Color.rgb(244, 246, 251),
                surface = Color.WHITE,
                elevated = Color.rgb(232, 236, 244),
                text = Color.rgb(15, 23, 42),
                muted = Color.rgb(100, 116, 139),
                accent = accent,
                onAccent = onAccent
            )
            BrowserPrefs.THEME_OLED -> BrowserPalette(
                background = Color.BLACK,
                surface = Color.rgb(14, 14, 18),
                elevated = Color.rgb(26, 27, 34),
                text = Color.rgb(248, 250, 252),
                muted = Color.rgb(148, 163, 184),
                accent = accent,
                onAccent = onAccent
            )
            else -> BrowserPalette(
                background = Color.rgb(13, 15, 23),
                surface = Color.rgb(22, 25, 37),
                elevated = Color.rgb(34, 39, 56),
                text = Color.rgb(241, 245, 249),
                muted = Color.rgb(148, 163, 184),
                accent = accent,
                onAccent = onAccent
            )
        }
    }

    fun applyWindow(activity: Activity) {
        val p = palette(activity)
        val window = activity.window
        window.statusBarColor = p.surface
        window.navigationBarColor = p.surface
        window.decorView.setBackgroundColor(p.background)

        val light = resolvedTheme(activity) == BrowserPrefs.THEME_LIGHT
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
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            } else 0
        }
    }

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

    fun styleButton(activity: Activity, button: Button, primary: Boolean = false) {
        val p = palette(activity)
        val foreground = if (primary) p.onAccent else p.text
        val bgColor = if (primary) p.accent else p.elevated
        val stroke = if (primary) null else ColorUtils.setAlphaComponent(p.muted, 45)
        button.background = rounded(activity, bgColor, 14f, stroke)
        button.setTextColor(foreground)
        button.compoundDrawableTintList = ColorStateList.valueOf(foreground)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.isAllCaps = false
    }

    fun styleIconButton(activity: Activity, button: ImageButton, active: Boolean = false) {
        val p = palette(activity)
        val stroke = if (active) null else ColorUtils.setAlphaComponent(p.muted, 35)
        button.background = rounded(activity, if (active) p.accent else p.elevated, 14f, stroke)
        button.imageTintList = ColorStateList.valueOf(if (active) p.onAccent else p.text)
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
                    textSize = 14.5f
                    val d = resources.displayMetrics.density
                    setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
                    background = rounded(
                        activity,
                        if (dropdown) p.surface else p.elevated,
                        12f,
                        ColorUtils.setAlphaComponent(p.muted, 40)
                    )
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
                    textSize = 14f
                    setLineSpacing(0f, 1.2f)
                    val d = resources.displayMetrics.density
                    setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
                    background = rounded(
                        activity,
                        p.surface,
                        16f,
                        ColorUtils.setAlphaComponent(p.muted, 38)
                    )
                }
                return v
            }
        }

    fun styleSpinner(activity: Activity, spinner: Spinner) {
        val p = palette(activity)
        spinner.background = rounded(
            activity,
            p.elevated,
            12f,
            ColorUtils.setAlphaComponent(p.muted, 45)
        )
        spinner.backgroundTintList = null
    }

    fun styleSwitch(activity: Activity, toggle: Switch) {
        val p = palette(activity)
        toggle.setTextColor(p.text)
        toggle.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(p.accent, p.muted)
        )
        toggle.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                ColorUtils.setAlphaComponent(p.accent, 110),
                ColorUtils.setAlphaComponent(p.muted, 70)
            )
        )
    }

    fun styleEdit(activity: Activity, edit: EditText) {
        val p = palette(activity)
        edit.background = rounded(
            activity,
            p.elevated,
            20f,
            ColorUtils.setAlphaComponent(p.muted, 50)
        )
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.muted)
        edit.backgroundTintList = null
        edit.gravity = Gravity.CENTER_VERTICAL
    }
}
