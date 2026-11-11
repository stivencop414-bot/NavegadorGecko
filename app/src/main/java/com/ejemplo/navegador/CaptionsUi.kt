package com.ejemplo.navegador

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
        "fr" to "Français", "de" to "Deutsch", "it" to "Italiano", "ja" to "日本語",
        "ko" to "한국어", "zh" to "中文", "ru" to "Русский", "hi" to "हिन्दी"
    )
    val TARGETS = listOf(
        "" to "No traducir", "es" to "Español", "en" to "English", "pt" to "Português",
        "fr" to "Français", "de" to "Deutsch", "it" to "Italiano", "ja" to "日本語", "ko" to "한국어", "zh" to "中文"
    )
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
        pip: Boolean
    ) {
        if (display == null || display.isEmpty || frameWidth <= 0 || frameHeight <= 0) {
            view.visibility = View.GONE
            return
        }
        val size = BrowserPrefs.captionSize(activity).toFloat()
        view.textSize = if (pip) (size * 0.72f).coerceAtLeast(11f) else size
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.setTextColor(Color.WHITE)
        view.setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
        view.background = GradientDrawable().apply {
            setColor(0xB8000000.toInt())
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
        body.addView(NexoUi.note(activity, "Si el video trae subtítulos propios se usan esos (sin clave). Si no, Deepgram transcribe el audio en la nube con tu cuenta."))
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
        chips(targetRow, TARGETS, { target }) { target = it; refreshProvider() }
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
