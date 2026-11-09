package com.ejemplo.navegador

import java.net.URI
import java.net.URLEncoder

/** Builds a link to the public website service, never an undocumented translation API. */
object PageTranslation {
    private val targets = setOf("es", "en", "pt", "fr", "de", "it", "ja", "ko")

    fun urlFor(pageUrl: String, target: String): String? {
        if (!UrlSafety.isPublicTranslationCandidate(pageUrl)) return null
        val language = target.takeIf { it in targets } ?: "es"
        return "https://translate.google.com/translate?sl=auto&tl=$language&hl=$language&u=" +
            URLEncoder.encode(pageUrl, "UTF-8")
    }

    fun isTranslationPage(pageUrl: String): Boolean {
        val host = runCatching { URI(pageUrl).host?.lowercase() }.getOrNull() ?: return false
        return host == "translate.google.com" || host.endsWith(".translate.goog")
    }
}
