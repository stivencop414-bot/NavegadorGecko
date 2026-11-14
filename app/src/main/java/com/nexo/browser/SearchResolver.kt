package com.nexo.browser

import android.content.Context
import android.net.Uri
import java.net.URLEncoder

object SearchResolver {
    // El último segmento (TLD) debe empezar por letra: "3.14" es una búsqueda.
    private val domain = Regex(
        """^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)*\.[a-zA-Z][a-zA-Z0-9-]*\.?(:\d{1,5})?([/?#].*)?$"""
    )
    private val ipv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d{1,5})?([/?#].*)?$""")
    private val localhost = Regex("""^localhost(:\d{1,5})?([/?#].*)?$""", RegexOption.IGNORE_CASE)

    /**
     * Dirección escrita sin esquema, o null si es una búsqueda. Red local
     * (localhost, IP) usa http: routers y servidores de desarrollo no suelen
     * tener https.
     */
    fun addressFor(input: String): String? =
        when {
            localhost.matches(input) || ipv4.matches(input) -> "http://$input"
            domain.matches(input) -> "https://$input"
            else -> null
        }

    fun resolve(context: Context, inputRaw: String): String {
        val input = inputRaw.trim()
        if (input.isBlank()) return BrowserPrefs.homePage(context)

        // Resolver primero hosts sin esquema (incluido localhost:puerto).
        addressFor(input)?.let { return it }

        val parsed = runCatching { Uri.parse(input) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()

        // javascript:, data:, file:, content: e intent: nunca deben ejecutarse
        // desde la barra de direcciones o desde un intent externo.
        if (scheme != null) {
            return when (scheme) {
                "https", "http", "about", "resource", "moz-extension" -> input
                else -> search(context, input)
            }
        }

        return search(context, input)
    }

    /** Búsqueda directa (sugerencias): nunca se interpreta como dirección. */
    fun searchUrl(context: Context, query: String): String = search(context, query.trim())

    private fun search(context: Context, input: String): String {
        val q = URLEncoder.encode(input, "UTF-8")
        val free = BrowserPrefs.freeSearch(context)

        return when (BrowserPrefs.searchEngine(context)) {
            BrowserPrefs.ENGINE_DDG ->
                "https://duckduckgo.com/?q=$q" + if (free) "&kp=-2" else ""
            BrowserPrefs.ENGINE_BING ->
                "https://www.bing.com/search?q=$q" + if (free) "&adlt=off" else ""
            BrowserPrefs.ENGINE_BRAVE ->
                "https://search.brave.com/search?q=$q" + if (free) "&safesearch=off" else ""
            BrowserPrefs.ENGINE_STARTPAGE ->
                "https://www.startpage.com/sp/search?query=$q"
            else ->
                "https://www.google.com/search?q=$q" + if (free) "&safe=off" else ""
        }
    }
}
