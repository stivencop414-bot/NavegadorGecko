package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.text.InputType
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

/** Cold maintenance is deliberately persistent across crashes and process death. */
object MigrationMode {
    // Survives Activity recreation; recovery never races an existing operation.
    internal val io = Executors.newSingleThreadExecutor()
    @Volatile var dataChanged = false
        private set
    fun markDataChanged() { dataChanged = true }
    var cold = false
        private set
    private fun marker(c: Context) = File(c.filesDir,"transfer-maintenance")
    fun initialize(c: Context) { cold = marker(c).exists() }
    fun enabled(c: Context) = marker(c).exists()
    fun ready(c: Context) = marker(c).takeIf { it.exists() }?.readBytes()?.contentEquals(byteArrayOf(2)) == true
    fun commit(c: Context) = TransferCommit(File(c.applicationInfo.dataDir),File(c.filesDir,"transfer-journal"))
    fun enter(activity: Activity) {
        AlertDialog.Builder(activity).setTitle("Transferir el perfil")
            .setMessage("Se cerrará Nexo. Vuelve a abrirlo para exportar o importar sin Gecko activo. No se incluyen descargas, cookies web, pestañas privadas ni Burner. Conserva la contraseña de la copia; no se puede recuperar.")
            .setPositiveButton("Cerrar y preparar") { _,_ ->
                TabManager.persistNow()
                TabStore.flush()
                HistoryStore.flush()
                listOf("nexo_browser_settings", "nexo_history", "nexo_quick_access", "nexo_secrets").forEach {
                    check(activity.getSharedPreferences(it, Context.MODE_PRIVATE).edit().commit())
                }
                marker(activity).apply { parentFile?.mkdirs(); java.io.FileOutputStream(this).use { it.write(byteArrayOf(1)); it.fd.sync() } }
                LiveCaptions.stopAll()
                activity.stopService(Intent(activity, MediaPlaybackService::class.java))
                GeckoRuntimeHolder.shutdownForTransfer {
                    // Drain writes queued by the final navigation/shutdown callbacks too.
                    TabStore.flush()
                    HistoryStore.flush()
                    listOf("nexo_browser_settings", "nexo_history", "nexo_quick_access", "nexo_secrets").forEach {
                        check(activity.getSharedPreferences(it, Context.MODE_PRIVATE).edit().commit())
                    }
                    java.io.FileOutputStream(marker(activity)).use { it.write(byteArrayOf(2)); it.fd.sync() }
                    activity.finishAffinity(); Process.killProcess(Process.myPid())
                }
            }.setNegativeButton("Cancelar",null).show()
    }
    fun leave(activity: Activity) {
        check(commit(activity).state() !in listOf("applying", "restoring")) { "Primero restaura la importación interrumpida." }
        check(marker(activity).delete())
        activity.finishAffinity(); Process.killProcess(Process.myPid())
    }
}

