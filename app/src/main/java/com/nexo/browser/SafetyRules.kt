package com.nexo.browser

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.net.URI

/**
 * Reglas de seguridad para URLs. Kotlin puro (sin android.*) para que
 * puedan probarse con tests unitarios en la JVM.
 *
 * El esquema se calcula imitando al parser de URLs del navegador:
 * se descartan tabs/saltos de línea internos y los caracteres de control
 * iniciales, así "java\tscript:" o " javascript:" se reconocen igual que
 * "javascript:".
 */
object UrlSafety {
    private val schemePattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** Esquemas que la propia app (barra de direcciones, historial, opciones) puede abrir. */
    private val appSchemes = setOf("http", "https", "about", "resource", "moz-extension")

    /** Esquemas aceptados desde otras apps a través del Activity exportado. */
    private val externalSchemes = setOf("http", "https")

    /** Esquemas que una extensión puede abrir en una pestaña nueva. */
    private val extensionTabSchemes = setOf("http", "https", "moz-extension")

    private val sensitiveParam = Regex(
        "(?i)^(access_?token|refresh_?token|id_?token|token|auth|authorization|" +
            "code|state|session|session_?id|sid|secret|client_?secret|api_?key|" +
            "password|passwd|pwd|signature|sig|x-amz-[a-z0-9-]+)$"
    )

    fun scheme(url: String): String? {
        val cleaned = url
            .filterNot { it == '\t' || it == '\n' || it == '\r' }
            .trimStart { it <= ' ' }
        return schemePattern.find(cleaned)?.groupValues?.get(1)?.lowercase()
    }

    fun isAppNavigable(url: String): Boolean = scheme(url) in appSchemes

    fun isExternallyOpenable(url: String): Boolean = scheme(url) in externalSchemes

    fun isWebUrl(url: String): Boolean = scheme(url) in externalSchemes

    fun isExtensionTabUrl(url: String): Boolean =
        url.trim().equals("about:blank", ignoreCase = true) ||
            scheme(url) in extensionTabSchemes

    /** Sharing to a public translation service is narrower than in-browser navigation. */
    fun isPublicTranslationCandidate(url: String): Boolean {
        if (url.any { it <= ' ' } || !isWebUrl(url)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (uri.rawUserInfo != null || uri.isOpaque || host.length > 253) return false
        if (!host.contains('.') || host.contains(':') || host.all { it.isDigit() || it == '.' }) return false
        if (listOf("localhost", "local", "localdomain", "lan", "home.arpa", "internal", "test", "invalid", "onion").any {
                host == it || host.endsWith(".$it")
            }) return false
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return false
        // Reject known credentials instead of silently handing them to another service.
        return redactSensitiveQuery(url) == url
    }

    /**
     * Esquemas que Gecko nunca debe pasar a otra app: los que carga él mismo
     * y los que permitirían ejecutar código o leer archivos locales.
     */
    private val neverExternalSchemes = setOf(
        "http", "https", "about", "resource", "moz-extension", "chrome",
        "javascript", "data", "blob", "file", "content", "view-source",
        "jar", "ws", "wss", "moz-icon", "moz-page-thumb", "wyciwyg"
    )

    /** Enlaces que corresponden a otra app (tel:, mailto:, intent:, market:…). */
    fun isExternalAppUrl(url: String): Boolean {
        val scheme = scheme(url) ?: return false
        return scheme !in neverExternalSchemes
    }

    /**
     * Quita usuario y contraseña de la autoridad ("https://user:pass@host/…"
     * → "https://host/…"). Nunca se guardan ni se reenvían credenciales en URLs.
     */
    fun stripCredentials(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val start = schemeEnd + 3
        var end = url.length
        for (i in start until url.length) if (url[i] == '/' || url[i] == '?' || url[i] == '#') { end = i; break }
        val at = url.lastIndexOf('@', end - 1)
        if (at < start) return url
        return url.substring(0, start) + url.substring(at + 1)
    }

    /** Lo que se guarda en el historial: sin credenciales en la autoridad ni en la consulta. */
    fun forHistory(url: String): String = redactSensitiveQuery(stripCredentials(url))

    /**
     * Texto que no debe salir hacia un servicio de sugerencias: direcciones
     * (con esquema, dominio o IP), nombres locales y cualquier cosa con
     * credenciales o parámetros sensibles.
     */
    fun isPrivateQuery(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty()) return true
        if (scheme(text) != null && text.contains(':')) return true
        if (text.contains("://") || text.contains('@') && !text.contains(' ')) return true
        val first = text.substringBefore(' ').substringBefore('/').lowercase()
        if (!text.contains(' ') && (first.contains('.') || first.contains(':'))) return true
        if (first == "localhost" || first.endsWith(".local") || first.endsWith(".lan") || first.endsWith(".internal")) return true
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){1,3}").containsMatchIn(first)) return true
        if (Regex("(?i)\\b(password|passwd|token|api[_-]?key|secret)\\s*[=:]").containsMatchIn(text)) return true
        return false
    }

    /**
     * Quita de la URL los parámetros que suelen llevar credenciales
     * (tokens OAuth, sesiones, firmas) y los fragmentos con pares clave=valor.
     * Se usa antes de guardar el historial en disco.
     */
    fun redactSensitiveQuery(url: String): String {
        val hashIndex = url.indexOf('#')
        val beforeHash = if (hashIndex >= 0) url.substring(0, hashIndex) else url
        val fragment = if (hashIndex >= 0) url.substring(hashIndex + 1) else null

        val queryIndex = beforeHash.indexOf('?')
        val base = if (queryIndex >= 0) beforeHash.substring(0, queryIndex) else beforeHash
        val query = if (queryIndex >= 0) beforeHash.substring(queryIndex + 1) else null

        val keptQuery = query
            ?.split('&')
            ?.filter { part ->
                if (part.isEmpty()) return@filter false
                val name = FileNames.percentDecode(part.substringBefore('='), plusAsSpace = true)
                !sensitiveParam.matches(name)
            }
            ?.joinToString("&")

        val keptFragment = fragment?.takeUnless { it.contains('=') }

        return buildString {
            append(base)
            if (!keptQuery.isNullOrEmpty()) append('?').append(keptQuery)
            if (!keptFragment.isNullOrEmpty()) append('#').append(keptFragment)
        }
    }
}

