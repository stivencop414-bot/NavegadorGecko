package com.ejemplo.navegador

/** Names are presentation data; never paths, identifiers for containers or network rules. */
object TabGroupRules {
    fun name(value: String?): String? = value?.filterNot { it.isISOControl() }?.trim()
        ?.take(40)?.takeIf { it.isNotBlank() }
}