class MigrationActivity : Activity() {
    private val io = MigrationMode.io
    private lateinit var status: TextView
    private lateinit var password: EditText
    private lateinit var actions: LinearLayout
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(MigrationMode.cold && !GeckoRuntimeHolder.started())
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,32,24,24) }
        status = TextView(this).apply { text = "Transferencia de Nexo · ${packageName}\nGecko está detenido. La importación reemplaza ajustes, historial, accesos y pestañas normales. Conserva los datos anteriores del destino hasta que decidas eliminarlos. Las extensiones externas se reinstalan después con confirmación y verificación de Gecko. No se importan cookies ni descargas." }
        password = EditText(this).apply { hint = "Contraseña de la copia (mínimo 12 caracteres)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: String, action: () -> Unit) { actions.addView(Button(this).apply { text = label; setOnClickListener { if(!busy) action() } }) }
        button("Exportar copia cifrada") { pick(true) }
        button("Importar y reemplazar datos") {
            AlertDialog.Builder(this).setMessage("Valida toda la copia antes de reemplazar. Los datos anteriores del destino quedarán guardados para restaurar. ¿Continuar?")
                .setPositiveButton("Seleccionar copia") { _,_ -> pick(false) }.setNegativeButton("Cancelar",null).show()
        }
        button("Restaurar datos anteriores del destino") {
            AlertDialog.Builder(this).setMessage("Se deshará la última importación. Los cambios posteriores de los archivos afectados se reemplazarán por los originales.")
                .setPositiveButton("Restaurar") { _,_ -> work { MigrationMode.markDataChanged(); MigrationMode.commit(this).restore(); "Restauración terminada. Sal del modo de transferencia y vuelve a abrir Nexo." } }
                .setNegativeButton("Cancelar",null).show()
        }
        button("Eliminar respaldo anterior del destino") {
            AlertDialog.Builder(this).setMessage("Elimina de forma irreversible el respaldo del destino de la última importación. La copia exportada y el paquete antiguo se conservan.")
                .setPositiveButton("Eliminar respaldo") { _,_ -> work { MigrationMode.commit(this).discardBackup(); "Respaldo eliminado." } }.setNegativeButton("Cancelar",null).show()
        }
        button("Salir del modo de transferencia y cerrar") { if (!busy) {
            if (MigrationMode.commit(this).state() in listOf("applying", "restoring")) status.text = "Restaura primero la importación interrumpida."
            else MigrationMode.leave(this)
        } }
        box.addView(status);box.addView(password);box.addView(actions);setContentView(ScrollView(this).apply { addView(box) })
        work { MigrationMode.commit(this).recover();
            File(filesDir,"transfer-work").deleteRecursively()
            "Modo de transferencia listo. Si hubo una importación interrumpida, se restauraron los originales."
        }
    }
    private fun pick(export: Boolean) {
        if (MigrationMode.dataChanged) { status.text = "Los archivos han cambiado. Sal de mantenimiento y vuelve a abrir Nexo antes de otra exportación o importación; así no se reutilizan cachés antiguas."; return }
        if (!MigrationMode.ready(this)) { status.text = "El cierre de Gecko se interrumpió. Sal de este modo, abre Nexo y vuelve a preparar la transferencia para cerrar el perfil limpiamente."; return }
        if (password.text.length !in 12..256) { status.text = "Usa una contraseña de 12 a 256 caracteres."; return }
        startActivityForResult(Intent(if(export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE)
            if(export) putExtra(Intent.EXTRA_TITLE,"Nexo-perfil.nexo")
        }, if(export) 1 else 2)
    }
    @Deprecated("Android Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode != RESULT_OK || data?.data == null) return
        val uri = requireNotNull(data.data)
        val secret = CharArray(password.text.length) { password.text[it] }; password.text.clear()
        work {
            val work = File(filesDir,"transfer-work").apply { deleteRecursively(); mkdirs() }
            try {
                if(requestCode == 1) {
                    val stage = File(work,"export")
                    ProfileTransfer.export(this,stage)
                    contentResolver.openOutputStream(uri,"wt").use { output -> MigrationArchive.write(stage,requireNotNull(output),secret) }
                    "Exportación cifrada terminada. Los originales se conservan. Usa la misma contraseña al importar en el paquete nuevo."
                } else {
                    val stage = File(work,"import")
                    contentResolver.openInputStream(uri).use { MigrationArchive.read(requireNotNull(it),stage,secret) }
                    val replacements = ProfileTransfer.prepare(this,stage)
                    MigrationMode.markDataChanged()
                    MigrationMode.commit(this).apply(replacements)
                    "Importación aplicada. Sal del modo de transferencia, vuelve a abrir Nexo y revisa tus datos. Reinstala los XPI transferidos desde Extensiones; Gecko verificará firmas y pedirá permisos. Puedes volver aquí para restaurar los originales del destino."
                }
            } finally { secret.fill('\u0000'); work.deleteRecursively() }
        }
    }
    private fun work(action: () -> String) {
        busy = true; status.text = "Procesando en modo de mantenimiento…"
        io.execute {
            val result = runCatching(action)
            runOnUiThread {
                busy = false
                status.text = result.getOrElse { "Transferencia no completada (${it.javaClass.simpleName}). No abras el navegador: vuelve a intentar restaurar o reinicia Nexo para recuperar los originales. Comprueba contraseña, espacio libre y formato de la copia." }
            }
        }
    }
    @Deprecated("Prevent leaving maintenance while modifying storage")
    override fun onBackPressed() { if (!busy) Toast.makeText(this,"Usa Salir del modo de transferencia para reiniciar.",Toast.LENGTH_LONG).show() }
}
