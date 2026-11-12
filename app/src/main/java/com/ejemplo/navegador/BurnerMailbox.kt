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
    private const val AUTO_REFRESH_MS = 10_000L
    private const val MAX_AUTO_REFRESHES = 30

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val accounts = java.util.concurrent.ConcurrentHashMap<String, MailTmClient.Account>()
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val client = MailTmClient()
    private val dialogs = mutableMapOf<String,MutableSet<Dialog>>()
    // Buzones abiertos en pantalla: se vuelven a dibujar cuando termina la creación.
    private val renderers = mutableMapOf<String, () -> Unit>()

    private fun track(id:String,dialog:Dialog) {
        dialogs.getOrPut(id){mutableSetOf()}.add(dialog)
        dialog.setOnDismissListener { dialogs[id]?.let { group -> group.remove(dialog);if(group.isEmpty())dialogs.remove(id) } }
    }

    fun isActive(id: String?): Boolean = id != null && active.contains(id)

    /** Dirección de correo de la identidad, si ya existe. */
    fun address(id: String?): String? = id?.let { accounts[it]?.address }

    fun open(activity: Activity) {
        val prefs = activity.getSharedPreferences("burner", Context.MODE_PRIVATE)
        fun create() {
            val id = "burner-" + java.util.UUID.randomUUID()
            active.add(id)
            // Un perfil de red no válido de la pestaña actual no debe bloquear la identidad nueva.
            val inherited = TabManager.activeTab()?.networkProfile?.takeIf { it.type != "invalid" }
            TabManager.createTab(BrowserPrefs.homePage(activity), true, true, "Burner", id, inherited)
            createAccount(id)
            // El buzón se muestra al momento ("Creando…") y se completa solo.
            if (TabManager.activeTab()?.burnerId == id) show(activity, id)
        }
        if (prefs.getBoolean("mailConsent", false)) create()
        else AlertDialog.Builder(activity).setTitle("Crear identidad Burner")
            .setMessage("Se abrirá un contenedor privado aislado y un correo temporal en mail.tm. Ese servicio gestiona el buzón y verá la conexión. El buzón usa una conexión propia fuera del proxy de la pestaña; la pestaña hereda la ruta actual. Al cerrar todas las pestañas de la identidad se borran los datos locales y se solicita eliminar la cuenta; el borrado remoto necesita conexión. Los archivos descargados permanecen. No oculta tu IP ni garantiza anonimato.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Crear Burner") { _, _ ->
                prefs.edit().putBoolean("mailConsent", true).apply(); create()
            }.show()
    }

    private fun createAccount(id: String, done: () -> Unit = {}) {
        if (!active.contains(id) || !pending.add(id)) return
        errors.remove(id)
        renderers[id]?.invoke()
        io.execute {
            try {
                val account = createWithRetry()
                val retain = synchronized(this) { if(active.contains(id)) { accounts[id]=account; true } else false }
                if(!retain) runCatching { client.delete(account) }
                errors.remove(id)
            } catch (error: Exception) {
                errors[id] = when ((error as? MailTmClient.ApiException)?.status) {
                    429 -> "mail.tm pidió esperar. Vuelve a intentarlo en un minuto."
                    else -> "No se pudo crear el buzón. Comprueba la conexión o inténtalo de nuevo."
                }
            } finally {
                pending.remove(id)
                main.post {
                    if (active.contains(id)) {
                        done(); renderers[id]?.invoke()
                        if (TabManager.activeTab()?.burnerId == id) TabManager.refreshActive()
                    }
                }
            }
        }
    }

    /** Un fallo de red puntual no deja la identidad sin buzón: dos reintentos con espera. */
    private fun createWithRetry(): MailTmClient.Account {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return client.create()
            } catch (error: MailTmClient.ApiException) {
                if (error.status == 429 || error.status in 400..499) throw error
                last = error
            } catch (error: Exception) {
                last = error
            }
            Thread.sleep(800L * (attempt + 1))
        }
        throw last ?: IllegalStateException("No se pudo crear el buzón.")
    }

    fun close(id: String) {
        main.post {
            renderers.remove(id)
            dialogs.remove(id)?.toList()?.forEach { it.dismiss() }
        }
        val account = synchronized(this) { active.remove(id); errors.remove(id); accounts.remove(id) } ?: return
        io.execute { runCatching { client.delete(account) } }
    }

    private fun copy(activity: Activity, label: String, value: String, toast: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        Toastish.show(activity, toast)
    }

    fun show(activity: Activity, id: String) {
        if (!active.contains(id) || activity.isFinishing || activity.isDestroyed) return
        // Un solo buzón abierto por identidad.
        dialogs[id]?.toList()?.forEach { it.dismiss() }
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
            copy(activity, "Correo Burner", account.address, "Dirección copiada.")
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
        body.addView(NexoUi.note(activity, "Se actualiza solo mientras está abierto. El buzón se borra al cerrar la identidad."))

        fun openMessage(account: MailTmClient.Account, message: MailTmClient.Message, row: View) {
            row.isEnabled = false
            io.execute {
                val text = runCatching { client.text(account, message.id) }.getOrDefault("No se pudo cargar el mensaje.")
                main.post {
                    row.isEnabled = true
                    if (!dialog.isShowing || !active.contains(id)) return@post
                    val content = NexoUi.column(activity)
                    MailTmClient.verificationCode(message.subject, message.intro, text.take(2000))?.let { code ->
                        content.addView(NexoUi.chip(activity, "Copiar código  $code", true) {
                            copy(activity, "Código", code, "Código $code copiado.")
                        })
                    }
                    content.addView(TextView(activity).apply {
                        this.text = text; textSize = 14.5f; setTextColor(p.text); setTextIsSelectable(true)
                        setLineSpacing(0f, 1.2f); setPadding(NexoUi.dp(activity, 4f), NexoUi.dp(activity, 8f), NexoUi.dp(activity, 4f), NexoUi.dp(activity, 8f))
                        android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
                    })
                    val messageDialog = NexoSheets.show(activity, NexoSheets.column(activity,
                        NexoSheets.header(activity, message.subject.ifBlank { "Sin asunto" }.take(100), message.from), content))
                    track(id, messageDialog)
                }
            }
        }

        var loading = false
        var autoRefreshes = 0
        var lastIds = emptyList<String>()

        fun render(quiet: Boolean = false) {
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
            if (loading) return
            loading = true
            refresh.isEnabled = false
            if (!quiet) status.text = "Cargando mensajes…"
            io.execute {
                val messages = runCatching { client.list(account) }
                main.post {
                    loading = false
                    if (!dialog.isShowing || !active.contains(id)) return@post
                    refresh.isEnabled = true
                    val list = messages.getOrElse {
                        status.text = (it as? MailTmClient.ApiException)?.message ?: "No se pudo actualizar. Espera y vuelve a intentarlo."
                        return@post
                    }
                    status.text = when {
                        list.isEmpty() -> "Aún no hay mensajes. Usa esta dirección; los nuevos aparecerán aquí."
                        list.size == 1 -> "1 mensaje"
                        else -> "${list.size} mensajes"
                    }
                    val ids = list.map { it.id }
                    if (ids == lastIds && items.childCount > 0) return@post
                    if (quiet && ids.size > lastIds.size && lastIds.isNotEmpty()) Toastish.show(activity, "Nuevo correo en el buzón Burner.")
                    lastIds = ids
                    items.removeAllViews()
                    list.forEach { message ->
                        lateinit var row: View
                        val code = MailTmClient.verificationCode(message.subject, message.intro)
                        val subtitle = listOfNotNull(message.from.ifBlank { null }, code?.let { "código $it" }).joinToString(" · ")
                        row = NexoUi.actionRow(activity, R.drawable.ic_menu_file, message.subject.ifBlank { "Sin asunto" }, subtitle) {
                            openMessage(account, message, row)
                        }
                        if (code != null) row.setOnLongClickListener {
                            copy(activity, "Código", code, "Código $code copiado."); true
                        }
                        items.addView(row)
                    }
                }
            }
        }

        // Comprobación periódica mientras el buzón está a la vista (limitada).
        val autoRefresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || !active.contains(id)) return
                if (accounts[id] != null && autoRefreshes++ < MAX_AUTO_REFRESHES) render(quiet = true)
                if (autoRefreshes < MAX_AUTO_REFRESHES) main.postDelayed(this, AUTO_REFRESH_MS)
            }
        }

        refresh.setOnClickListener { if (accounts[id] == null) createAccount(id) else { autoRefreshes = 0; render() } }
        dialog = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, "Buzón Burner", "Correo temporal de esta identidad"), body))
        track(id, dialog)
        val renderer = { if (dialog.isShowing) render() }
        renderers[id] = renderer
        dialog.setOnDismissListener {
            main.removeCallbacks(autoRefresh)
            if (renderers[id] === renderer) renderers.remove(id)
            dialogs[id]?.let { group -> group.remove(dialog); if (group.isEmpty()) dialogs.remove(id) }
        }
        render()
        main.postDelayed(autoRefresh, AUTO_REFRESH_MS)
    }
}
