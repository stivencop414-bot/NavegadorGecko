package com.ejemplo.navegador

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class StoreAddon(
    val name: String,
    val summary: String,
    val version: String,
    val xpiUrl: String,
    val detailUrl: String,
    val users: Long
)

object AmoClient {
    private const val API = "https://addons.mozilla.org/api/v5/addons/search/"

    fun search(query: String, callback: (Result<List<StoreAddon>>) -> Unit) {
        Thread {
            val result = runCatching {
                val params = mutableListOf(
                    "app=android", "type=extension", "page_size=25", "lang=es", "sort=users"
                )
                if (query.isBlank()) params += "promoted=badged"
                else params += "q=" + URLEncoder.encode(query, "UTF-8")

                val connection = URL("$API?${params.joinToString("&")}")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.useCaches = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "NexoBrowser/0.8 GeckoView/153")

                if (connection.responseCode !in 200..299) error("AMO HTTP ${connection.responseCode}")
                parse(connection.inputStream.bufferedReader().use { it.readText() })
            }
            Handler(Looper.getMainLooper()).post { callback(result) }
        }.start()
    }

    private fun parse(text: String): List<StoreAddon> {
        val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val addon = results.optJSONObject(i) ?: continue
                val current = addon.optJSONObject("current_version") ?: continue
                var xpi = current.optJSONObject("file")?.optString("url").cleanNull()
                if (xpi.isBlank()) {
                    val oldFiles = current.optJSONArray("files")
                    if (oldFiles != null) {
                        for (j in 0 until oldFiles.length()) {
                            xpi = oldFiles.optJSONObject(j)?.optString("url").cleanNull()
                            if (xpi.isNotBlank()) break
                        }
                    }
                }
                if (xpi.isBlank()) continue

                add(
                    StoreAddon(
                        localized(addon.opt("name")).ifBlank { "Extensión de Mozilla" },
                        localized(addon.opt("summary")).ifBlank { "Sin descripción disponible" },
                        current.optString("version").cleanNull(),
                        xpi,
                        addon.optString("url").cleanNull(),
                        addon.optLong("average_daily_users")
                    )
                )
            }
        }
    }

    private fun localized(value: Any?): String =
        when (value) {
            is String -> value.cleanNull()
            is JSONObject ->
                value.optString("es").cleanNull()
                    .ifBlank { value.optString("es-ES").cleanNull() }
                    .ifBlank { value.optString("en-US").cleanNull() }
                    .ifBlank { value.optString("en").cleanNull() }
                    .ifBlank {
                        value.keys().asSequence()
                            .map { value.optString(it).cleanNull() }
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                    }
            else -> ""
        }

    private fun String?.cleanNull(): String {
        val v = this?.trim().orEmpty()
        return if (v.equals("null", true)) "" else v
    }
}
