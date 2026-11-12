package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * Traer los datos de la app anterior (com.ejemplo.navegador) a Nexo nuevo
 * (com.nexo.browser) sin exportar archivos: ambas están firmadas con la misma
 * clave y la anterior (actualizada a la versión de transición) los entrega por
 * un proveedor protegido por firma.
 *
 * Incluye historial, accesos rápidos, pestañas normales, ajustes y claves de
 * servicios (que se vuelven a cifrar con el Keystore de esta app). No incluye
 * pestañas privadas, Burner, cookies, descargas ni datos de extensiones: para
 * esos está la copia cifrada de Ajustes › Transferir perfil.
 */
object LegacyImport {
    const val SOURCE_PACKAGE = "com.ejemplo.navegador"
    const val TARGET_PACKAGE = "com.nexo.browser"
    const val PERMISSION = "com.nexo.browser.permission.TRANSFER_PROFILE"
    private const val AUTHORITY = "$SOURCE_PACKAGE.profile"
    const val RELEASES_URL = "https://github.com/stivencop414-bot/NavegadorGecko/releases/latest"

    private const val PREFS = "nexo_legacy_import"
    private const val KEY_STATE = "state" // "", "done", "dismissed"
    private const val KEY_SNOOZED_AT = "snoozed_at"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var askedThisProcess = false

    enum class Source { MISSING, NEEDS_UPDATE, READY }

    data class Summary(val history: Int, val tabs: Int, val links: Int, val settings: Int, val secrets: Int)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun source(context: Context): Source {
        val pm = context.packageManager
        val installed = runCatching { pm.getPackageInfo(SOURCE_PACKAGE, 0) }.isSuccess
        if (!installed) return Source.MISSING
        // El proveedor solo existe en la actualización de transición (0.26.1 o posterior).
        return if (pm.resolveContentProvider(AUTHORITY, 0) != null) Source.READY else Source.NEEDS_UPDATE
    }

    /** Al abrir Nexo nuevo: ofrecerlo una vez (y otra si se pospuso hace más de un día). */
    fun offerIfNeeded(activity: Activity, onImported: () -> Unit) {
        if (activity.packageName != TARGET_PACKAGE || askedThisProcess) return
        val state = prefs(activity).getString(KEY_STATE, "").orEmpty()
        if (state == "done" || state == "dismissed") return
        if (System.currentTimeMillis() - prefs(activity).getLong(KEY_SNOOZED_AT, 0L) < 24L * 3600 * 1000) return
        val source = source(activity)
        if (source == Source.MISSING) return
        askedThisProcess = true
        show(activity, source, onImported)
    }

    /** Desde Ajustes: siempre se puede repetir. */
    fun showManual(activity: Activity, onImported: () -> Unit) {
        when (val source = source(activity)) {
            Source.MISSING -> AlertDialog.Builder(activity)
                .setTitle("Nexo anterior no está instalado")
                .setMessage("No se encontró la app anterior (com.ejemplo.navegador) en este teléfono. Si la desinstalaste, sus datos ya no se pueden recuperar automáticamente; usa una copia cifrada si la exportaste.")
                .setPositiveButton("Entendido", null).show()
            else -> show(activity, source, onImported)
        }
    }

    private fun show(activity: Activity, source: Source, onImported: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (source == Source.NEEDS_UPDATE) {
            AlertDialog.Builder(activity)
                .setTitle("Trae tus datos de Nexo anterior")
                .setMessage("Tienes instalada la versión anterior de Nexo. Para pasar el historial, accesos, pestañas, ajustes y claves a esta app, primero actualiza la anterior con el archivo «Nexo-anterior» de la página de versiones (se instala encima, sin perder nada). Después vuelve aquí.")
                .setPositiveButton("Abrir descargas") { _, _ ->
                    runCatching { activity.startActivity(MainActivity.openUrlIntent(activity, RELEASES_URL)) }
                }
                .setNeutralButton("Más tarde") { _, _ -> snooze(activity) }
                .setNegativeButton("No usarla") { _, _ -> prefs(activity).edit().putString(KEY_STATE, "dismissed").apply() }
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Trae tus datos de Nexo anterior")
            .setMessage("Se encontró la versión anterior de Nexo. ¿Pasar a esta app el historial, los accesos rápidos, las pestañas normales, los ajustes y las claves de servicios?\n\nNo se copian pestañas privadas, Burner, cookies ni descargas. La app anterior no se modifica.")
            .setPositiveButton("Traer datos") { _, _ -> run(activity, onImported) }
            .setNeutralButton("Más tarde") { _, _ -> snooze(activity) }
            .setNegativeButton("No, gracias") { _, _ -> prefs(activity).edit().putString(KEY_STATE, "dismissed").apply() }
            .show()
    }

    private fun snooze(context: Context) {
        prefs(context).edit().putLong(KEY_SNOOZED_AT, System.currentTimeMillis()).apply()
    }

    private fun run(activity: Activity, onImported: () -> Unit) {
        val app = activity.applicationContext
        if (!signedAlike(app)) {
            failed(activity, SecurityException("firma distinta"))
            return
        }
        Toast.makeText(activity, "Trayendo datos de Nexo anterior…", Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching {
                val bundle = app.contentResolver.call(Uri.parse("content://$AUTHORITY"), LegacyProfileProvider.METHOD_EXPORT, null, null)
                val raw = bundle?.getString(LegacyProfileProvider.KEY_PAYLOAD) ?: error("La app anterior no respondió.")
                LegacyMerge.parse(raw)
            }
            main.post {
                result.fold(
                    onSuccess = { payload ->
                        val summary = runCatching { apply(app, payload) }
                        summary.onSuccess { s ->
                            prefs(app).edit().putString(KEY_STATE, "done").apply()
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                AlertDialog.Builder(activity)
                                    .setTitle("Datos traídos")
                                    .setMessage(describe(s) + "\n\nCuando compruebes que todo está bien puedes desinstalar la app anterior.")
                                    .setPositiveButton("Listo") { _, _ -> onImported() }
                                    .setOnCancelListener { onImported() }
                                    .show()
                            }
                        }.onFailure { error -> failed(activity, error) }
                    },
                    onFailure = { error -> failed(activity, error) }
                )
            }
        }
    }

