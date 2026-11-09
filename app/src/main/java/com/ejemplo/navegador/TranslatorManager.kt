package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log

/** Online page translation: no language models, DOM scanning or local inference. */
object TranslatorManager {
    const val TRANSLATOR_ID = "nexo_translator@com.ejemplo.navegador"

    private var migrationStarted = false

    /** Remove the retired ML Kit bridge from profiles upgraded from older APKs. */
    fun initialize(context: Context) {
        if (migrationStarted) return
        migrationStarted = true
        val controller = GeckoRuntimeHolder.get(context).webExtensionController
        controller.list().accept(
            { extensions ->
                extensions.orEmpty().filter { it.id == TRANSLATOR_ID }.forEach { legacy ->
                    controller.uninstall(legacy).accept(
                        { },
                        { error -> Log.w("NexoTranslator", "No se pudo retirar el traductor anterior", error) }
                    )
                }
            },
            { error ->
                migrationStarted = false
                Log.w("NexoTranslator", "No se pudo revisar el traductor anterior", error)
            }
        )
    }

    fun translateActive(context: Context, callback: (Boolean, String) -> Unit) {
        val activity = context as? Activity
        val tab = TabManager.activeTab()
        if (activity == null || activity.isFinishing || activity.isDestroyed || tab == null) {
            callback(false, "No hay una página activa para traducir.")
            return
        }
        if (PageTranslation.isTranslationPage(tab.url)) {
            callback(false, "Esta página ya usa Google Translate. Vuelve a la pestaña original para cambiar el idioma.")
            return
        }
        val original = tab.url
        val translatedUrl = PageTranslation.urlFor(original, BrowserPrefs.translatorTarget(context))
        if (translatedUrl == null) {
            callback(false, "Traduce una página web pública sin credenciales ni parámetros de sesión en su dirección.")
            return
        }
        val privateNote = if (tab.isPrivate) "\n\nEstás en navegación privada: esta acción también compartirá la dirección con Google." else ""
        AlertDialog.Builder(activity)
            .setTitle("Traducir con Google Translate")
            .setMessage("Google recibirá la dirección de esta página y podrá procesar su contenido público. La pestaña original se conserva. Las páginas con inicio de sesión pueden no funcionar. No se descargan modelos al teléfono." + privateNote)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Abrir traducción") { _, _ ->
                val current = TabManager.activeTab()
                if (current?.id != tab.id || current.url != original) {
                    callback(false, "La página cambió. Vuelve a solicitar la traducción.")
                } else {
                    TabManager.createTab(translatedUrl, isPrivate = tab.isPrivate, activate = true)
                    callback(true, "Traducción abierta en otra pestaña.")
                }
            }
            .show()
    }
}
