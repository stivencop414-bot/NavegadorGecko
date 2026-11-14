package com.nexo.browser

import android.content.Context
import android.content.res.Configuration

/**
 * Modo nocturno de las páginas (Dark Reader, cargado bajo demanda por el
 * puente). Activo con Midnight, OLED o el tema del sistema en oscuro; cada
 * sitio puede excluirse desde las herramientas de la dirección.
 */
object NightMode {
    /** Color de fondo para las páginas oscurecidas, o null si el modo nocturno no aplica. */
    fun effective(context: Context): String? = when (BrowserPrefs.theme(context)) {
        BrowserPrefs.THEME_OLED -> "#000000"
        BrowserPrefs.THEME_MIDNIGHT -> "#0c1222"
        BrowserPrefs.THEME_SYSTEM -> {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) "#0c1222" else null
        }
        else -> null
    }

    /** Cambia la excepción del sitio y la aplica al momento a todos sus marcos. */
    fun toggleSite(context: Context, host: String, excluded: Boolean) {
        BrowserPrefs.setNightException(context, host, excluded)
        ExtensionManager.sendBrowserState(context)
    }
}
