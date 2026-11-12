package com.nexo.browser

import java.util.Locale

/**
 * Reglas puras de las descargas aceleradas (sin red ni Android, probadas en
 * la JVM). Un archivo grande cuyo servidor admite rangos se reparte entre
 * varias conexiones: la descarga original sigue con el primer tramo y otras
 * piden el resto con `Range`, como los gestores de descargas.
 */
object DownloadPlan {
    /** Por debajo de este tamaño una conexión basta. */
    const val MIN_PARALLEL_BYTES = 16L * 1024 * 1024
    /** Ningún tramo más pequeño que esto (cada conexión cuesta un saludo TLS). */
    const val MIN_SEGMENT_BYTES = 4L * 1024 * 1024
    const val MAX_CONNECTIONS = 4

    data class ContentRange(val start: Long, val end: Long, val total: Long)

    /** `bytes 100-199/1000` → (100, 199, 1000). Total desconocido (`*`) = -1. */
    fun parseContentRange(value: String?): ContentRange? {
        val match = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(value ?: return null)
            ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].let { if (it == "*") -1L else it.toLongOrNull() ?: return null }
        if (end < start || (total >= 0 && end >= total)) return null
        return ContentRange(start, end, total)
    }

    /**
     * La respuesta original permite repartir: tamaño conocido y grande,
     * `Accept-Ranges: bytes` y sin compresión de transporte (con gzip los
     * tamaños y los rangos no corresponden al archivo guardado).
     */
    fun canSplit(headers: Map<String, String>, total: Long): Boolean {
        if (total < MIN_PARALLEL_BYTES) return false
        val ranges = header(headers, "Accept-Ranges")?.lowercase(Locale.ROOT)?.trim()
        val encoding = header(headers, "Content-Encoding")?.lowercase(Locale.ROOT)?.trim()
        return ranges == "bytes" && (encoding.isNullOrEmpty() || encoding == "identity")
    }

    /** Conexiones según el tamaño: 2 desde 16 MB, 3 desde 48 MB, 4 desde 96 MB. */
    fun connectionsFor(total: Long): Int = when {
        total >= 96L * 1024 * 1024 -> 4
        total >= 48L * 1024 * 1024 -> 3
        total >= MIN_PARALLEL_BYTES -> 2
        else -> 1
    }.coerceAtMost(MAX_CONNECTIONS)

    /** Inicio del tramo final, el primero que se pide para comprobar el servidor. */
    fun tailStart(total: Long, connections: Int): Long = total - total / connections.coerceAtLeast(2)

    /**
     * Reparte lo que falta entre la conexión original (que va por [position])
     * y [extra] conexiones intermedias, antes de [tail]. Devuelve el límite de
     * la original y los tramos intermedios [inicio, fin). null si ya no compensa.
     */
    fun split(position: Long, tail: Long, extra: Int): Pair<Long, List<LongRange>>? {
        // Un margen para que la original no llegue al límite mientras se calcula.
        val from = position + 1024L * 1024
        if (tail - from < MIN_SEGMENT_BYTES) return null
        val parts = (extra + 1).coerceAtLeast(1)
        val size = (tail - from) / parts
        if (size < MIN_SEGMENT_BYTES && extra > 0) return split(position, tail, extra - 1)
        val bounds = (0..parts).map { if (it == parts) tail else from + size * it }
        val ranges = (1 until parts).map { bounds[it] until bounds[it + 1] }
        return bounds[1] to ranges
    }

    /** El tramo pedido corresponde al mismo archivo (mismas validaciones si las hay). */
    fun sameEntity(original: Map<String, String>, part: Map<String, String>): Boolean {
        for (name in listOf("ETag", "Last-Modified")) {
            val a = header(original, name)?.trim()?.removePrefix("W/")
            val b = header(part, name)?.trim()?.removePrefix("W/")
            if (!a.isNullOrEmpty() && !b.isNullOrEmpty() && a != b) return false
        }
        return true
    }

    fun header(headers: Map<String, String>, name: String): String? =
        headers[name] ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
