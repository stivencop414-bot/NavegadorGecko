package com.nexo.browser

import java.net.URI

/** Origen (esquema://host:puerto) al que se liga una solicitud de permiso. Puro: probado en la JVM. */
object PermissionScope {
    fun origin(url: String): String? = runCatching {
        val uri = URI(UrlSafety.stripCredentials(url.trim()))
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = when {
            uri.port == -1 -> ""
            scheme == "https" && uri.port == 443 || scheme == "http" && uri.port == 80 -> ""
            else -> ":${uri.port}"
        }
        "$scheme://$host$port"
    }.getOrNull()
}
