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
    private val SIZES = listOf(14 to "Pequeño", 16 to "Normal", 19 to "Grande", 22 to "Muy grande")

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
        view.textSize = when {
            hint -> (size * 0.8f).coerceAtLeast(12f)
            pip -> (size * 0.72f).coerceAtLeast(11f)
            else -> size
        }
        // El aviso de estado se distingue del subtítulo: cursiva y fondo más ligero.
        view.typeface = Typeface.create("sans-serif-medium", if (hint) Typeface.ITALIC else Typeface.NORMAL)
        view.setTextColor(Color.WHITE)
        view.setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
        view.background = GradientDrawable().apply {
            setColor(if (hint) 0x99000000.toInt() else 0xB8000000.toInt())
            cornerRadius = dp(activity, 10f).toFloat()
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
        view.maxLines = if (display.secondary.isNotBlank()) 5 else 3
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
        if (activity is MainActivity && android.os.Build.VERSION.SDK_INT >= 29) {
            body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Capturar audio con Android",
                "Para reproductores que bloquean la captura web · requiere Deepgram y autorización") {
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
        body.addView(NexoUi.note(activity, "Si el video o su reproductor (YouTube, Netflix, Prime Video…) tienen subtítulos, Nexo los usa sin clave. Si no, Deepgram transcribe el audio en la nube con tu cuenta."))
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
        chips(aiProviderRow, listOf("openai" to "OpenAI", "claude" to "Claude", "openrouter" to "OpenRouter", "custom" to "Propio"), { aiProvider }) {
            aiProvider = it; refreshProvider()
        }
        translateBox.addView(aiBox)
        translateBox.addView(NexoUi.note(activity, "Se traducen frases completas, agrupadas, al momento y sin descargar idiomas."))
        body.addView(translateBox)
        refreshProvider()

        body.addView(NexoUi.section(activity, "Límite diario de Deepgram"))
        val limitRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(limitRow, listOf("30" to "30 min", "60" to "1 h", "120" to "2 h", "240" to "4 h", "0" to "Sin límite"), { limit.toString() }) { limit = it.toInt() }
        body.addView(limitRow)
        body.addView(NexoUi.note(activity, LiveCaptions.usageText(activity) + ". Los videos con subtítulos propios no lo consumen."))

        body.addView(NexoUi.section(activity, "Tamaño del texto"))
        val sizeRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(sizeRow, SIZES.map { it.first.toString() to it.second }, { size.toString() }) { size = it.toInt() }
        body.addView(sizeRow)

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
                if (aiProvider == "custom" && !endpoint.startsWith("https://")) {
                    error.text = "La dirección de la API debe empezar por https://."; error.visibility = View.VISIBLE; return@button
                }
                if (aiProvider != "custom" && aiKeyField.text.isBlank()) {
                    error.text = "Escribe la clave del modelo de IA."; error.visibility = View.VISIBLE; return@button
                }
            }
            BrowserPrefs.setDeepgramKey(activity, key)
            BrowserPrefs.setCaptionLanguage(activity, language)
            BrowserPrefs.setCaptionTranslateTo(activity, target)
            BrowserPrefs.setTranslationProvider(activity, provider)
            BrowserPrefs.setDeeplKey(activity, deeplField.text.toString())
            BrowserPrefs.setCaptionSize(activity, size)
            BrowserPrefs.setCaptionMode(activity, mode)
            BrowserPrefs.setCaptionDailyLimit(activity, limit)
            BrowserPrefs.setAiTranslateProvider(activity, aiProvider)
            BrowserPrefs.setAiTranslateModel(activity, aiModelField.text.toString())
            BrowserPrefs.setAiTranslateEndpoint(activity, if (aiProvider == "custom") aiEndpointField.text.toString() else "")
            BrowserPrefs.setAiTranslateKey(activity, aiKeyField.text.toString())
            sheet.dismiss()
            onSaved()
        }, NexoUi.buttonParams(activity, 14f))

        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, "Subtítulos en vivo", "Transcripción y traducción del video"), body))
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (askKey) keyField.requestFocus()
    }
}
