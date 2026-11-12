package com.ejemplo.navegador

import java.net.URI
import java.util.Locale

/**
 * Busca en el HTML de un sitio los iconos que declara (`apple-touch-icon`,
 * `icon` con tamaños, `shortcut icon`) y los ordena del más nítido al menos.
 * Puro: sin red ni Android, para probarlo en la JVM.
 */
object SiteIconDiscovery {
    data class Candidate(val url: String, val score: Int)

    private val LINK = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("""([a-zA-Z][\w:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val BASE = Regex("""<base\b[^>]*href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    /** URLs de iconos HTTPS, sin SVG (Android no lo dibuja como mapa de bits). */
    fun candidates(html: String, pageUrl: String): List<String> {
        val head = html.take(200_000).let { text ->
            val end = text.indexOf("</head", ignoreCase = true)
            if (end > 0) text.substring(0, end) else text
        }
        val base = BASE.find(head)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
            ?.let { resolve(pageUrl, it) } ?: pageUrl
        val found = mutableListOf<Candidate>()
        for (match in LINK.findAll(head)) {
            val attributes = ATTRIBUTE.findAll(match.value).associate { attribute ->
                attribute.groupValues[1].lowercase(Locale.ROOT) to
                    (attribute.groupValues[2].ifEmpty { attribute.groupValues[3].ifEmpty { attribute.groupValues[4] } })
            }
            val rel = attributes["rel"]?.lowercase(Locale.ROOT)?.split(Regex("\\s+")).orEmpty()
            val href = attributes["href"]?.trim().orEmpty()
            if (href.isEmpty() || href.startsWith("data:", ignoreCase = true)) continue
            val type = attributes["type"]?.lowercase(Locale.ROOT).orEmpty()
            if (type.contains("svg") || href.substringBefore('?').endsWith(".svg", ignoreCase = true)) continue
            val kind = when {
                "apple-touch-icon" in rel || "apple-touch-icon-precomposed" in rel -> 3
                "icon" in rel -> 2
                else -> continue
            }
            val url = resolve(base, href) ?: continue
            if (!url.startsWith("https://")) continue
            val size = attributes["sizes"]?.let { sizes ->
                Regex("""(\d{1,4})x(\d{1,4})""", RegexOption.IGNORE_CASE).findAll(sizes)
                    .map { it.groupValues[1].toInt() }.maxOrNull()
            } ?: if (kind == 3) 180 else 32
            // Preferir ~96–256 px: más grande no aporta y pesa más.
            val sizeScore = when {
                size in 96..256 -> 300
                size > 256 -> 250
                else -> size
            }
            found += Candidate(url, kind * 1000 + sizeScore)
        }
        val origin = runCatching { URI(pageUrl) }.getOrNull()?.let {
            URI("https", null, it.host, it.port, "/", null, null).toASCIIString().trimEnd('/')
        }
        val fallbacks = listOfNotNull(
            origin?.let { "$it/apple-touch-icon.png" },
            origin?.let { "$it/favicon.ico" }
        )
        return (found.sortedByDescending { it.score }.map { it.url } + fallbacks).distinct().take(6)
    }

    fun resolve(base: String, href: String): String? = runCatching {
        val resolved = URI(base).resolve(href.replace(" ", "%20"))
        if (resolved.userInfo != null || resolved.host.isNullOrBlank()) null
        else resolved.toASCIIString()
    }.getOrNull()

    /** Mismo sitio (último par de etiquetas): una redirección no lleva a otro dominio. */
    fun sameSite(a: String?, b: String?): Boolean {
        fun site(host: String?): String? = host?.lowercase(Locale.ROOT)?.trimEnd('.')?.split('.')?.takeLast(2)?.joinToString(".")
        return site(a) != null && site(a) == site(b)
    }
}
