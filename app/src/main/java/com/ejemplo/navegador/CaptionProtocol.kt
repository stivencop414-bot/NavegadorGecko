package com.ejemplo.navegador

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Formatos de Deepgram (transcripción en streaming) y de los traductores.
 * Sin dependencias de Android para poder probarlo en la JVM.
 */
object CaptionProtocol {
    const val LISTEN_ENDPOINT = "wss://api.deepgram.com/v1/listen"

    /** nova-3 para inglés y el modo multilingüe; nova-2 cubre el resto de idiomas. */
    fun modelFor(language: String): String =
        if (language == "multi" || language == "en" || language.startsWith("en-")) "nova-3" else "nova-2"

    fun listenUrl(language: String, model: String = modelFor(language)): String {
        val lang = language.takeIf { it.matches(Regex("[a-z]{2,5}(-[A-Za-z]{2,4})?|multi")) } ?: "multi"
        return "$LISTEN_ENDPOINT?model=$model&language=$lang&smart_format=true&punctuate=true" +
            "&interim_results=true&endpointing=300&utterance_end_ms=1000"
    }

    data class Transcript(val text: String, val isFinal: Boolean, val speechFinal: Boolean)

    /** Mensaje "Results" de Deepgram; null para metadatos u otros tipos. */
    fun parseResult(raw: String): Transcript? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optString("type") != "Results") return null
        val text = json.optJSONObject("channel")
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
            ?.optString("transcript")
            .orEmpty()
            .trim()
        return Transcript(text, json.optBoolean("is_final"), json.optBoolean("speech_final"))
    }

    fun isUtteranceEnd(raw: String): Boolean =
        runCatching { JSONObject(raw).optString("type") == "UtteranceEnd" }.getOrDefault(false)

    const val KEEP_ALIVE = """{"type":"KeepAlive"}"""
    const val CLOSE_STREAM = """{"type":"CloseStream"}"""

    /** Mensaje de error comprensible a partir del código HTTP del saludo WebSocket. */
    fun errorFor(httpCode: Int?): String = when (httpCode) {
        400 -> "Deepgram rechazó la configuración (idioma o modelo)."
        401, 403 -> "La clave de Deepgram no es válida."
        402 -> "La cuenta de Deepgram no tiene saldo."
        429 -> "Deepgram limitó las solicitudes. Inténtalo en un momento."
        null -> "Sin conexión con Deepgram."
        else -> "Deepgram no está disponible (HTTP $httpCode)."
    }

    // --- Traducción ------------------------------------------------------

    fun googleUrl(text: String, target: String): String =
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" +
            URLEncoder.encode(target, "UTF-8") + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8")

    /** [[["traducido","original",...],...],null,"en",...] */
    fun parseGoogle(body: String): String? = runCatching {
        val parts = JSONArray(body).getJSONArray(0)
        buildString {
            for (i in 0 until parts.length()) {
                val piece = parts.optJSONArray(i)?.optString(0).orEmpty()
                append(piece)
            }
        }.trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun deeplEndpoint(key: String): String =
        if (key.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"

    fun deeplTarget(code: String): String = when (code) {
        "en" -> "EN-US"
        "pt" -> "PT-BR"
        "zh" -> "ZH-HANS"
        else -> code.uppercase()
    }

    fun deeplBody(text: String, target: String): String =
        "text=" + URLEncoder.encode(text, "UTF-8") + "&target_lang=" + URLEncoder.encode(deeplTarget(target), "UTF-8")

    fun parseDeepl(body: String): String? = runCatching {
        JSONObject(body).getJSONArray("translations").getJSONObject(0).getString("text").trim()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * Texto que se muestra: últimas frases finalizadas (con su traducción si la
 * hay) más la frase en curso. Se limita a unas dos líneas.
 */
class CaptionText(private val maxChars: Int = 150) {
    data class Segment(val id: Long, val original: String, var translated: String? = null)

    private val segments = ArrayDeque<Segment>()
    private var interim = ""
    private var nextId = 1L

    /** Devuelve el segmento finalizado nuevo (para traducirlo), si lo hay. */
    fun onTranscript(transcript: CaptionProtocol.Transcript): Segment? {
        if (!transcript.isFinal) {
            interim = transcript.text
            return null
        }
        interim = ""
        if (transcript.text.isBlank()) return null
        val segment = Segment(nextId++, transcript.text)
        segments.addLast(segment)
        while (segments.size > 6) segments.removeFirst()
        return segment
    }

    fun setTranslation(id: Long, text: String) {
        segments.firstOrNull { it.id == id }?.translated = text
    }

    fun clear() {
        segments.clear()
        interim = ""
    }

    val isEmpty: Boolean get() = segments.isEmpty() && interim.isBlank()

    /** Texto principal (traducido cuando existe) y, aparte, la parte aún sin traducir. */
    fun display(translating: Boolean): Pair<String, String> {
        val done = StringBuilder()
        val pending = StringBuilder()
        for (segment in segments) {
            val text = if (translating) segment.translated else segment.original
            if (text != null) {
                if (pending.isNotEmpty()) { done.append(pending).append(' '); pending.clear() }
                done.append(text).append(' ')
            } else {
                pending.append(segment.original).append(' ')
            }
        }
        if (interim.isNotBlank()) pending.append(interim)
        return trimStart(done.toString().trim(), pending.toString().trim())
    }

    /** Conserva el final del texto, cortando por palabras. */
    private fun trimStart(done: String, pending: String): Pair<String, String> {
        val total = done.length + (if (done.isNotEmpty() && pending.isNotEmpty()) 1 else 0) + pending.length
        if (total <= maxChars) return done to pending
        var cut = total - maxChars
        if (cut >= done.length) {
            val rest = pending.drop(cut - done.length - if (done.isNotEmpty()) 1 else 0)
            return "" to rest.substringAfter(' ', rest).trimStart()
        }
        val kept = done.drop(cut)
        cut = kept.indexOf(' ')
        return (if (cut in 0..20) kept.drop(cut + 1) else kept) to pending
    }
}
