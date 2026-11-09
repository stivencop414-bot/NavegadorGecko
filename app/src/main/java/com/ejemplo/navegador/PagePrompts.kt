package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.Locale
import java.util.WeakHashMap

/**
 * Diálogos que piden las páginas web: alert/confirm/prompt, <select>,
 * fechas y horas, colores, archivos, autenticación HTTP, salir de la página,
 * reenviar formularios y navigator.share().
 *
 * Sin este delegado GeckoView descarta todos esos diálogos: los menús
 * desplegables no se abrían, los campos de fecha no hacían nada y no se
 * podían subir archivos.
 */
object PagePrompts : PromptDelegate {
    private const val DIALOG_BURST = 3
    private const val DIALOG_BURST_WINDOW_MS = 15_000L

    /** Sesiones cuyo usuario pidió no mostrar más alert/confirm hasta navegar. */
    private val blockedDialogs = WeakHashMap<GeckoSession, Boolean>()
    private val recentDialogs = WeakHashMap<GeckoSession, ArrayDeque<Long>>()
    private val popupNotified = WeakHashMap<GeckoSession, Boolean>()

    /** Una navegación nueva restablece los bloqueos de diálogos. */
    fun onPageStart(session: GeckoSession) {
        blockedDialogs.remove(session)
        recentDialogs.remove(session)
        popupNotified.remove(session)
    }

    // ---------------------------------------------------------------
    // Infraestructura
    // ---------------------------------------------------------------

    /** Respuesta única: la primera acción gana y las siguientes se ignoran. */
    private class Pending(val prompt: BasePrompt) {
        val result = GeckoResult<PromptResponse>()
        private var done = false

        fun finish(response: () -> PromptResponse) {
            if (done) return
            done = true
            val value = runCatching { if (prompt.isComplete) null else response() }.getOrNull()
                ?: runCatching { if (prompt.isComplete) null else prompt.dismiss() }.getOrNull()
            if (value != null) result.complete(value)
        }

        fun dismiss() = finish { prompt.dismiss() }

        /** La página retiró el diálogo: ya no espera respuesta. */
        fun abandon() {
            done = true
        }
    }

    private fun activity(): Activity? = BrowserHosts.current()?.hostActivity

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun dismissed(prompt: BasePrompt): GeckoResult<PromptResponse> =
        GeckoResult.fromValue(prompt.dismiss())

