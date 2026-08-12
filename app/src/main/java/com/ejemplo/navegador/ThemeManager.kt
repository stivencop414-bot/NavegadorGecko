package com.ejemplo.navegador

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

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
    fun palette(activity: Activity): BrowserPalette {
        val accent = when (BrowserPrefs.accent(activity)) {
            BrowserPrefs.ACCENT_CYAN -> Color.rgb(0, 207, 255)
            BrowserPrefs.ACCENT_LIME -> Color.rgb(67, 220, 96)
            BrowserPrefs.ACCENT_ORANGE -> Color.rgb(255, 152, 54)
            BrowserPrefs.ACCENT_PINK -> Color.rgb(255, 79, 154)
            BrowserPrefs.ACCENT_RED -> Color.rgb(255, 78, 78)
            else -> Color.rgb(129, 91, 255)
        }

        return when (BrowserPrefs.theme(activity)) {
            BrowserPrefs.THEME_LIGHT -> BrowserPalette(
                Color.rgb(246, 247, 251),
                Color.WHITE,
                Color.rgb(233, 235, 243),
                Color.rgb(25, 27, 35),
                Color.rgb(95, 99, 112),
                accent,
                Color.WHITE
            )
            BrowserPrefs.THEME_OLED -> BrowserPalette(
                Color.BLACK,
                Color.rgb(8, 8, 10),
                Color.rgb(22, 22, 25),
                Color.WHITE,
                Color.rgb(170, 170, 178),
                accent,
                Color.WHITE
            )
            else -> BrowserPalette(
                Color.rgb(17, 18, 27),
                Color.rgb(25, 27, 39),
                Color.rgb(37, 39, 54),
                Color.rgb(245, 245, 250),
                Color.rgb(175, 178, 194),
                accent,
                Color.WHITE
            )
        }
    }

    fun applyWindow(activity: Activity) {
        val p = palette(activity)
        activity.window.statusBarColor = p.surface
        activity.window.navigationBarColor = p.background

        activity.window.decorView.systemUiVisibility =
            if (BrowserPrefs.theme(activity) == BrowserPrefs.THEME_LIGHT) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            } else 0
    }

    fun rounded(activity: Activity, color: Int, radiusDp: Float = 12f): GradientDrawable {
        val density = activity.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
        }
    }

    fun styleButton(activity: Activity, button: Button, primary: Boolean = false) {
        val p = palette(activity)
        button.background = rounded(activity, if (primary) p.accent else p.elevated)
        button.setTextColor(if (primary) p.onAccent else p.text)
        button.backgroundTintList = null
    }

    fun styleText(activity: Activity, text: TextView, muted: Boolean = false) {
        val p = palette(activity)
        text.setTextColor(if (muted) p.muted else p.text)
    }

    fun styleEdit(activity: Activity, edit: EditText) {
        val p = palette(activity)
        edit.background = rounded(activity, p.elevated, 14f)
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.muted)
        edit.backgroundTintList = ColorStateList.valueOf(p.elevated)
    }
}
