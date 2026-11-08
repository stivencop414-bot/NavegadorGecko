package com.ejemplo.navegador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiContrastTest {
    @Test fun accentLabelsMeetNormalTextContrast() {
        listOf(0x00CFFF, 0x43DC60, 0xFF9836, 0xFF4F9A, 0xFF4E4E, 0x815BFF).forEach { rgb ->
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
}
