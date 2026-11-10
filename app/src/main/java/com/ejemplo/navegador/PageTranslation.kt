package com.ejemplo.navegador

/** Resolve regional language detection against Gecko's actual supported language list. */
object PageTranslation {
    fun sourceIndex(detected: String?, supported: List<String>): Int {
        val exact = supported.indexOfFirst { it.equals(detected, ignoreCase = true) }
        if (exact >= 0) return exact
        val language = detected?.substringBefore('-')
        val primary = supported.indexOfFirst { it.equals(language, ignoreCase = true) }
        if (primary >= 0) return primary
        return supported.indexOfFirst { it == "en" }.coerceAtLeast(0)
    }
}
