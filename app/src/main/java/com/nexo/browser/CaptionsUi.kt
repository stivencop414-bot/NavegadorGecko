package com.nexo.browser

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Botón CC, capa de subtítulos sobre el video y hoja de configuración. */
object CaptionsUi {
    val LANGUAGES = listOf(
        "multi" to "Automático", "es" to "Español", "en" to "English", "pt" to "Português",
        "fr" to "Français", "de" to "Deutsch", "it" to "Italiano", "nl" to "Nederlands", "pl" to "Polski",
        "ru" to "Русский", "uk" to "Українська", "tr" to "Türkçe", "hi" to "हिन्दी", "ja" to "日本語",
        "ko" to "한국어", "zh" to "中文", "id" to "Bahasa Indonesia", "vi" to "Tiếng Việt", "sv" to "Svenska"
    )
    /** Destino: "No traducir" y los 37 idiomas del traductor. */
    val TARGETS: List<Pair<String, String>> =
        listOf("" to "No traducir") + CaptionProtocol.TARGET_LANGUAGES.map { it.code to it.label }
    /** Los primeros que se ven como píldoras; el resto en "Más idiomas". */
    private val QUICK_TARGETS = listOf("es", "en", "pt", "fr", "de", "it", "ja", "zh")
    private val SIZES = listOf(11 to "Mínimo", 13 to "Pequeño", 16 to "Normal", 19 to "Grande", 22 to "Muy grande")

    // Estilo ya aplicado al texto: solo se cambia lo que cambió (cada cambio de fondo,
    // tamaño o líneas obliga a recolocar la pantalla, y eso con video resta fluidez).
    private var appliedStyle: String? = null
    private val backgrounds = HashMap<String, GradientDrawable>()

    private fun dp(activity: Activity, value: Float) = (value * activity.resources.displayMetrics.density).toInt()

    /** Píldora translúcida como la de "Mini"; con el acento cuando está activa. */
    fun styleChip(activity: Activity, button: Button, active: Boolean) {
        val p = ThemeManager.palette(activity)
        val density = activity.resources.displayMetrics.density
        button.background = ThemeManager.ripple(
            activity,
            GradientDrawable().apply {
                setColor(if (active) p.accent else 0xE6111827.toInt())
                cornerRadius = 24f * density
                setStroke(density.toInt().coerceAtLeast(1), if (active) p.accent else 0x33FFFFFF)
            },
            24f,
            0x40FFFFFF
        )
        button.backgroundTintList = null
        button.setTextColor(if (active) p.onAccent else Color.WHITE)
        button.stateListAnimator = null
        button.elevation = 8f * density
        button.contentDescription = if (active) "Desactivar subtítulos en vivo" else "Activar subtítulos en vivo"
    }

