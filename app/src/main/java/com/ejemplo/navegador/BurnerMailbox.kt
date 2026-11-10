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
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
        val dialog = Dialog(activity, ThemeManager.themeRes(activity))
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(p.background) }
        val heading = TextView(activity).apply { text = "Buzón Burner"; textSize = 22f; setTextColor(p.text) }
        val status = TextView(activity).apply { setTextColor(p.muted); setPadding(0,20,0,20) }
        val items = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(heading); root.addView(status)
        val refresh = Button(activity).apply { text = "Actualizar buzón"; ThemeManager.styleButton(activity, this, primary = true) }
        root.addView(refresh)
        root.addView(ScrollView(activity).apply { addView(items) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        root.addView(Button(activity).apply { text = "Cerrar panel"; ThemeManager.styleButton(activity,this); setOnClickListener { dialog.dismiss() } })
        fun render() {
            val account = accounts[id]
            status.text = account?.address ?: errors[id] ?: "Creando correo temporal…"
            if (account == null) {
                refresh.text = if (pending.contains(id)) "Creando…" else "Reintentar creación"
                refresh.isEnabled = !pending.contains(id)
                return
            }
            status.setOnClickListener { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Correo Burner", account.address)) }
            refresh.isEnabled = false; status.text = account.address + "\nCargando mensajes…"
            io.execute {
                val messages = runCatching { client.list(account) }
                main.post {
                    if (!dialog.isShowing || !active.contains(id)) return@post
                    refresh.isEnabled = true; items.removeAllViews()
                    status.text = account.address + "\nToca la dirección para copiar. " + if (messages.isSuccess) "${messages.getOrThrow().size} mensajes." else "No se pudo actualizar. Espera y vuelve a intentarlo."
                    messages.getOrDefault(emptyList()).forEach { message ->
                        items.addView(Button(activity).apply {
                            text = message.subject.ifBlank { "Sin asunto" } + "\n" + message.from
                            ThemeManager.styleButton(activity,this)
                            setOnClickListener {
                                isEnabled = false
                                io.execute {
                                    val body = runCatching { client.text(account,message.id) }.getOrDefault("No se pudo cargar el mensaje.")
                                    main.post {
                                        isEnabled = true
                                        if (dialog.isShowing && active.contains(id)) {
                                            val messageDialog=AlertDialog.Builder(activity).setTitle(message.subject.take(100)).setMessage(body).setPositiveButton("Cerrar",null).create()
                                            track(id,messageDialog);messageDialog.show()
                                        }
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
        refresh.setOnClickListener { if (accounts[id] == null) createAccount(id) { if (dialog.isShowing) render() } else render() }
        track(id,dialog)
        dialog.setContentView(root); dialog.show()
        dialog.window?.apply { setGravity(Gravity.END); setLayout((activity.resources.displayMetrics.widthPixels*0.94).toInt(),ViewGroup.LayoutParams.MATCH_PARENT) }
        render()
    }
}
