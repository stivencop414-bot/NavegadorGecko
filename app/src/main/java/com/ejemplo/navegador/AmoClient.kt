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
    private const val API =
        "https://addons.mozilla.org/api/v5/addons/search/"

    fun search(
        query: String,
        callback: (Result<List<StoreAddon>>) -> Unit
    ) {
        Thread {
            val result = runCatching {
                val params = mutableListOf(
                    "app=android",
                    "type=extension",
                    "page_size=25",
                    "lang=es",
                    "sort=users"
                )

                if (query.isBlank()) {
                    params += "promoted=recommended"
                } else {
                    params += "q=" + URLEncoder.encode(query, "UTF-8")
                }

                val connection = URL(
                    "$API?${params.joinToString("&")}"
                ).openConnection() as HttpURLConnection

                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty(
                    "User-Agent",
                    "NexoBrowser/0.6 GeckoView"
                )

                if (connection.responseCode !in 200..299) {
                    error("AMO HTTP ${connection.responseCode}")
                }

                val text = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                parse(text)
            }

            Handler(Looper.getMainLooper()).post {
                callback(result)
            }
        }.start()
    }

    private fun parse(text: String): List<StoreAddon> {
        val results = JSONObject(text).optJSONArray("results")
            ?: return emptyList()

        return buildList {
            for (i in 0 until results.length()) {
                val addon = results.optJSONObject(i) ?: continue
                val current = addon.optJSONObject("current_version") ?: continue
                val files = current.optJSONArray("files") ?: continue

                var xpi = ""
                for (j in 0 until files.length()) {
                    val file = files.optJSONObject(j) ?: continue
                    val platform = file.optString("platform")
                    if (
                        platform.equals("android", true) ||
                        platform.equals("all", true)
                    ) {
                        xpi = file.optString("url")
                        if (xpi.isNotBlank()) break
                    }
                }

                if (xpi.isBlank()) continue

                add(
                    StoreAddon(
                        localized(addon.opt("name")),
                        localized(addon.opt("summary")),
                        current.optString("version"),
                        xpi,
                        addon.optString("url"),
                        addon.optLong("average_daily_users")
                    )
                )
            }
        }
    }

    private fun localized(value: Any?): String =
        when (value) {
            is String -> value
            is JSONObject ->
                value.optString("es")
                    .ifBlank { value.optString("en-US") }
                    .ifBlank { value.optString("en") }
                    .ifBlank {
                        value.keys().asSequence()
                            .map { value.optString(it) }
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                    }
            else -> ""
        }
}