    /** Vincula el diálogo con el aviso: si la página lo retira, se cierra. */
    private fun attach(pending: Pending, dialog: Dialog, dismissOnClose: Boolean = true) {
        pending.prompt.setDelegate(object : PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) {
                pending.abandon()
                runCatching { dialog.dismiss() }
            }
        })
        if (dismissOnClose) dialog.setOnDismissListener { pending.dismiss() }
    }

    private fun present(pending: Pending, dialog: Dialog): GeckoResult<PromptResponse> {
        attach(pending, dialog)
        runCatching {
            dialog.show()
            roundCorners(dialog)
        }.onFailure { pending.dismiss() }
        return pending.result
    }

    /**
     * Recorta el contenido a las esquinas del fondo del diálogo: la cabecera
     * de color de los selectores de fecha y hora se pintaba cuadrada.
     */
    private fun roundCorners(dialog: Dialog) {
        dialog.window?.decorView?.clipToOutline = true
    }

    /** Nombre del sitio que pide el diálogo; nunca el título que pone la página. */
    private fun origin(session: GeckoSession): String {
        val url = TabManager.tabForSession(session)?.url.orEmpty()
        val host = runCatching { Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        return host ?: "Esta página"
    }

    private fun isBlocked(session: GeckoSession): Boolean = blockedDialogs[session] == true

    /** Tras varios diálogos seguidos se ofrece bloquearlos (como otros navegadores). */
    private fun offerBlock(session: GeckoSession, builder: AlertDialog.Builder, pending: Pending) {
        val now = SystemClock.elapsedRealtime()
        val times = recentDialogs.getOrPut(session) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > DIALOG_BURST_WINDOW_MS) times.removeFirst()
        times.addLast(now)
        if (times.size >= DIALOG_BURST) {
            builder.setNeutralButton("Bloquear diálogos") { _, _ ->
                blockedDialogs[session] = true
                pending.dismiss()
            }
        }
    }

    private fun field(activity: Activity, value: String?, hint: String, password: Boolean = false): EditText =
        EditText(activity).apply {
            setText(value.orEmpty())
            this.hint = hint
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(activity, 16f), 0, dp(activity, 16f), 0)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else InputType.TYPE_CLASS_TEXT
            ThemeManager.styleEdit(activity, this)
        }

    private fun fieldsContainer(activity: Activity, vararg fields: View): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24f), dp(activity, 8f), dp(activity, 24f), 0)
            fields.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50f)).apply {
                    if (index > 0) topMargin = dp(activity, 10f)
                })
            }
        }

    private fun showKeyboard(dialog: Dialog) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ---------------------------------------------------------------
    // alert / confirm / prompt
    // ---------------------------------------------------------------

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.AlertPrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return dismissed(prompt)
        if (isBlocked(session)) return dismissed(prompt)
        val pending = Pending(prompt)
        val builder = AlertDialog.Builder(activity)
            .setTitle("${origin(session)} dice")
            .setMessage(prompt.message.orEmpty())
            .setPositiveButton("Aceptar") { _, _ -> pending.dismiss() }
        offerBlock(session, builder, pending)
        return present(pending, builder.create())
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ButtonPrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return dismissed(prompt)
        if (isBlocked(session)) return dismissed(prompt)
        val pending = Pending(prompt)
        val builder = AlertDialog.Builder(activity)
            .setTitle("${origin(session)} dice")
            .setMessage(prompt.message.orEmpty())
            .setNegativeButton("Cancelar") { _, _ ->
                pending.finish { prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE) }
            }
            .setPositiveButton("Aceptar") { _, _ ->
                pending.finish { prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE) }
            }
        offerBlock(session, builder, pending)
        return present(pending, builder.create())
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.TextPrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return dismissed(prompt)
        if (isBlocked(session)) return dismissed(prompt)
        val pending = Pending(prompt)
        val input = field(activity, prompt.defaultValue, "")
        val builder = AlertDialog.Builder(activity)
            .setTitle("${origin(session)} dice")
            .setMessage(prompt.message.orEmpty())
            .setView(fieldsContainer(activity, input))
            .setNegativeButton("Cancelar") { _, _ -> pending.dismiss() }
            .setPositiveButton("Aceptar") { _, _ ->
                pending.finish { prompt.confirm(input.text?.toString().orEmpty()) }
            }
        offerBlock(session, builder, pending)
        val dialog = builder.create()
        showKeyboard(dialog)
        input.selectAll()
        return present(pending, dialog)
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.AuthPrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return dismissed(prompt)
        val pending = Pending(prompt)
        val options = prompt.authOptions
        val onlyPassword =
            (options.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0
        val user = field(activity, options.username, "Usuario")
        val pass = field(activity, options.password, "Contraseña", password = true)
        val host = runCatching { Uri.parse(options.uri).host }.getOrNull().orEmpty()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Iniciar sesión")
            .setMessage(
                prompt.message?.takeIf { it.isNotBlank() }
                    ?: "$host pide un usuario y una contraseña."
            )
            .setView(if (onlyPassword) fieldsContainer(activity, pass) else fieldsContainer(activity, user, pass))
            .setNegativeButton("Cancelar") { _, _ -> pending.dismiss() }
            .setPositiveButton("Entrar") { _, _ ->
                val password = pass.text?.toString().orEmpty()
                pending.finish {
                    if (onlyPassword) prompt.confirm(password)
                    else prompt.confirm(user.text?.toString().orEmpty(), password)
                }
            }
            .create()
        showKeyboard(dialog)
        return present(pending, dialog)
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.BeforeUnloadPrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        val pending = Pending(prompt)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("¿Salir de esta página?")
            .setMessage("Es posible que no se guarden los cambios que hiciste.")
            .setNegativeButton("Quedarme") { _, _ -> pending.finish { prompt.confirm(AllowOrDeny.DENY) } }
            .setPositiveButton("Salir") { _, _ -> pending.finish { prompt.confirm(AllowOrDeny.ALLOW) } }
            .create()
        return present(pending, dialog)
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.RepostConfirmPrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        val pending = Pending(prompt)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("¿Reenviar el formulario?")
            .setMessage("Para mostrar esta página hay que volver a enviar los datos que ingresaste.")
            .setNegativeButton("Cancelar") { _, _ -> pending.finish { prompt.confirm(AllowOrDeny.DENY) } }
            .setPositiveButton("Reenviar") { _, _ -> pending.finish { prompt.confirm(AllowOrDeny.ALLOW) } }
            .create()
        return present(pending, dialog)
    }

    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.PopupPrompt
    ): GeckoResult<PromptResponse>? {
        // Ventanas abiertas sin que el usuario tocara nada: se bloquean.
        if (popupNotified.put(session, true) != true) {
            TabManager.notifyMessage("Nexo bloqueó una ventana emergente")
        }
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
    }

    override fun onSharePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.SharePrompt
    ): GeckoResult<PromptResponse>? {
        val host = BrowserHosts.current()
            ?: return GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.ABORT))
        val text = listOfNotNull(
            prompt.text?.takeIf { it.isNotBlank() },
            prompt.uri?.takeIf { UrlSafety.isWebUrl(it) }
        ).joinToString("\n")
        if (text.isBlank()) {
            return GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.FAILURE))
        }
        val shared = runCatching {
            host.launch(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        prompt.title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    },
                    "Compartir"
                )
            )
        }.isSuccess
        return GeckoResult.fromValue(
            prompt.confirm(
                if (shared) PromptDelegate.SharePrompt.Result.SUCCESS
                else PromptDelegate.SharePrompt.Result.FAILURE
            )
        )
    }

    // ---------------------------------------------------------------
    // <select>
    // ---------------------------------------------------------------

    private data class ChoiceRow(
        val choice: PromptDelegate.ChoicePrompt.Choice?,
        val label: String,
        val header: Boolean = false,
        val separator: Boolean = false,
        val depth: Int = 0,
        val disabled: Boolean = false
    )

    private fun flatten(
        choices: Array<out PromptDelegate.ChoicePrompt.Choice>?,
        out: MutableList<ChoiceRow>,
        depth: Int = 0,
        parentDisabled: Boolean = false
    ) {
        choices?.forEach { choice ->
            val disabled = parentDisabled || choice.disabled
            when {
                choice.separator -> out += ChoiceRow(null, "", separator = true, depth = depth)
                choice.items != null -> {
                    out += ChoiceRow(null, choice.label.orEmpty(), header = true, depth = depth, disabled = disabled)
                    flatten(choice.items, out, depth + 1, disabled)
                }
                else -> out += ChoiceRow(choice, choice.label.orEmpty(), depth = depth, disabled = disabled)
            }
        }
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ChoicePrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return dismissed(prompt)
        val rows = mutableListOf<ChoiceRow>().also { flatten(prompt.choices, it) }
        if (rows.none { it.choice != null }) return dismissed(prompt)

        return if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            multipleChoice(activity, prompt, rows)
        } else {
            singleChoice(activity, prompt, rows)
        }
    }

    private fun multipleChoice(
        activity: Activity,
        prompt: PromptDelegate.ChoicePrompt,
        rows: List<ChoiceRow>
    ): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        val selectable = rows.filter { it.choice != null && !it.disabled }
        // Opciones deshabilitadas pero marcadas: la página espera conservarlas.
        val fixed = rows.mapNotNull { row -> row.choice?.takeIf { row.disabled && it.selected } }
        val checked = BooleanArray(selectable.size) { selectable[it].choice?.selected == true }
        val labels = selectable.map { "  ".repeat(it.depth) + it.label }.toTypedArray()

        val dialog = AlertDialog.Builder(activity)
            .setTitle(prompt.title?.takeIf { it.isNotBlank() } ?: "Selecciona opciones")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancelar") { _, _ -> pending.dismiss() }
            .setPositiveButton("Aceptar") { _, _ ->
                val chosen = selectable.filterIndexed { index, _ -> checked[index] }.mapNotNull { it.choice } + fixed
                pending.finish { prompt.confirm(chosen.toTypedArray()) }
            }
            .create()
        return present(pending, dialog)
    }

    private fun singleChoice(
        activity: Activity,
        prompt: PromptDelegate.ChoicePrompt,
        rows: List<ChoiceRow>
    ): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        val p = ThemeManager.palette(activity)
        var sheet: Dialog? = null
        var selectedView: View? = null

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8f), 0, dp(activity, 8f), 0)
        }

        rows.forEach { row ->
            when {
                row.separator -> list.addView(
                    View(activity).apply { setBackgroundColor(p.outline) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1f).coerceAtLeast(1)).apply {
                        setMargins(dp(activity, 16f), dp(activity, 6f), dp(activity, 16f), dp(activity, 6f))
                    }
                )

                row.header -> list.addView(TextView(activity).apply {
                    text = row.label
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(p.muted)
                    alpha = if (row.disabled) 0.5f else 1f
                    setPadding(dp(activity, 16f + row.depth * 12f), dp(activity, 12f), dp(activity, 16f), dp(activity, 4f))
                })

                else -> {
                    val choice = row.choice ?: return@forEach
                    val selected = choice.selected
                    val item = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(activity, 50f)
                        setPadding(dp(activity, 16f + row.depth * 12f), 0, dp(activity, 16f), 0)
                        background = ThemeManager.ripple(
                            activity,
                            if (selected) ThemeManager.rounded(activity, p.accentSoft, 14f) else null,
                            14f
                        )
                        isClickable = !row.disabled
                        isEnabled = !row.disabled
                        alpha = if (row.disabled) 0.45f else 1f
                        contentDescription = row.label
                        setOnClickListener {
                            pending.finish { prompt.confirm(choice) }
                            sheet?.dismiss()
                        }
                    }
                    item.addView(TextView(activity).apply {
                        text = row.label.ifBlank { " " }
                        textSize = 15f
                        setTextColor(if (selected) p.accent else p.text)
                        if (selected) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (selected) {
                        item.addView(ImageView(activity).apply {
                            setImageResource(R.drawable.ic_check)
                            imageTintList = android.content.res.ColorStateList.valueOf(p.accent)
                        }, LinearLayout.LayoutParams(dp(activity, 22f), dp(activity, 22f)).apply {
                            marginStart = dp(activity, 12f)
                        })
                        selectedView = item
                    }
                    list.addView(item, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                }
            }
        }

        val content = NexoSheets.column(
            activity,
            NexoSheets.header(activity, prompt.title?.takeIf { it.isNotBlank() } ?: "Selecciona una opción"),
            list
        )
        val dialog = NexoSheets.show(activity, content)
        sheet = dialog
        attach(pending, dialog)

        // En listas largas, mostrar directamente la opción elegida.
        selectedView?.let { view ->
            view.post { view.requestRectangleOnScreen(Rect(0, -dp(activity, 48f), view.width, view.height * 2), true) }
        }
        return pending.result
    }

    // ---------------------------------------------------------------
    // Fecha y hora
    // ---------------------------------------------------------------

    private fun parseDateTime(type: Int, value: String?): LocalDateTime? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching {
            when (type) {
                PromptDelegate.DateTimePrompt.Type.DATE -> LocalDate.parse(raw).atStartOfDay()
                PromptDelegate.DateTimePrompt.Type.MONTH -> YearMonth.parse(raw).atDay(1).atStartOfDay()
                PromptDelegate.DateTimePrompt.Type.WEEK -> {
                    val (year, week) = raw.uppercase(Locale.US).split("-W").map { it.toInt() }
                    LocalDate.of(year, 6, 1)
                        .with(IsoFields.WEEK_BASED_YEAR, year.toLong())
                        .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
                        .with(DayOfWeek.MONDAY)
                        .atStartOfDay()
                }
                PromptDelegate.DateTimePrompt.Type.TIME -> LocalTime.parse(raw).atDate(LocalDate.now())
                else -> LocalDateTime.parse(raw)
            }
        }.getOrNull()
    }

    private fun epochMillis(value: LocalDateTime): Long =
        value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun formatDate(type: Int, date: LocalDate): String =
        when (type) {
            PromptDelegate.DateTimePrompt.Type.MONTH ->
                String.format(Locale.US, "%04d-%02d", date.year, date.monthValue)
            PromptDelegate.DateTimePrompt.Type.WEEK ->
                String.format(
                    Locale.US,
                    "%04d-W%02d",
                    date.get(IsoFields.WEEK_BASED_YEAR),
                    date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                )
            else -> String.format(Locale.US, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
        }

    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.DateTimePrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return dismissed(prompt)
        val pending = Pending(prompt)
        val type = prompt.type
        val initial = parseDateTime(type, prompt.defaultValue) ?: LocalDateTime.now()
        val is24h = DateFormat.is24HourFormat(activity)

        fun timeDialog(onTime: (Int, Int) -> Unit) =
            TimePickerDialog(activity, { _, hour, minute -> onTime(hour, minute) }, initial.hour, initial.minute, is24h)

        if (type == PromptDelegate.DateTimePrompt.Type.TIME) {
            val dialog = timeDialog { hour, minute ->
                pending.finish { prompt.confirm(String.format(Locale.US, "%02d:%02d", hour, minute)) }
            }
            dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "Borrar") { _, _ -> pending.finish { prompt.confirm("") } }
            return present(pending, dialog)
        }

        var chaining = false
        val dialog = DatePickerDialog(
            activity,
            { _, year, month, day ->
                val date = LocalDate.of(year, month + 1, day)
                if (type == PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) {
                    // Fecha elegida: pedir ahora la hora.
                    chaining = true
                    val time = timeDialog { hour, minute ->
                        pending.finish {
                            prompt.confirm(
                                String.format(
                                    Locale.US, "%04d-%02d-%02dT%02d:%02d",
                                    date.year, date.monthValue, date.dayOfMonth, hour, minute
                                )
                            )
                        }
                    }
                    attach(pending, time)
                    runCatching {
                        time.show()
                        roundCorners(time)
                    }.onFailure { pending.dismiss() }
                } else {
                    pending.finish { prompt.confirm(formatDate(type, date)) }
                }
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        )
        val dateType = if (type == PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) {
            PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL
        } else type
        parseDateTime(dateType, prompt.minValue)?.let { min ->
            runCatching { dialog.datePicker.minDate = epochMillis(min.toLocalDate().atStartOfDay()) }
        }
        parseDateTime(dateType, prompt.maxValue)?.let { max ->
            runCatching { dialog.datePicker.maxDate = epochMillis(max.toLocalDate().atTime(23, 59)) }
        }
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "Borrar") { _, _ -> pending.finish { prompt.confirm("") } }

        attach(pending, dialog, dismissOnClose = false)
        dialog.setOnDismissListener { if (!chaining) pending.dismiss() }
        runCatching {
            dialog.show()
            roundCorners(dialog)
        }.onFailure { pending.dismiss() }
        return pending.result
    }

    // ---------------------------------------------------------------
    // Color
    // ---------------------------------------------------------------

    private val defaultColors = listOf(
        "#000000", "#434343", "#999999", "#ffffff", "#ef4444", "#f97316",
        "#f59e0b", "#eab308", "#84cc16", "#22c55e", "#14b8a6", "#06b6d4",
        "#3b82f6", "#6366f1", "#7c3aed", "#a855f7", "#ec4899", "#f43f5e",
        "#7f1d1d", "#78350f", "#14532d", "#164e63", "#1e3a8a", "#4c1d95"
    )

    private fun normalizeColor(value: String?): String? =
        runCatching {
            val color = Color.parseColor(value?.trim())
            String.format(Locale.US, "#%06x", color and 0xFFFFFF)
        }.getOrNull()

    override fun onColorPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ColorPrompt
    ): GeckoResult<PromptResponse>? {
        val activity = activity() ?: return dismissed(prompt)
        val pending = Pending(prompt)
        val p = ThemeManager.palette(activity)
        val current = normalizeColor(prompt.defaultValue) ?: "#000000"
        val colors = (prompt.predefinedValues?.mapNotNull { normalizeColor(it) }?.takeIf { it.isNotEmpty() }
            ?: defaultColors).let { if (current in it) it else listOf(current) + it }.distinct()
        var sheet: Dialog? = null

        fun choose(hex: String) {
            pending.finish { prompt.confirm(hex) }
            sheet?.dismiss()
        }

        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16f), 0, dp(activity, 16f), 0)
        }
        colors.chunked(6).forEach { rowColors ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 6f
            }
            rowColors.forEach { hex ->
                val swatch = FrameLayout(activity).apply {
                    setPadding(dp(activity, 6f), dp(activity, 6f), dp(activity, 6f), dp(activity, 6f))
                    contentDescription = hex
                    isClickable = true
                    background = ThemeManager.ripple(activity, null, 24f)
                    setOnClickListener { choose(hex) }
                    addView(View(activity).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor(hex))
                            setStroke(
                                dp(activity, if (hex == current) 3f else 1f),
                                if (hex == current) p.accent else p.outline
                            )
                        }
                    }, FrameLayout.LayoutParams(dp(activity, 40f), dp(activity, 40f), Gravity.CENTER))
                }
                row.addView(swatch, LinearLayout.LayoutParams(0, dp(activity, 54f), 1f))
            }
            grid.addView(row)
        }

        val hexInput = field(activity, current, "#rrggbb")
        val apply = android.widget.Button(activity).apply {
            text = "Usar"
            ThemeManager.styleButton(activity, this, primary = true)
            setOnClickListener {
                normalizeColor(hexInput.text?.toString())?.let { choose(it) }
                    ?: run { hexInput.error = "Color no válido" }
            }
        }
        val custom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(activity, 24f), dp(activity, 12f), dp(activity, 24f), 0)
            addView(hexInput, LinearLayout.LayoutParams(0, dp(activity, 48f), 1f))
            addView(apply, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48f)).apply {
                marginStart = dp(activity, 10f)
            })
        }

        val dialog = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, "Elegir color"), grid, custom)
        )
        sheet = dialog
        attach(pending, dialog)
        return pending.result
    }

    // ---------------------------------------------------------------
    // Archivos
    // ---------------------------------------------------------------

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.FilePrompt
    ): GeckoResult<PromptResponse>? {
        val host = BrowserHosts.current() ?: return dismissed(prompt)
        if (prompt.type == PromptDelegate.FilePrompt.Type.FOLDER) return dismissed(prompt)

        val pending = Pending(prompt)
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        prompt.setDelegate(object : PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) = pending.abandon()
        })
        host.pickFiles(prompt.mimeTypes?.toList().orEmpty(), multiple) { uris ->
            if (uris.isEmpty()) {
                pending.dismiss()
            } else {
                val context = host.hostActivity
                pending.finish {
                    if (multiple) prompt.confirm(context, uris.toTypedArray())
                    else prompt.confirm(context, uris.first())
                }
            }
        }
        return pending.result
    }
}
