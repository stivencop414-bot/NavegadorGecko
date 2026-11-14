package com.nexo.browser

import java.net.URI
import java.text.Normalizer
import java.util.Locale

sealed class OmniboxResult {
    data class History(val url: String, val title: String) : OmniboxResult()
    data class Search(val query: String) : OmniboxResult()
}

/** Immutable, rebuilt only when history changes. No disk or Android dependencies. */
class OmniboxIndex(entries: List<HistoryEntry>) {
    private data class Item(val entry: HistoryEntry, val host: String, val title: String, val text: String)
    private val items = entries.distinctBy { it.url }.take(500).map {
        val host = runCatching { URI(it.url).host.orEmpty() }.getOrDefault("")
        Item(it, normalize(host.removePrefix("www.")), normalize(it.title), normalize("${it.title} $host ${it.url}"))
    }
    fun find(query: String): List<OmniboxResult.History> {
        val q = normalize(query.trim()).take(256)
        if (q.isBlank()) return emptyList()
        val terms = q.split(spaces)
        return items.asSequence().filter { item -> terms.all { it in item.text } }
            .sortedWith(compareByDescending<Item> { when {
                it.host == q -> 3
                it.host.startsWith(q) -> 2
                it.title.startsWith(q) -> 1
                else -> 0
            } }.thenByDescending { it.entry.timestamp })
            .take(3).map { OmniboxResult.History(it.entry.url, it.entry.title) }.toList()
    }
    companion object {
        private val spaces = Regex("\\s+")
        private val marks = Regex("\\p{Mn}+")
        fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "")

        fun canQueryRemotely(raw: String): Boolean {
            val q = raw.trim()
            if (q.length !in 2..200 || q.any { it.isISOControl() }) return false
            if (q.any { it in "@:/\\?#=&" } || q.contains('.') || q.contains("localhost", true)) return false
            return !Regex("(?i)\\b(token|password|passwd|secret|api[_ -]?key|authorization)\\b").containsMatchIn(q)
        }
    }
}
