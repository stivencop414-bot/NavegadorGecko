package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Account credentials live in memory only and are never forwarded to web pages. */
object BurnerMailbox {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val accounts = java.util.concurrent.ConcurrentHashMap<String, MailTmClient.Account>()
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val client = MailTmClient()
    private val dialogs = mutableMapOf<String,MutableSet<Dialog>>()
    private fun track(id:String,dialog:Dialog) {
        dialogs.getOrPut(id){mutableSetOf()}.add(dialog)
        dialog.setOnDismissListener { dialogs[id]?.let { group -> group.remove(dialog);if(group.isEmpty())dialogs.remove(id) } }
    }

    fun open(activity: Activity) {
        val prefs = activity.getSharedPreferences("burner", Context.MODE_PRIVATE)
        fun create() {
            val id = "burner-" + java.util.UUID.randomUUID()
            active.add(id)
            TabManager.createTab(BrowserPrefs.homePage(activity), true, true, "Burner", id, TabManager.activeTab()?.networkProfile)
            createAccount(id) { if(TabManager.activeTab()?.burnerId == id) show(activity, id) }
        }
        if (prefs.getBoolean("mailConsent", false)) create()
        else AlertDialog.Builder(activity).setTitle("Crear identidad Burner")
            .setMessage("Se abrirá un contenedor privado aislado y un correo temporal en mail.tm. Ese servicio gestiona el buzón y verá la conexión. El buzón usa una conexión propia fuera del proxy de la pestaña; la pestaña hereda la ruta actual. Al cerrar todas las pestañas de la identidad se borran los datos locales y se solicita eliminar la cuenta; el borrado remoto necesita conexión. Los archivos descargados permanecen. No oculta tu IP ni garantiza anonimato.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Crear Burner") { _, _ ->
                prefs.edit().putBoolean("mailConsent", true).apply(); create()
            }.show()
    }

    private fun createAccount(id: String, done: () -> Unit) {
        if (!active.contains(id) || !pending.add(id)) return
        io.execute {
            try {
                val account = client.create()
                val retain = synchronized(this) { if(active.contains(id)) { accounts[id]=account; true } else false }
                if(!retain) runCatching { client.delete(account) }
                errors.remove(id)
            } catch (_: Exception) { errors[id] = "No se pudo crear el buzón. Comprueba la conexión o inténtalo de nuevo." }
            finally { pending.remove(id); main.post { if (active.contains(id)) done() } }
        }
    }

    fun close(id: String) {
        main.post { dialogs.remove(id)?.toList()?.forEach { it.dismiss() } }
        val account = synchronized(this) { active.remove(id); errors.remove(id); accounts.remove(id) } ?: return
        io.execute { runCatching { client.delete(account) } }
    }

    fun show(activity: Activity, id: String) {
        if (!active.contains(id) || activity.isFinishing || activity.isDestroyed) return
        val p = ThemeManager.palette(activity)
        lateinit var dialog: Dialog
        val body = NexoUi.column(activity)

        // Tarjeta de la dirección: monoespaciada, con botón Copiar.
        val addressCard = NexoUi.card(activity)
        val address = TextView(activity).apply {
            textSize = 15f; setTextColor(p.text); setTextIsSelectable(true)
            typeface = activity.resources.getFont(R.font.jetbrains_mono)
        }
        val status = TextView(activity).apply { textSize = 12.5f; setTextColor(p.muted); setPadding(0, NexoUi.dp(activity, 6f), 0, 0) }
        val copy = NexoUi.chip(activity, "Copiar", false) {
            val account = accounts[id] ?: return@chip
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Correo Burner", account.address))
            Toastish.show(activity, "Dirección copiada.")
        }
        addressCard.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, NexoUi.dp(activity, 38f)).apply {
                marginStart = NexoUi.dp(activity, 10f)
            })
        })
        addressCard.addView(status)
        body.addView(addressCard)

        val refresh = NexoUi.button(activity, "Actualizar buzón") {}
        body.addView(refresh, NexoUi.buttonParams(activity, 12f))
        body.addView(NexoUi.section(activity, "Mensajes"))
        val items = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(items)
        body.addView(NexoUi.note(activity, "El buzón vive solo en memoria y no se incluye en copias ni restauraciones. " +
            "Al destruir la identidad se solicita borrar la cuenta en mail.tm."))

        fun openMessage(account: MailTmClient.Account, message: MailTmClient.Message, row: View) {
            row.isEnabled = false
            io.execute {
                val text = runCatching { client.text(account, message.id) }.getOrDefault("No se pudo cargar el mensaje.")
                main.post {
                    row.isEnabled = true
                    if (!dialog.isShowing || !active.contains(id)) return@post
                    val content = NexoUi.column(activity).apply {
                        addView(TextView(activity).apply {
                            this.text = text; textSize = 14.5f; setTextColor(p.text); setTextIsSelectable(true)
                            setLineSpacing(0f, 1.2f); setPadding(NexoUi.dp(activity, 4f), 0, NexoUi.dp(activity, 4f), NexoUi.dp(activity, 8f))
                            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
                        })
                    }
                    val messageDialog = NexoSheets.show(activity, NexoSheets.column(activity,
                        NexoSheets.header(activity, message.subject.ifBlank { "Sin asunto" }.take(100), message.from), content))
                    track(id, messageDialog)
                }
            }
        }

        fun render() {
            val account = accounts[id]
            if (account == null) {
                address.text = if (pending.contains(id)) "Creando correo temporal…" else "Sin buzón"
                status.text = errors[id] ?: "Preparando la cuenta en mail.tm."
                copy.visibility = View.GONE
                refresh.text = if (pending.contains(id)) "Creando…" else "Reintentar creación"
                refresh.isEnabled = !pending.contains(id)
                return
            }
            address.text = account.address
            copy.visibility = View.VISIBLE
            refresh.text = "Actualizar buzón"
            refresh.isEnabled = false; status.text = "Cargando mensajes…"
            io.execute {
                val messages = runCatching { client.list(account) }
                main.post {
                    if (!dialog.isShowing || !active.contains(id)) return@post
                    refresh.isEnabled = true; items.removeAllViews()
                    val list = messages.getOrDefault(emptyList())
                    status.text = when {
                        messages.isFailure -> "No se pudo actualizar. Espera y vuelve a intentarlo."
                        list.isEmpty() -> "Aún no hay mensajes. Usa esta dirección y pulsa Actualizar."
                        list.size == 1 -> "1 mensaje"
                        else -> "${list.size} mensajes"
                    }
                    list.forEach { message ->
                        lateinit var row: View
                        row = NexoUi.actionRow(activity, R.drawable.ic_menu_file, message.subject.ifBlank { "Sin asunto" }, message.from) {
                            openMessage(account, message, row)
                        }
                        items.addView(row)
                    }
                }
            }
        }
        refresh.setOnClickListener { if (accounts[id] == null) createAccount(id) { if (dialog.isShowing) render() } else render() }
        dialog = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, "Buzón Burner", "Correo temporal de esta identidad"), body))
        track(id, dialog)
        render()
    }
}
