package com.ejemplo.navegador

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val url: String,
    val title: String,
    val timestamp: Long
)

object HistoryStore {
    private const val FILE = "nexo_history"
    private const val KEY = "entries"
    private const val MAX = 500

    @Synchronized
    fun add(context: Context, url: String, title: String) {
        if (url.isBlank() || url.startsWith("resource://android/assets/home/")) return

        val items = list(context).toMutableList()
        items.removeAll { it.url == url }
        items.add(0, HistoryEntry(url, title.ifBlank { url }, System.currentTimeMillis()))
        save(context, items.take(MAX))
    }

    @Synchronized
    fun list(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"

        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        HistoryEntry(
                            o.optString("url"),
                            o.optString("title"),
                            o.optLong("timestamp")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    private fun save(context: Context, items: List<HistoryEntry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("url", it.url)
                put("title", it.title)
                put("timestamp", it.timestamp)
            })
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