    /**
     * Coloca el texto sobre la parte baja del video. Si no se conoce su
     * posición (o en la ventana mini), al pie de la página.
     */
    fun render(
        activity: Activity,
        view: TextView,
        frameWidth: Int,
        frameHeight: Int,
        videoRect: Rect?,
        display: CaptionDisplay?,
        pip: Boolean,
        hint: Boolean = false
    ) {
        if (display == null || display.isEmpty || frameWidth <= 0 || frameHeight <= 0) {
            view.visibility = View.GONE
            return
        }
        val size = BrowserPrefs.captionSize(activity).toFloat()
        val background = if (hint) "hint" else BrowserPrefs.captionBackground(activity)
        val color = if (BrowserPrefs.captionColor(activity) == "yellow" && !hint) 0xFFFFE45C.toInt() else Color.WHITE
        val lines = BrowserPrefs.captionLines(activity)
        val maxLines = if (display.secondary.isNotBlank()) lines + 2 else lines
        val textSize = when {
            hint -> (size * 0.8f).coerceAtLeast(12f)
            pip -> (size * 0.72f).coerceAtLeast(10f)
            else -> size
        }
        val style = "$textSize|$background|$color|$maxLines|$hint"
        if (style != appliedStyle || view.tag !== CaptionsUi) {
            appliedStyle = style
            view.tag = CaptionsUi
            view.textSize = textSize
            // El aviso de estado se distingue del subtítulo: cursiva y fondo más ligero.
            view.typeface = Typeface.create("sans-serif-medium", if (hint) Typeface.ITALIC else Typeface.NORMAL)
            view.setTextColor(color)
            // Sin fondo, un contorno oscuro marcado mantiene el texto legible sobre cualquier imagen.
            if (background == "none") view.setShadowLayer(dp(activity, 2.5f).toFloat(), 0f, 0f, 0xF0000000.toInt())
            else view.setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
            view.background = if (background == "none") null else backgrounds.getOrPut(background) {
                GradientDrawable().apply {
                    setColor(when (background) { "hint" -> 0x99000000.toInt(); "soft" -> 0x66000000; else -> 0xB8000000.toInt() })
                    cornerRadius = dp(activity, 10f).toFloat()
                }
            }
            view.maxLines = maxLines
        }
        val text = SpannableStringBuilder()
        if (display.primary.isNotBlank()) text.append(display.primary)
        if (display.pending.isNotBlank()) {
            if (text.isNotEmpty()) text.append(' ')
            val start = text.length
            text.append(display.pending)
            // Lo que aún se está transcribiendo o traduciendo, algo más tenue.
            text.setSpan(ForegroundColorSpan(0xC8E5E7EB.toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (display.secondary.isNotBlank() && !pip) {
            // Modo "ambos": el original debajo, más pequeño.
            text.append('\n')
            val start = text.length
            text.append(display.secondary)
            text.setSpan(ForegroundColorSpan(0xB3CBD5E1.toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text

        val margin = dp(activity, 8f)
        val useVideo = !pip && videoRect != null && videoRect.width() > dp(activity, 160f) && videoRect.height() > dp(activity, 90f)
        val area = if (useVideo) Rect(videoRect).apply { intersect(0, 0, frameWidth, frameHeight) }
        else Rect(0, 0, frameWidth, frameHeight - if (pip) 0 else dp(activity, 76f))
        val maxWidth = (area.width() - margin * 2).coerceAtLeast(dp(activity, 120f))
        view.maxWidth = maxWidth
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val width = view.measuredWidth
        val height = view.measuredHeight
        val maxX = (frameWidth - width - margin).coerceAtLeast(margin)
        view.translationX = (area.centerX() - width / 2f).coerceIn(margin.toFloat(), maxX.toFloat())
        view.translationY = (area.bottom - height - dp(activity, if (pip) 6f else 14f)).toFloat()
            .coerceAtLeast((area.top + margin).toFloat())
        view.visibility = View.VISIBLE
    }

    /** Píldoras de idioma de destino: los habituales, el elegido y "Más idiomas…". */
    private fun targetChips(activity: Activity, selected: String, onPick: (String) -> Unit): View {
        // El idioma elegido siempre a la vista, al principio de la fila.
        val codes = (listOf(selected) + listOf("") + QUICK_TARGETS).distinct()
        val chips = codes.map { code ->
            NexoUi.chip(activity, if (code.isBlank()) "No traducir" else CaptionProtocol.languageLabel(code), code == selected) { onPick(code) }
        } + NexoUi.chip(activity, "Más idiomas…", false) { pickLanguage(activity, selected, onPick) }
        return NexoUi.chipRow(activity, chips)
    }

    /** Lista completa de idiomas de destino, con el elegido marcado. */
    fun pickLanguage(activity: Activity, selected: String, onPick: (String) -> Unit) {
        val p = ThemeManager.palette(activity)
        lateinit var sheet: Dialog
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12f), 0, dp(activity, 12f), dp(activity, 8f))
        }
        for ((code, label) in TARGETS) {
            val chosen = code == selected
            list.addView(TextView(activity).apply {
                text = (if (chosen) "✓  " else "") + label
                textSize = 15.5f
                setTextColor(if (chosen) (if (p.isDark) p.text else p.accent) else p.text)
                typeface = if (chosen) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                minHeight = dp(activity, 48f)
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                // Árabe o hebreo también alineados con el resto de la lista.
                textDirection = View.TEXT_DIRECTION_LOCALE
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(dp(activity, 14f), 0, dp(activity, 14f), 0)
                background = ThemeManager.ripple(activity, if (chosen) ThemeManager.rounded(activity, p.accentSoft, 14f) else null, 14f)
                isClickable = true
                setOnClickListener { sheet.dismiss(); onPick(code) }
            })
        }
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Traducir a", "${TARGETS.size - 1} idiomas"), list))
    }

    private fun stateText(activity: Activity, session: org.mozilla.geckoview.GeckoSession): String = when {
        LiveCaptions.isTrackMode(session) -> "Subtítulos del propio video · sin consumo de Deepgram"
        LiveCaptions.isDomMode(session) -> "Subtítulos del reproductor · sin consumo de Deepgram"
        LiveCaptions.state(session) == LiveCaptions.State.LISTENING -> "Escuchando · " + LiveCaptions.usageText(activity)
        LiveCaptions.state(session) == LiveCaptions.State.CONNECTING -> "Conectando con Deepgram…"
        LiveCaptions.state(session) == LiveCaptions.State.ERROR -> LiveCaptions.message(session) ?: "Error"
        else -> "En espera: empieza al reproducir el video"
    }

    /**
     * Hoja rápida con los subtítulos activos: idioma de destino, qué mostrar
     * e idioma del video. Los cambios se aplican al momento, sin cortar el audio.
     */
    fun showQuick(activity: Activity, session: org.mozilla.geckoview.GeckoSession, onChange: () -> Unit, onOff: () -> Unit) {
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        val targetRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val sourceRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderTarget() {
            targetRow.removeAllViews()
            targetRow.addView(targetChips(activity, BrowserPrefs.captionTranslateTo(activity)) { code ->
                BrowserPrefs.setCaptionTranslateTo(activity, code)
                if (code.isNotBlank() && BrowserPrefs.captionMode(activity) == "original") BrowserPrefs.setCaptionMode(activity, "translated")
                LiveCaptions.retarget(activity, session)
                renderTarget(); onChange()
            })
            modeRow.visibility = if (BrowserPrefs.captionTranslateTo(activity).isBlank()) View.GONE else View.VISIBLE
        }
        fun renderMode() {
            modeRow.removeAllViews()
            modeRow.addView(NexoUi.section(activity, "Mostrar"))
            modeRow.addView(NexoUi.chipRow(activity, listOf("translated" to "Traducido", "both" to "Ambos", "original" to "Original").map { (value, label) ->
                NexoUi.chip(activity, label, BrowserPrefs.captionMode(activity) == value) {
                    BrowserPrefs.setCaptionMode(activity, value)
                    LiveCaptions.retarget(activity, session)
                    renderMode(); onChange()
                }
            }))
        }
        fun renderSource() {
            sourceRow.removeAllViews()
            sourceRow.addView(NexoUi.chipRow(activity, LANGUAGES.map { (value, label) ->
                NexoUi.chip(activity, label, BrowserPrefs.captionLanguage(activity) == value) {
                    BrowserPrefs.setCaptionLanguage(activity, value)
                    LiveCaptions.relisten(activity, session)
                    renderSource(); onChange()
                }
            }))
        }
        // Diagnóstico por etapas: dónde se corta (reproductor, pista, señal, Deepgram, texto).
        LiveCaptions.stages(session).takeIf { it.isNotEmpty() }?.let { stages ->
            body.addView(NexoUi.section(activity, "Estado"))
            body.addView(NexoUi.note(activity, stages.joinToString("\n") { (label, ok) ->
                (when (ok) { true -> "✓ "; false -> "✗ "; null -> "… " }) + label
            } + (LiveCaptions.aiTranslationWinner(activity)?.let { "\n✓ Traducción IA: $it" } ?: "")))
        }
        body.addView(NexoUi.section(activity, "Traducir a"))
        body.addView(targetRow)
        body.addView(modeRow)
        renderMode(); renderTarget()
        if (!LiveCaptions.isTrackMode(session) && !LiveCaptions.isDomMode(session)) {
            body.addView(NexoUi.section(activity, "Idioma del video"))
            body.addView(sourceRow)
            renderSource()
        }
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Más ajustes", "Claves, servicio de traducción, tamaño y límite") {
            sheet.dismiss(); showSheet(activity) { onChange() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 10f)
        })
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Subtítulos instantáneos de Android",
            "Generados en el teléfono, sin enviar nada · funcionan con audífonos") {
            sheet.dismiss(); SystemCaptions.openSystemSettings(activity)
        })
        if (activity is MainActivity && !LiveCaptions.isTrackMode(session) && !LiveCaptions.isDomMode(session) &&
            !LiveCaptions.audioAllowed(session) && BrowserPrefs.deepgramKey(activity).isNotBlank()
        ) {
            body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_play, "Transcribir el audio de este video",
                "Se envía el audio del video a Deepgram (no el micrófono)") {
                sheet.dismiss(); LiveCaptions.allowAudio(activity, session); onChange()
            })
        }
        if (activity is MainActivity && android.os.Build.VERSION.SDK_INT >= 29) {
            body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Capturar audio con Android",
                "Si la página no da su audio · capta el audio multimedia del teléfono (puede incluir otras apps), con tu autorización") {
                sheet.dismiss(); PlaybackAudioCapture.request(activity)
            })
        }
        body.addView(NexoUi.button(activity, "Apagar subtítulos", danger = true) { sheet.dismiss(); onOff() }, NexoUi.buttonParams(activity, 8f))
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Subtítulos en vivo", stateText(activity, session)), body))
    }

    /** Hoja de configuración: clave, idioma, traducción y tamaño. */
    fun showSheet(activity: Activity, askKey: Boolean = false, onSaved: () -> Unit = {}) {
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        var language = BrowserPrefs.captionLanguage(activity)
        var target = BrowserPrefs.captionTranslateTo(activity)
        var provider = BrowserPrefs.translationProvider(activity)
        var size = BrowserPrefs.captionSize(activity)
        var mode = BrowserPrefs.captionMode(activity)
        var limit = BrowserPrefs.captionDailyLimit(activity)

        body.addView(NexoUi.section(activity, "Deepgram"))
        val (keyView, keyField) = NexoUi.field(activity, "Clave API de Deepgram", "Pega tu clave", BrowserPrefs.deepgramKey(activity))
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        body.addView(keyView)
        body.addView(NexoUi.note(activity, "Nexo usa primero texto: la pista del video, los subtítulos del reproductor (YouTube, Netflix…) o el archivo .vtt/.srt que descarga la página. Nunca usa el micrófono. Deepgram (en la nube, con tu cuenta) solo transcribe el audio del video si tú lo eliges."))
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_link, "Crear una clave en deepgram.com", "Se abre en una pestaña nueva") {
            sheet.dismiss()
            TabManager.createTab("https://console.deepgram.com/signup", false, true)
            if (activity !is MainActivity) activity.finish()
        })

        fun chips(holder: LinearLayout, options: List<Pair<String, String>>, selected: () -> String, onPick: (String) -> Unit) {
            holder.removeAllViews()
            holder.addView(NexoUi.chipRow(activity, options.map { (value, label) ->
                NexoUi.chip(activity, label, selected() == value) { onPick(value); chips(holder, options, selected, onPick) }
            }))
        }
        body.addView(NexoUi.section(activity, "Idioma del video"))
        val languageRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(languageRow, LANGUAGES, { language }) { language = it }
        body.addView(languageRow)

        body.addView(NexoUi.section(activity, "Traducir a"))
        val targetRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val translateBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val providerRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (deeplView, deeplField) = NexoUi.field(activity, "Clave API de DeepL", "Termina en :fx si es gratuita", BrowserPrefs.deeplKey(activity))
        deeplField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        // Traducción con un modelo de IA: lotes grandes y pocas peticiones.
        val aiBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val aiProviderRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var aiProvider = BrowserPrefs.aiTranslateProvider(activity)
        val (aiModelView, aiModelField) = NexoUi.field(activity, "Modelo", CaptionProtocol.AI_MODELS[aiProvider] ?: "Nombre del modelo", BrowserPrefs.aiTranslateModel(activity))
        val (aiEndpointView, aiEndpointField) = NexoUi.field(activity, "Dirección de la API (compatible con OpenAI)", "https://servidor/v1", BrowserPrefs.aiTranslateEndpoint(activity))
        val (aiKeyView, aiKeyField) = NexoUi.field(activity, "Clave API del modelo", "Se guarda cifrada en el teléfono", BrowserPrefs.aiTranslateKey(activity))
        aiKeyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        aiBox.addView(NexoUi.section(activity, "Modelo de IA"))
        aiBox.addView(aiProviderRow)
        aiBox.addView(aiModelView); aiBox.addView(aiEndpointView); aiBox.addView(aiKeyView)
        aiBox.addView(NexoUi.note(activity, "Traduce varias frases en una sola petición, con el contexto anterior. Con subtítulos propios del video, traduce todo por adelantado."))
        fun refreshProvider() {
            translateBox.visibility = if (target.isBlank()) View.GONE else View.VISIBLE
            deeplView.visibility = if (provider == BrowserPrefs.TRANSLATE_DEEPL) View.VISIBLE else View.GONE
            aiBox.visibility = if (provider == BrowserPrefs.TRANSLATE_AI) View.VISIBLE else View.GONE
            aiEndpointView.visibility = if (aiProvider == "custom") View.VISIBLE else View.GONE
            aiModelField.hint = CaptionProtocol.AI_MODELS[aiProvider] ?: "Nombre del modelo"
        }
        fun renderTargets() {
            targetRow.removeAllViews()
            targetRow.addView(targetChips(activity, target, { target = it; renderTargets(); refreshProvider() }))
        }
        renderTargets()
        body.addView(targetRow)
        translateBox.addView(NexoUi.section(activity, "Mostrar"))
        chips(modeRow, listOf("translated" to "Traducido", "both" to "Ambos", "original" to "Original"), { mode }) { mode = it }
        translateBox.addView(modeRow)
        translateBox.addView(NexoUi.section(activity, "Servicio de traducción"))
        chips(providerRow, listOf(
            BrowserPrefs.TRANSLATE_GOOGLE to "Google (sin clave)",
            BrowserPrefs.TRANSLATE_DEEPL to "DeepL",
            BrowserPrefs.TRANSLATE_AI to "Modelo de IA"
        ), { provider }) { provider = it; refreshProvider() }
        translateBox.addView(providerRow)
        translateBox.addView(deeplView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 8f)
        })
        chips(aiProviderRow, CaptionProtocol.AI_PROVIDERS, { aiProvider }) {
            aiProvider = it; refreshProvider()
        }
        translateBox.addView(aiBox)
        // Segundo modelo (otra API o proveedor): se piden los dos a la vez y gana el primero que responde.
        val ai2Box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val ai2Fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var ai2Enabled = BrowserPrefs.aiTranslate2Enabled(activity)
        var ai2Provider = BrowserPrefs.aiTranslate2Provider(activity)
        val ai2ProviderRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (ai2ModelView, ai2ModelField) = NexoUi.field(activity, "Modelo 2", CaptionProtocol.AI_MODELS[ai2Provider] ?: "Nombre del modelo", BrowserPrefs.aiTranslate2Model(activity))
        val (ai2EndpointView, ai2EndpointField) = NexoUi.field(activity, "Dirección de la API 2 (compatible con OpenAI)", "https://servidor/v1", BrowserPrefs.aiTranslate2Endpoint(activity))
        val (ai2KeyView, ai2KeyField) = NexoUi.field(activity, "Clave API del modelo 2", "Se guarda cifrada en el teléfono", BrowserPrefs.aiTranslate2Key(activity))
        ai2KeyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        fun refreshAi2() {
            ai2Fields.visibility = if (ai2Enabled) View.VISIBLE else View.GONE
            ai2EndpointView.visibility = if (ai2Provider == "custom") View.VISIBLE else View.GONE
            ai2ModelField.hint = CaptionProtocol.AI_MODELS[ai2Provider] ?: "Nombre del modelo"
        }
        ai2Box.addView(NexoUi.switchRow(activity, "Segundo modelo en paralelo",
            "Se pregunta a los dos modelos a la vez y se usa el que responda primero. Si uno falla o se queda sin saldo, el otro sigue traduciendo. Gasta en ambos.", ai2Enabled) {
            ai2Enabled = it; refreshAi2()
        })
        chips(ai2ProviderRow, CaptionProtocol.AI_PROVIDERS, { ai2Provider }) { ai2Provider = it; refreshAi2() }
        ai2Fields.addView(ai2ProviderRow)
        ai2Fields.addView(ai2ModelView); ai2Fields.addView(ai2EndpointView); ai2Fields.addView(ai2KeyView)
        ai2Box.addView(ai2Fields)
        aiBox.addView(ai2Box)
        refreshAi2()
        translateBox.addView(NexoUi.note(activity, "Se traducen frases completas, agrupadas, al momento y sin descargar idiomas."))
        body.addView(translateBox)
        refreshProvider()

        var audioAuto = BrowserPrefs.captionAudioAuto(activity)
        body.addView(NexoUi.switchRow(activity, "Transcribir el audio sin preguntar",
            "Si el video no trae subtítulos de texto, enviar su audio a Deepgram directamente. Apagado: Nexo pregunta antes.", audioAuto) {
            audioAuto = it
        })
        var friendlyAudio = BrowserPrefs.captureFriendlyAudio(activity)
        body.addView(NexoUi.switchRow(activity, "Audio compatible con la captura de Android",
            "Los reproductores con Web Audio suenan por la vía que Android puede capturar (~0,1 s más de retardo en juegos web). Apágalo para comparar si la captura no lo necesita.", friendlyAudio) {
            friendlyAudio = it
        })
        body.addView(NexoUi.section(activity, "Límite diario de Deepgram"))
        val limitRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(limitRow, listOf("30" to "30 min", "60" to "1 h", "120" to "2 h", "240" to "4 h", "0" to "Sin límite"), { limit.toString() }) { limit = it.toInt() }
        body.addView(limitRow)
        body.addView(NexoUi.note(activity, LiveCaptions.usageText(activity) + ". Los videos con subtítulos propios no lo consumen."))

        body.addView(NexoUi.section(activity, "Diseño · tamaño del texto"))
        val sizeRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(sizeRow, SIZES.map { it.first.toString() to it.second }, { size.toString() }) { size = it.toInt() }
        body.addView(sizeRow)
        var background = BrowserPrefs.captionBackground(activity)
        body.addView(NexoUi.section(activity, "Fondo"))
        val backgroundRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(backgroundRow, listOf("solid" to "Oscuro", "soft" to "Suave", "none" to "Sin fondo"), { background }) { background = it }
        body.addView(backgroundRow)
        var color = BrowserPrefs.captionColor(activity)
        body.addView(NexoUi.section(activity, "Color del texto"))
        val colorRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(colorRow, listOf("white" to "Blanco", "yellow" to "Amarillo"), { color }) { color = it }
        body.addView(colorRow)
        var lines = BrowserPrefs.captionLines(activity)
        body.addView(NexoUi.section(activity, "Longitud"))
        val linesRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(linesRow, listOf("1" to "Corta · 1 línea", "2" to "Media · 2 líneas", "3" to "Larga · 3 líneas"), { lines.toString() }) { lines = it.toInt() }
        body.addView(linesRow)
        var clearSeconds = BrowserPrefs.captionClearSeconds(activity)
        body.addView(NexoUi.section(activity, "Borrar al dejar de hablar"))
        val clearRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(clearRow, listOf("2" to "2 s", "3" to "3 s", "5" to "5 s", "8" to "8 s"), { clearSeconds.toString() }) { clearSeconds = it.toInt() }
        body.addView(clearRow)
        body.addView(NexoUi.note(activity, "Tras ese silencio el último subtítulo desaparece, para no tapar el video."))

        val error = TextView(activity).apply {
            textSize = 12.5f; setTextColor(ThemeManager.palette(activity).danger); visibility = View.GONE
        }
        body.addView(error)
        body.addView(NexoUi.button(activity, "Guardar", primary = true) {
            val key = keyField.text.toString().trim()
            if (key.isNotEmpty() && !key.matches(Regex("[A-Za-z0-9_\\-.]{20,200}"))) {
                error.text = "La clave de Deepgram no parece válida."; error.visibility = View.VISIBLE; return@button
            }
            if (target.isNotBlank() && provider == BrowserPrefs.TRANSLATE_DEEPL && deeplField.text.isBlank()) {
                error.text = "Escribe la clave de DeepL o elige Google."; error.visibility = View.VISIBLE; return@button
            }
            if (target.isNotBlank() && provider == BrowserPrefs.TRANSLATE_AI) {
                val endpoint = aiEndpointField.text.toString().trim()
                if (aiProvider == "custom" && !CaptionProtocol.isSafeKeyedEndpoint(endpoint)) {
                    error.text = "La dirección de la API debe ser https:// (o http:// solo hacia este teléfono), sin usuario ni contraseña."
                    error.visibility = View.VISIBLE; return@button
                }
                if (aiProvider != "custom" && aiKeyField.text.isBlank()) {
                    error.text = "Escribe la clave del modelo de IA."; error.visibility = View.VISIBLE; return@button
                }
                if (ai2Enabled) {
                    if (ai2Provider == "custom" && !CaptionProtocol.isSafeKeyedEndpoint(ai2EndpointField.text.toString().trim())) {
                        error.text = "Modelo 2: la dirección de la API debe ser https:// (o http:// solo hacia este teléfono)."
                        error.visibility = View.VISIBLE; return@button
                    }
                    if (ai2Provider != "custom" && ai2KeyField.text.isBlank()) {
                        error.text = "Escribe la clave del modelo 2 o apaga el segundo modelo."; error.visibility = View.VISIBLE; return@button
                    }
                }
            }
            BrowserPrefs.setDeepgramKey(activity, key)
            BrowserPrefs.setCaptionLanguage(activity, language)
            BrowserPrefs.setCaptionTranslateTo(activity, target)
            BrowserPrefs.setTranslationProvider(activity, provider)
            BrowserPrefs.setDeeplKey(activity, deeplField.text.toString())
            BrowserPrefs.setCaptionSize(activity, size)
            BrowserPrefs.setCaptionBackground(activity, background)
            BrowserPrefs.setCaptionColor(activity, color)
            BrowserPrefs.setCaptionLines(activity, lines)
            BrowserPrefs.setCaptionClearSeconds(activity, clearSeconds)
            BrowserPrefs.setCaptionMode(activity, mode)
            BrowserPrefs.setCaptionDailyLimit(activity, limit)
            BrowserPrefs.setCaptionAudioAuto(activity, audioAuto)
            if (friendlyAudio != BrowserPrefs.captureFriendlyAudio(activity)) {
                BrowserPrefs.setCaptureFriendlyAudio(activity, friendlyAudio)
                GeckoRuntimeHolder.applyAudioPrefs(activity)
            }
            BrowserPrefs.setAiTranslateProvider(activity, aiProvider)
            BrowserPrefs.setAiTranslateModel(activity, aiModelField.text.toString())
            BrowserPrefs.setAiTranslateEndpoint(activity, if (aiProvider == "custom") aiEndpointField.text.toString() else "")
            BrowserPrefs.setAiTranslateKey(activity, aiKeyField.text.toString())
            BrowserPrefs.setAiTranslate2Enabled(activity, ai2Enabled)
            BrowserPrefs.setAiTranslate2Provider(activity, ai2Provider)
            BrowserPrefs.setAiTranslate2Model(activity, ai2ModelField.text.toString())
            BrowserPrefs.setAiTranslate2Endpoint(activity, if (ai2Provider == "custom") ai2EndpointField.text.toString() else "")
            BrowserPrefs.setAiTranslate2Key(activity, ai2KeyField.text.toString())
            sheet.dismiss()
            onSaved()
        }, NexoUi.buttonParams(activity, 14f))

        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, "Subtítulos en vivo", "Transcripción y traducción del video"), body))
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (askKey) keyField.requestFocus()
    }
}
