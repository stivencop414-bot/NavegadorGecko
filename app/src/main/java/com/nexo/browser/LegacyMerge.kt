package com.nexo.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Datos nativos que la app anterior (com.ejemplo.navegador) entrega a la
 * nueva: validación y combinación con lo que ya tenga el destino. Puro (sin
 * Android) para probarlo en la JVM. Nunca incluye pestañas privadas, Burner,
 * cookies ni datos de Gecko.
 */
object LegacyMerge {
    const val VERSION = 1
    private const val MAX_HISTORY = 500
    private const val MAX_LINKS = 24
    private const val MAX_TABS = 200

    data class Tab(val url: String, val title: String, val groupName: String?, val desktopMode: Boolean)

    /** Ajustes ya comprobados (clave conocida y tipo correcto): clave → valor. */
    data class Payload(
        val settings: Map<String, Any>,
        val history: JSONArray,
        val customLinks: JSONArray,
        val hiddenLinks: Set<String>,
        val tabs: List<Tab>,
        val secrets: Map<String, String>
    )

    /** Rechaza todo lo que no sea exactamente el formato esperado; no se aplica nada a medias. */
    fun parse(raw: String): Payload {
        require(raw.length <= 4 * 1024 * 1024) { "Datos demasiado grandes" }
        val json = JSONObject(raw)
        require(json.getInt("version") == VERSION) { "Versión de datos no compatible" }
        require(json.getString("source") == "com.ejemplo.navegador") { "Origen inesperado" }

        val settings = linkedMapOf<String, Any>()
        val rawSettings = json.optJSONObject("settings") ?: JSONObject()
        for (key in rawSettings.keySet()) {
            val type = TransferSchema.settingsTypes[key] ?: continue // claves de otras versiones: se ignoran
            val item = rawSettings.getJSONObject(key)
            require(item.getString("type") == type) { "Tipo de ajuste inválido: $key" }
            settings[key] = when (type) {
                "boolean" -> item.getBoolean("value")
                "int" -> item.getInt("value")
                else -> item.getString("value").also { require(it.length <= 4096) }
            }
        }

        val history = JSONArray()
        val rawHistory = json.optJSONArray("history") ?: JSONArray()
        require(rawHistory.length() <= MAX_HISTORY * 2)
        for (i in 0 until rawHistory.length()) {
            val h = rawHistory.getJSONObject(i)
            val url = h.getString("url")
            if (!UrlSafety.isWebUrl(url) || url.length > 8192) continue
            history.put(JSONObject().put("url", url).put("title", h.optString("title").take(1024)).put("timestamp", h.optLong("timestamp")))
        }

        val links = JSONArray()
        val rawLinks = json.optJSONArray("customLinks") ?: JSONArray()
        require(rawLinks.length() <= 500)
        for (i in 0 until rawLinks.length()) {
            val url = rawLinks.getJSONObject(i).getString("url")
            if (UrlSafety.isWebUrl(url) && url.length <= 2048) links.put(JSONObject().put("url", url))
        }
        val hidden = json.optJSONArray("hiddenLinks")?.let { array ->
            require(array.length() <= 500)
            (0 until array.length()).map { array.getString(it).take(2048) }.toSet()
        } ?: emptySet()

        val tabs = mutableListOf<Tab>()
        val rawTabs = JSONArray(TransferSchema.tabs((json.optJSONArray("tabs") ?: JSONArray()).toString()))
        for (i in 0 until minOf(rawTabs.length(), MAX_TABS)) {
            val t = rawTabs.getJSONObject(i)
            val url = t.getString("url")
            if (!UrlSafety.isWebUrl(url)) continue
            tabs += Tab(url, t.optString("title").take(512), TabGroupRules.name(t.optString("groupName")), t.optBoolean("desktopMode"))
        }

        val secrets = linkedMapOf<String, String>()
        val rawSecrets = json.optJSONObject("secrets") ?: JSONObject()
        for (key in rawSecrets.keySet()) {
            require(key in TransferSchema.secretNames) { "Clave desconocida" }
            val value = rawSecrets.getString(key).trim()
            require(value.length <= 16384)
            if (value.isNotEmpty()) secrets[key] = value
        }
        return Payload(settings, history, links, hidden, tabs, secrets)
    }

    /** Historial combinado: una entrada por dirección (la visita más reciente), de más nuevo a más viejo. */
    fun mergeHistory(existing: JSONArray, incoming: JSONArray): JSONArray {
        val newest = linkedMapOf<String, JSONObject>()
        for (array in listOf(existing, incoming)) for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val url = entry.optString("url")
            if (url.isEmpty()) continue
            val known = newest[url]
            if (known == null || entry.optLong("timestamp") > known.optLong("timestamp")) newest[url] = entry
        }
        return JSONArray(newest.values.sortedByDescending { it.optLong("timestamp") }.take(MAX_HISTORY))
    }

    /** Accesos fijados: primero los del destino, después los traídos que falten. */
    fun mergeLinks(existing: JSONArray, incoming: JSONArray): JSONArray {
        val seen = HashSet<String>()
        val out = JSONArray()
        for (array in listOf(existing, incoming)) for (i in 0 until array.length()) {
            val url = array.optJSONObject(i)?.optString("url").orEmpty()
            if (url.isNotEmpty() && seen.add(url.trimEnd('/').lowercase()) && out.length() < MAX_LINKS) out.put(JSONObject().put("url", url))
        }
        return out
    }

    /** Pestañas traídas que no estén ya abiertas. */
    fun newTabs(openUrls: Collection<String>, incoming: List<Tab>): List<Tab> {
        val open = openUrls.toHashSet()
        return incoming.filter { open.add(it.url) }
    }

    /** Una clave ya puesta en la app nueva no se pisa. */
    fun secretsToApply(current: Map<String, Boolean>, incoming: Map<String, String>): Map<String, String> =
        incoming.filterKeys { current[it] != true }

    /** Formato que produce la app anterior (lado origen). */
    fun build(
        settings: Map<String, Any?>,
        historyJson: String?,
        customJson: String?,
        hidden: Set<String>?,
        tabsJson: String?,
        secrets: Map<String, String>
    ): JSONObject {
        val typed = JSONObject()
        settings.forEach { (key, value) ->
            val type = TransferSchema.settingsTypes[key] ?: return@forEach
            val ok = when (type) { "boolean" -> value is Boolean; "int" -> value is Int; else -> value is String }
            if (ok) typed.put(key, JSONObject().put("type", type).put("value", value))
        }
        return JSONObject()
            .put("version", VERSION)
            .put("source", "com.ejemplo.navegador")
            .put("settings", typed)
            .put("history", runCatching { JSONArray(historyJson ?: "[]") }.getOrDefault(JSONArray()))
            .put("customLinks", runCatching { JSONArray(customJson ?: "[]") }.getOrDefault(JSONArray()))
            .put("hiddenLinks", JSONArray(hidden.orEmpty().toList()))
            .put("tabs", runCatching { JSONArray(TransferSchema.tabs(tabsJson ?: "[]")) }.getOrDefault(JSONArray()))
            .put("secrets", JSONObject(secrets.filterValues { it.isNotEmpty() }))
    }
}
