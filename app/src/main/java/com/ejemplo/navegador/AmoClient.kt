package com.ejemplo.navegador

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class StoreAddon(
    val id: String,
    val name: String,
    val summary: String,
    val version: String,
    val xpiUrl: String,
    val detailUrl: String,
    val iconUrl: String,
    val author: String,
    val users: Long,
    val rating: Double
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
                    "page_size=40",
                    "lang=es"
                )

                if (query.isBlank()) {
                    params += "promoted=badged"
                    params += "sort=recommended,users"
                } else {
                    params += "sort=relevance"
                    params +=
                        "q=" +
                        URLEncoder.encode(
                            query.take(120),
                            "UTF-8"
                        )
                }

                val connection =
                    URL(
                        "$API?${params.joinToString("&")}"
                    ).openConnection()
                        as HttpURLConnection

                connection.connectTimeout = 7000
                connection.readTimeout = 12000
                connection.useCaches = true
                connection.instanceFollowRedirects = true

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Accept-Language",
                    "es-CO,es;q=0.95,en;q=0.4"
                )

                connection.setRequestProperty(
                    "User-Agent",
                    "NexoBrowser/0.13 GeckoView/153 Android"
                )

                if (
                    connection.responseCode
                    !in 200..299
                ) {
                    error(
                        "Mozilla Add-ons respondió HTTP " +
                            connection.responseCode
                    )
                }

                connection.inputStream
                    .bufferedReader()
                    .use {
                        parse(it.readText())
                    }
            }

            Handler(
                Looper.getMainLooper()
            ).post {
                callback(result)
            }
        }.start()
    }

    private fun parse(
        text: String
    ): List<StoreAddon> {
        val results =
            JSONObject(text)
                .optJSONArray("results")
                ?: return emptyList()

        return buildList {
            for (
                i in 0 until results.length()
            ) {
                val addon =
                    results.optJSONObject(i)
                        ?: continue

                val current =
                    addon.optJSONObject(
                        "current_version"
                    )
                        ?: continue

                var xpi =
                    current
                        .optJSONObject("file")
                        ?.optString("url")
                        .cleanNull()

                if (xpi.isBlank()) {
                    val oldFiles =
                        current
                            .optJSONArray("files")

                    if (oldFiles != null) {
                        for (
                            j in 0 until
                                oldFiles.length()
                        ) {
                            xpi =
                                oldFiles
                                    .optJSONObject(j)
                                    ?.optString("url")
                                    .cleanNull()

                            if (xpi.isNotBlank()) {
                                break
                            }
                        }
                    }
                }

                if (xpi.isBlank()) {
                    continue
                }

                val icons =
                    addon.optJSONObject(
                        "icons"
                    )

                val icon =
                    icons
                        ?.optString("64")
                        .cleanNull()
                        .ifBlank {
                            icons
                                ?.optString("128")
                                .cleanNull()
                        }
                        .ifBlank {
                            addon
                                .optString(
                                    "icon_url"
                                )
                                .cleanNull()
                        }

                val author =
                    addon
                        .optJSONArray("authors")
                        ?.optJSONObject(0)
                        ?.optString("name")
                        .cleanNull()

                add(
                    StoreAddon(
                        id =
                            addon
                                .optString("guid")
                                .cleanNull()
                                .ifBlank {
                                    addon
                                        .optString(
                                            "slug"
                                        )
                                        .cleanNull()
                                },
                        name =
                            localized(
                                addon.opt("name")
                            ).ifBlank {
                                "Extensión de Mozilla"
                            },
                        summary =
                            localized(
                                addon.opt("summary")
                            ).ifBlank {
                                "Sin descripción disponible en español."
                            },
                        version =
                            current
                                .optString(
                                    "version"
                                )
                                .cleanNull(),
                        xpiUrl = xpi,
                        detailUrl =
                            addon
                                .optString("url")
                                .cleanNull(),
                        iconUrl = icon,
                        author = author,
                        users =
                            addon.optLong(
                                "average_daily_users"
                            ),
                        rating =
                            addon
                                .optJSONObject(
                                    "ratings"
                                )
                                ?.optDouble(
                                    "average",
                                    0.0
                                )
                                ?: 0.0
                    )
                )
            }
        }
    }

    private fun localized(
        value: Any?
    ): String =
        when (value) {
            is String ->
                value.cleanNull()

            is JSONObject -> {
                value
                    .optString("es")
                    .cleanNull()
                    .ifBlank {
                        value
                            .optString("es-ES")
                            .cleanNull()
                    }
                    .ifBlank {
                        value
                            .optString("es-MX")
                            .cleanNull()
                    }
                    .ifBlank {
                        value
                            .optString("es-AR")
                            .cleanNull()
                    }
                    .ifBlank {
                        value.keys()
                            .asSequence()
                            .filter {
                                it.startsWith(
                                    "es-",
                                    ignoreCase = true
                                )
                            }
                            .map {
                                value
                                    .optString(it)
                                    .cleanNull()
                            }
                            .firstOrNull {
                                it.isNotBlank()
                            }
                            .orEmpty()
                    }
                    .ifBlank {
                        value
                            .optString("en-US")
                            .cleanNull()
                    }
                    .ifBlank {
                        value
                            .optString("en")
                            .cleanNull()
                    }
                    .ifBlank {
                        value.keys()
                            .asSequence()
                            .map {
                                value
                                    .optString(it)
                                    .cleanNull()
                            }
                            .firstOrNull {
                                it.isNotBlank()
                            }
                            .orEmpty()
                    }
            }

            else -> ""
        }

    private fun String?.cleanNull(): String {
        val value =
            this?.trim().orEmpty()

        return if (
            value.equals(
                "null",
                ignoreCase = true
            )
        ) {
            ""
        } else {
            value
        }
    }
}
