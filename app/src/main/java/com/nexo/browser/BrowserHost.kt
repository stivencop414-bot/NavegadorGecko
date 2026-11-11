package com.nexo.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.lang.ref.WeakReference

/**
 * Pantalla del navegador capaz de mostrar lo que piden las páginas web:
 * diálogos, selector de archivos y permisos de Android.
 */
interface BrowserHost {
    val hostActivity: Activity

    /** Abre el selector de archivos del sistema; [onResult] recibe una lista vacía si se cancela. */
    fun pickFiles(mimeTypes: List<String>, multiple: Boolean, onResult: (List<Uri>) -> Unit)

    /** Pide permisos de Android en tiempo de ejecución. */
    fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit)

    /**
     * Abre otra app sin que la salida se tome como "Inicio" (que activaría
     * el mini video). Lanza ActivityNotFoundException si nadie la atiende.
     */
    fun launch(intent: Intent)
}

object BrowserHosts {
    private var ref = WeakReference<BrowserHost>(null)

    fun attach(host: BrowserHost) {
        ref = WeakReference(host)
    }

    /** Solo desvincula si [host] sigue siendo el actual (evita carreras al recrear). */
    fun detach(host: BrowserHost) {
        if (ref.get() === host) ref = WeakReference(null)
    }

    fun current(): BrowserHost? =
        ref.get()?.takeIf { host ->
            val activity = host.hostActivity
            !activity.isFinishing && !activity.isDestroyed
        }
}
