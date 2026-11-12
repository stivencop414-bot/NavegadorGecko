package com.nexo.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.io.File

/**
 * Solo en la actualización de transición (paquete com.ejemplo.navegador):
 * entrega a Nexo nuevo (com.nexo.browser) el historial, accesos, pestañas
 * normales, ajustes y claves de servicios, para que se pasen sin exportar
 * archivos. Únicamente responde a com.nexo.browser firmado con la misma
 * clave: `call()` no queda cubierto por los permisos del manifiesto, así que
 * se comprueban aquí el permiso de firma, el paquete y la firma del llamante.
 */
class LegacyProfileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (method != METHOD_EXPORT || context.packageName != LegacyImport.SOURCE_PACKAGE) return null
        val uid = Binder.getCallingUid()
        val pm = context.packageManager
        val caller = callingPackage
        require(caller == LegacyImport.TARGET_PACKAGE && pm.getPackagesForUid(uid)?.contains(caller) == true) { "Llamante no permitido" }
        require(pm.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH) { "Firma distinta" }
        context.enforceCallingPermission(LegacyImport.PERMISSION, "Permiso de transferencia requerido")

        val payload = Binder.clearCallingIdentity().let { token ->
            try { exportNative(context) } finally { Binder.restoreCallingIdentity(token) }
        }
        return Bundle().apply { putString(KEY_PAYLOAD, payload) }
    }

    private fun exportNative(context: Context): String {
        TabManager.persistNow()
        runCatching { TabStore.flush() }
        runCatching { HistoryStore.flush() }
        val settings = context.getSharedPreferences("nexo_browser_settings", Context.MODE_PRIVATE).all
            .filterKeys { it in TransferSchema.settingsKeys }
        val history = context.getSharedPreferences("nexo_history", Context.MODE_PRIVATE).getString("entries", "[]")
        val quick = context.getSharedPreferences("nexo_quick_access", Context.MODE_PRIVATE)
        val tabs = File(context.filesDir, "tabs.json").takeIf { it.isFile }?.readText() ?: BrowserPrefs.tabsJson(context)
        // Las claves se descifran aquí con el Keystore de este paquete; el destino las cifra con el suyo.
        val secrets = TransferSchema.secretNames.associateWith { SecretStore.get(context, it) }
        val payload = LegacyMerge.build(settings, history, quick.getString("custom", "[]"), quick.getStringSet("hidden", emptySet()), tabs, secrets).toString()
        // Límite de Binder (~1 MB): mejor un error claro que un fallo a medias.
        require(payload.length <= 900_000) { "Demasiados datos para pasarlos automáticamente; usa la copia cifrada de Ajustes." }
        return payload
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_EXPORT = "export_native_v1"
        const val KEY_PAYLOAD = "payload"
    }
}
