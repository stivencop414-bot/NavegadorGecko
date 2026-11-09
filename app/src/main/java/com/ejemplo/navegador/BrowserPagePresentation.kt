package com.ejemplo.navegador

import java.net.URI
import java.util.Locale

/** Presentation of history links; never changes a URL used for navigation. */
object BrowserPagePresentation {
    data class Shortcut(val title: String, val origin: String, val identity: String)

    /** Names come only from installed extension metadata, never from page titles or URL paths. */
    fun extensionLabel(url: String, installedOptions: List<Pair<String, String>>): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("moz-extension", ignoreCase = true)) return null
        val authority = uri.rawAuthority ?: return "Página de extensión"
        val name = installedOptions.firstOrNull { (options, name) ->
            if (name.isBlank()) return@firstOrNull false
            val known = runCatching { URI(options) }.getOrNull()
            known?.scheme.equals("moz-extension", ignoreCase = true) &&
                known?.rawAuthority?.equals(authority, ignoreCase = true) == true
        }?.second
        return name?.let { "$it · Extensión" } ?: "Página de extensión"
    }

    fun shortcut(url: String): Shortcut? = runCatching {
        if (!UrlSafety.isWebUrl(url)) return null
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        // Keep HTTP, mobile hosts and explicit ports; drop credentials, path and query.
        val origin = URI(scheme, null, host, uri.port, "/", null, null).toASCIIString()
        val readable = host.removePrefix("www.").removePrefix("m.")
        val title = if (readable.contains(':') || readable.matches(Regex("[0-9.]+"))) readable
        else readable.split('.').let { labels ->
            // Without a public-suffix database, a multi-label host must stay readable:
            // guessing the penultimate label turns both google.co.uk and bbc.co.uk into "Co".
            if (labels.size > 2) readable
            else labels.first().replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        Shortcut(title, origin, "$readable:${uri.port}")
    }.getOrNull()
}
