package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController
import java.util.WeakHashMap
import java.util.Collections
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Spinner

/** Native Gecko translation operates on the current document and keeps its navigation state. */
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

    private val states = WeakHashMap<GeckoSession,
        TranslationsController.SessionTranslation.TranslationState>()
    private val preparing = Collections.newSetFromMap(WeakHashMap<GeckoSession, Boolean>())
    private val pending = Collections.newSetFromMap(WeakHashMap<GeckoSession, Boolean>())

    fun forget(session: GeckoSession) { states.remove(session); pending.remove(session); preparing.remove(session) }

    fun bind(session: GeckoSession) {
        session.setTranslationsSessionDelegate(object : TranslationsController.SessionTranslation.Delegate {
            override fun onTranslationStateChange(session: GeckoSession,
                state: TranslationsController.SessionTranslation.TranslationState?) {
                if (state == null) { forget(session); return }
                val previous = states.put(session, state)
                if (state.hasVisibleChange == true || state.error != null) pending.remove(session)
                if (state.error != null && state.error != previous?.error && TabManager.activeSession() === session) {
                    TabManager.notifyMessage("No se pudo traducir. Comprueba el idioma y la conexión para descargarlo.")
                }
            }
        })
    }

    fun translateActive(context: Context, callback: (Boolean, String) -> Unit) {
        val activity = context as? Activity
        val tab = TabManager.activeTab()
        val session = tab?.session
        if (activity == null || activity.isFinishing || activity.isDestroyed || tab == null || session == null || !UrlSafety.isWebUrl(tab.url)) {
            callback(false, "Abre una página web para traducir."); return
        }
        if (session.sessionTranslation == null) { callback(false, "El motor de traducción no está disponible en esta sesión."); return }
        if (pending.contains(session) || preparing.contains(session)) { callback(false, "La traducción sigue en curso."); return }
        if (states[session]?.hasVisibleChange == true) {
            session.sessionTranslation!!.restoreOriginalPage().accept(
                { callback(true, "Página original restaurada.") },
                { callback(false, "No se pudo restaurar la página original.") })
            return
        }
        val original = tab.url
        val navigationEpoch = tab.navigationEpoch
        fun current() = !activity.isFinishing && !activity.isDestroyed &&
            TabManager.activeSession() === session && tab.url == original && tab.navigationEpoch == navigationEpoch
        preparing.add(session)
        TranslationsController.RuntimeTranslation.isTranslationsEngineSupported().accept({ supported ->
            if (!current()) { preparing.remove(session); return@accept }
            if (supported != true) { preparing.remove(session); callback(false, "Este dispositivo no admite el motor de traducción de Gecko."); return@accept }
            TranslationsController.RuntimeTranslation.listSupportedLanguages().accept({ languages ->
                preparing.remove(session)
                if (!current()) return@accept
                val from = languages?.fromLanguages.orEmpty().filter { !it.code.isNullOrBlank() }
                val to = languages?.toLanguages.orEmpty().filter { !it.code.isNullOrBlank() }
                if (from.isEmpty() || to.isEmpty()) { callback(false, "No se pudieron consultar los idiomas disponibles."); return@accept }
                val box = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val d = resources.displayMetrics.density
                    setPadding((24*d).toInt(), 0, (24*d).toInt(), 0)
                }
                fun spinner(label: String, items: List<TranslationsController.Language>): Spinner {
                    box.addView(TextView(activity).apply { text = label; setPadding(0,16,0,8) })
                    return Spinner(activity).apply {
                        adapter = ThemeManager.spinnerAdapter(activity, items.map { it.localizedDisplayName ?: it.code.orEmpty() })
                        ThemeManager.styleSpinner(activity, this); box.addView(this)
                    }
                }
                val source = spinner("Idioma de la página", from)
                val target = spinner("Traducir a", to)
                val detected = states[session]?.detectedLanguages?.docLangTag
                source.setSelection(PageTranslation.sourceIndex(detected, from.map { it.code.orEmpty() }))
                target.setSelection(to.indexOfFirst { it.code == BrowserPrefs.translatorTarget(context) }.coerceAtLeast(0))
                AlertDialog.Builder(activity).setTitle("Traducir esta página")
                    .setMessage("El texto se traduce en este dispositivo, dentro de la página y sin recargarla. Gecko puede descargar los idiomas necesarios de Mozilla y guardarlos en el teléfono. El contenido de la página no se envía a Google.")
                    .setView(box).setNegativeButton("Cancelar", null)
                    .setPositiveButton("Traducir") { _, _ ->
                        if (!current()) { callback(false, "La página cambió. Solicita la traducción de nuevo."); return@setPositiveButton }
                        val fromCode = from[source.selectedItemPosition].code!!
                        val toCode = to[target.selectedItemPosition].code!!
                        if (fromCode == toCode) { callback(false, "Elige dos idiomas distintos."); return@setPositiveButton }
                        pending.add(session)
                        val options = TranslationsController.SessionTranslation.TranslationOptions.Builder().downloadModel(true).build()
                        session.sessionTranslation!!.translate(fromCode, toCode, options).accept(
                            { if (current()) callback(true, "Traduciendo en esta página… Vuelve a pulsar Traducir para ver el original.") },
                            { pending.remove(session); if (current()) callback(false, "No se pudo iniciar la traducción. Comprueba la conexión y los idiomas.") })
                    }.show()
            }, { preparing.remove(session); if (current()) callback(false, "No se pudo consultar el servicio de idiomas de Gecko.") })
        }, { preparing.remove(session); if (current()) callback(false, "No se pudo comprobar el motor de traducción.") })
    }
}