/** Nombres de archivo seguros para descargas. */
object FileNames {
    private const val MAX_LENGTH = 120
    private val forbidden = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    fun sanitize(value: String, fallback: String = "descarga.bin"): String {
        val clean = value
            .replace(forbidden, "_")
            .replace("..", "_")
            .trim()
            .trimStart('.')
            .trim()

        if (clean.isBlank() || clean.all { it == '_' || it == '.' }) return fallback
        if (clean.length <= MAX_LENGTH) return clean

        // Recortar conservando la extensión.
        val dot = clean.lastIndexOf('.')
        val ext = if (dot > 0 && clean.length - dot <= 12) clean.substring(dot) else ""
        return clean.take(MAX_LENGTH - ext.length) + ext
    }

    /**
     * Extrae el nombre de archivo de Content-Disposition.
     * Prioriza filename* (RFC 5987, p. ej. UTF-8''informe%20%C3%B1.pdf)
     * sobre filename.
     */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null

        val params = splitParams(header)

        params["filename*"]?.let { extended ->
            val parts = extended.split('\'', limit = 3)
            if (parts.size == 3) {
                val charset = runCatching { Charset.forName(parts[0].ifBlank { "UTF-8" }) }
                    .getOrDefault(Charsets.UTF_8)
                val decoded = percentDecode(parts[2], plusAsSpace = false, charset = charset)
                if (decoded.isNotBlank()) return decoded
            }
        }

        return params["filename"]?.takeIf { it.isNotBlank() }
    }

    fun fromUrl(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val last = path.substringAfterLast('/')
        if (last.isBlank()) return null
        return percentDecode(last, plusAsSpace = false).takeIf { it.contains('.') }
    }

    private fun splitParams(header: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = header.indexOf(';')
        if (i < 0) return result

        while (i < header.length) {
            // Saltar ';' y espacios.
            while (i < header.length && (header[i] == ';' || header[i].isWhitespace())) i++
            val eq = header.indexOf('=', i)
            if (eq < 0) break
            val name = header.substring(i, eq).trim().lowercase()
            var j = eq + 1
            while (j < header.length && header[j].isWhitespace()) j++

            val value: String
            if (j < header.length && header[j] == '"') {
                val sb = StringBuilder()
                j++
                while (j < header.length && header[j] != '"') {
                    if (header[j] == '\\' && j + 1 < header.length) j++
                    sb.append(header[j])
                    j++
                }
                value = sb.toString()
                j++ // comilla de cierre
                val next = header.indexOf(';', j)
                i = if (next < 0) header.length else next
            } else {
                val next = header.indexOf(';', j)
                val end = if (next < 0) header.length else next
                value = header.substring(j, end).trim()
                i = end
            }

            if (name.isNotEmpty() && name !in result) result[name] = value
        }
        return result
    }

    fun percentDecode(
        value: String,
        plusAsSpace: Boolean,
        charset: Charset = Charsets.UTF_8
    ): String {
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length &&
                    hex(value[i + 1]) >= 0 && hex(value[i + 2]) >= 0 -> {
                    out.write(hex(value[i + 1]) * 16 + hex(value[i + 2]))
                    i += 3
                }
                c == '+' && plusAsSpace -> {
                    out.write(' '.code)
                    i++
                }
                else -> {
                    // Copiar el code point completo (incluye pares sustitutos).
                    val end = if (
                        c.isHighSurrogate() &&
                        i + 1 < value.length &&
                        value[i + 1].isLowSurrogate()
                    ) i + 2 else i + 1
                    val bytes = value.substring(i, end).toByteArray(Charsets.UTF_8)
                    out.write(bytes, 0, bytes.size)
                    i = end
                }
            }
        }
        return String(out.toByteArray(), charset)
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
