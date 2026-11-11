package com.ejemplo.navegador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiContrastTest {
    @Test fun accentLabelsMeetNormalTextContrast() {
        listOf(0x3D6BF2, 0x06B6D4, 0x22C55E, 0xF97316, 0xEC4899, 0xEF4444, 0x7C3AED).forEach { rgb ->
            val background = rgb or 0xFF000000.toInt()
            val a = UiContrast.luminance(background)
            val b = UiContrast.luminance(UiContrast.foreground(background))
            val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
            assertTrue("Insufficient contrast for ${rgb.toString(16)}: $ratio", ratio >= 4.5)
        }
    }

    @Test fun blackAndWhiteSurfacesUseOppositeForegrounds() {
        assertEquals(0xFFFFFFFF.toInt(), UiContrast.foreground(0xFF000000.toInt()))
        assertEquals(0xFF000000.toInt(), UiContrast.foreground(0xFFFFFFFF.toInt()))
    }

    @Test fun cobaltButtonsUseWhiteLabels() {
        assertEquals(0xFFFFFFFF.toInt(), UiContrast.foreground(0xFF3D6BF2.toInt()))
    }
}
