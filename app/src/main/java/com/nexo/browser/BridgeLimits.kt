package com.nexo.browser

import org.json.JSONObject

/**
 * Límites de los mensajes que una página (a través del puente) envía a Nexo.
 * Un marco hostil no puede hacer que Nexo procese cadenas o listas enormes.
 * Puro: probado en la JVM.
 */
object BridgeLimits {
    private const val MAX_KEYS = 16
    private const val MAX_AUDIO_BASE64 = 350_000
    private const val MAX_TEXT = 2_000
    private const val MAX_CUES = 200
    private const val MAX_IDS = 16

    fun accept(json: JSONObject): Boolean {
        if (json.length() > MAX_KEYS) return false
        val type = json.optString("type")
        if (type.isEmpty() || type.length > 40) return false
        for (key in json.keys()) {
            val value = json.opt(key)
            when {
                value is String && key == "data" && type == "captions_audio" -> if (value.length > MAX_AUDIO_BASE64) return false
                value is String -> if (value.length > MAX_TEXT) return false
                value is org.json.JSONArray -> {
                    val limit = if (key == "cues") MAX_CUES else MAX_IDS
                    if (value.length() > limit) return false
                }
                value is JSONObject -> return false
            }
        }
        return true
    }
}
