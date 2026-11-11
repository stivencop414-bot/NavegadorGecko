package com.ejemplo.navegador

import kotlin.math.pow

/** WCAG contrast for opaque RGB surfaces; independent of Android for regression tests. */
object UiContrast {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** Blanco siempre que cumpla AA (4.5:1); si no, el de mayor contraste. */
    fun foreground(background: Int): Int {
        val l = luminance(background)
        val white = 1.05 / (l + 0.05)
        val black = (l + 0.05) / 0.05
        return if (white >= 4.5 || white > black) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }
}
