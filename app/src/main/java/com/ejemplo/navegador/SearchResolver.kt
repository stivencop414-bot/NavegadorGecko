package com.ejemplo.navegador

import android.content.Context
import android.net.Uri
import java.net.URLEncoder

object SearchResolver {
    private val domain = Regex("""^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+([/:?#].*)?$""")
    private val ipv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?$""")

    fun resolve(context: Context, inputRaw: String): String {
        val input = inputRaw.trim()
        if (input.isBlank()) return BrowserPrefs.homePage(context)

        // Resolver primero hosts sin esquema (incluido localhost:puerto).
        if (
            input.equals("localhost", true) ||
            input.startsWith("localhost:", true) ||
            domain.matches(input) ||
            ipv4.matches(input)
        ) return "https://$input"

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
