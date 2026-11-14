package com.nexo.browser

import java.text.Normalizer

/**
 * Índice en memoria del historial (las mismas 500 entradas, sin otra base de
 * datos) para las sugerencias de la barra. Busca por título, dominio y
 * dirección sin distinguir mayúsculas ni tildes; ordena por calidad de la
 * coincidencia y después por fecha, sin direcciones repetidas.
 * Puro (sin Android): se prueba en la JVM, incluida la latencia.
 */
class SuggestionIndex(entries: List<HistoryEntry>) {
    private class Item(val entry: HistoryEntry, val host: String, val url: String, val title: String, val key: String)

    private val items: List<Item> = entries.mapNotNull { entry ->
        val url = UrlSafety.stripCredentials(entry.url)
        if (!UrlSafety.isWebUrl(url)) return@mapNotNull null
        val host = hostOf(url)
        Item(entry.copy(url = url), fold(host.removePrefix("www.")), fold(url.substringAfter("://")), fold(entry.title), dedupeKey(url))
    }

    val size: Int get() = items.size

    /** Hasta [limit] entradas que coinciden con [query]; lista vacía si no hay consulta. */
    fun search(query: String, limit: Int = 3): List<HistoryEntry> {
        val q = fold(query.trim()).removePrefix("www.")
        if (q.isEmpty() || limit <= 0) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        val scored = ArrayList<Pair<Item, Int>>()
        for (item in items) {
            val score = score(item, q, words)
            if (score > 0) scored += item to score
        }
        scored.sortWith(compareByDescending<Pair<Item, Int>> { it.second }.thenByDescending { it.first.entry.timestamp })
        val seen = HashSet<String>()
        val out = ArrayList<HistoryEntry>(limit)
        for ((item, _) in scored) {
            if (!seen.add(item.key)) continue
            out += item.entry
            if (out.size == limit) break
        }
        return out
    }

    private fun score(item: Item, q: String, words: List<String>): Int {
        if (item.host.startsWith(q)) return 100
        if (item.url.startsWith(q)) return 90
        if (item.host.split('.').any { it.startsWith(q) }) return 80
        if (item.title.startsWith(q)) return 70
        if (words.all { w -> item.title.split(' ').any { it.startsWith(w) } }) return 60
        if (item.title.contains(q)) return 40
        if (item.url.contains(q)) return 30
        if (words.size > 1 && words.all { item.title.contains(it) || item.url.contains(it) }) return 20
        return 0
    }

    companion object {
        private val marks = Regex("\\p{Mn}+")

        /** Minúsculas y sin tildes: "Canción" → "cancion". */
        fun fold(text: String): String =
            marks.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "").replace(Regex("\\s+"), " ")

        fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').lowercase()

        /** Misma página con o sin www, esquema o barra final. */
        fun dedupeKey(url: String): String =
            url.substringAfter("://").removePrefix("www.").substringBefore('#').trimEnd('/').lowercase()
    }
}
