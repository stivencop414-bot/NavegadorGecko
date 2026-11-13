package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import org.mozilla.geckoview.GeckoSession

/**
 * Opciones cuando un video no trae subtítulos de texto. Lo primero que se
 * ofrece no envía nada fuera del teléfono: los subtítulos instantáneos de
 * Android (Live Caption) se generan en el propio dispositivo para cualquier
 * app. Transcribir con Deepgram (envía el audio del video) solo si se elige.
 */
object SystemCaptions {
    fun showNoTextChoice(activity: MainActivity, session: GeckoSession, onChange: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val hasKey = BrowserPrefs.deepgramKey(activity).isNotBlank()
        val options = arrayOf(
            "Subtítulos instantáneos de Android · en el teléfono, sin enviar nada",
            if (hasKey) "Transcribir el audio con Deepgram · se envía el audio del video" else "Configurar Deepgram para transcribir el audio",
            "Esperar a los subtítulos del reproductor"
        )
        AlertDialog.Builder(activity)
            .setTitle("Este video no trae subtítulos de texto")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { LiveCaptions.stop(session); onChange(); openSystemSettings(activity) }
                    1 -> if (hasKey) { LiveCaptions.allowAudio(activity, session); onChange() }
                        else CaptionsUi.showSheet(activity, askKey = true) { onChange() }
                    else -> Unit
                }
            }
            .show()
    }

    /** Explica dónde se activa (varía por fabricante) y abre los ajustes de subtítulos del sistema. */
    fun openSystemSettings(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Subtítulos instantáneos de Android")
            .setMessage(
                "Android puede generar subtítulos de cualquier sonido directamente en el teléfono, sin enviar nada a internet " +
                    "y con audífonos igual que con altavoz.\n\n" +
                    "La forma más rápida: pulsa una tecla de volumen y toca el icono de subtítulos. " +
                    "Si no aparece, búscalo en Ajustes › Accesibilidad como «Subtítulos instantáneos» o «Live Caption». " +
                    "No todos los teléfonos lo incluyen."
            )
            .setPositiveButton("Abrir ajustes") { _, _ ->
                val opened = runCatching { activity.startActivity(Intent(Settings.ACTION_CAPTIONING_SETTINGS)) }.isSuccess ||
                    runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.isSuccess
                if (!opened) android.widget.Toast.makeText(activity, "Abre Ajustes › Accesibilidad en tu teléfono.", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
}