    private fun failed(activity: Activity, error: Throwable) {
        if (activity.isFinishing || activity.isDestroyed) return
        val reason = when (error) {
            is SecurityException -> "La app anterior no tiene la misma firma o no es la versión de transición."
            else -> error.message ?: "error desconocido"
        }
        AlertDialog.Builder(activity)
            .setTitle("No se pudieron traer los datos")
            .setMessage("$reason\n\nNo se cambió nada. Puedes reintentarlo desde Ajustes › Traer datos de Nexo anterior.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun describe(s: Summary): String = listOfNotNull(
        s.history.takeIf { it > 0 }?.let { "$it páginas del historial" },
        s.tabs.takeIf { it > 0 }?.let { if (it == 1) "1 pestaña" else "$it pestañas" },
        s.links.takeIf { it > 0 }?.let { if (it == 1) "1 acceso rápido" else "$it accesos rápidos" },
        s.settings.takeIf { it > 0 }?.let { "$it ajustes" },
        s.secrets.takeIf { it > 0 }?.let { if (it == 1) "1 clave de servicio" else "$it claves de servicios" }
    ).joinToString(", ").ifBlank { "La app anterior no tenía datos que traer." }.replaceFirstChar { it.uppercase() }

    /** Aplica en el hilo principal (TabManager vive allí). Los ajustes se escriben de una vez. */
    private fun apply(context: Context, payload: LegacyMerge.Payload): Summary {
        val settings = context.getSharedPreferences("nexo_browser_settings", Context.MODE_PRIVATE).edit()
        payload.settings.forEach { (key, value) ->
            when (value) {
                is Boolean -> settings.putBoolean(key, value)
                is Int -> settings.putInt(key, value)
                is String -> settings.putString(key, value)
            }
        }
        check(settings.commit()) { "No se pudieron guardar los ajustes." }

        val historyPrefs = context.getSharedPreferences("nexo_history", Context.MODE_PRIVATE)
        val existingHistory = runCatching { JSONArray(historyPrefs.getString("entries", "[]")) }.getOrDefault(JSONArray())
        HistoryStore.replaceAll(context, LegacyMerge.mergeHistory(existingHistory, payload.history))

        val quick = context.getSharedPreferences("nexo_quick_access", Context.MODE_PRIVATE)
        val existingLinks = runCatching { JSONArray(quick.getString("custom", "[]")) }.getOrDefault(JSONArray())
        val hidden = quick.getStringSet("hidden", emptySet()).orEmpty() + payload.hiddenLinks
        quick.edit().putString("custom", LegacyMerge.mergeLinks(existingLinks, payload.customLinks).toString())
            .putStringSet("hidden", hidden).commit()

        val tabs = LegacyMerge.newTabs(TabManager.allTabs().map { it.url }, payload.tabs)
        tabs.forEach { TabManager.importTab(it.url, it.title, it.groupName, it.desktopMode) }

        val secrets = LegacyMerge.secretsToApply(
            TransferSchema.secretNames.associateWith { SecretStore.get(context, it).isNotEmpty() },
            payload.secrets
        )
        secrets.forEach { (name, value) -> SecretStore.put(context, name, value) }

        return Summary(payload.history.length(), tabs.size, payload.customLinks.length(), payload.settings.size, secrets.size)
    }

    // --- Lado de la app anterior (actualización de transición) --------------

    private const val KEY_MOVED_SHOWN = "moved_notice_at"

    /** En la app anterior: avisa (una vez al día como mucho) de que Nexo se mudó. */
    fun noticeInOldApp(activity: Activity) {
        if (activity.packageName != SOURCE_PACKAGE) return
        val prefs = prefs(activity)
        if (System.currentTimeMillis() - prefs.getLong(KEY_MOVED_SHOWN, 0L) < 24L * 3600 * 1000) return
        prefs.edit().putLong(KEY_MOVED_SHOWN, System.currentTimeMillis()).apply()
        val launch = activity.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        AlertDialog.Builder(activity)
            .setTitle("Nexo se mudó a una app nueva")
            .setMessage(
                if (launch != null) "Ya tienes Nexo nuevo instalado. Ábrelo y toca «Traer datos»: el historial, los accesos, las pestañas, los ajustes y las claves se pasan solos. Esta versión ya no recibirá novedades."
                else "Las próximas versiones llegan como una app nueva. Descarga «Nexo» (sin «anterior») de la página de versiones; al abrirla podrás traer aquí tus datos con un toque. Esta app sigue funcionando mientras tanto."
            )
            .setPositiveButton(if (launch != null) "Abrir Nexo nuevo" else "Descargar") { _, _ ->
                runCatching {
                    if (launch != null) activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else activity.startActivity(MainActivity.openUrlIntent(activity, RELEASES_URL))
                }
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun signedAlike(context: Context): Boolean =
        runCatching { context.packageManager.checkSignatures(context.packageName, SOURCE_PACKAGE) == PackageManager.SIGNATURE_MATCH }.getOrDefault(false)
}
