package com.nexo.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logos de sitios para accesos rápidos y pestañas. Se piden de forma anónima
 * (sin cookies ni referer, solo HTTPS) al propio sitio: primero los iconos que
 * declara su página (`apple-touch-icon`, `icon`), después `/apple-touch-icon.png`
 * y `/favicon.ico`. Sin servicios de terceros y nunca desde pestañas privadas.
 */
object SiteIconStore {
    private const val MAX_ICON_BYTES = 262_144
    private const val MAX_PAGE_BYTES = 160_000
    private const val ICON_PX = 96

    private val worker = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val attempted = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, MutableList<(Bitmap) -> Unit>>()
    private val memory = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private val LETTER_COLORS = intArrayOf(
        0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFFDC2626.toInt(),
        0xFFEA580C.toInt(), 0xFF16A34A.toInt(), 0xFF0891B2.toInt(), 0xFF4F46E5.toInt(),
        0xFF0D9488.toInt(), 0xFF9333EA.toInt()
    )

    /** Color estable por sitio para la inicial de respaldo. */
    fun letterColor(host: String): Int {
        val key = host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        var hash = 0
        for (ch in key) hash = hash * 31 + ch.code
        return LETTER_COLORS[Math.floorMod(hash, LETTER_COLORS.size)]
    }

    fun letter(host: String): String =
        host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.").firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "•"

    private fun key(origin: String): String? {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return URI("https", null, uri.host.lowercase(Locale.ROOT), uri.port, "/", null, null).toASCIIString()
    }

    /** Icono ya cargado en memoria (para pintar sin esperas al reciclar tarjetas). */
    fun cached(origin: String): Bitmap? = key(origin)?.let { memory.get(it) }

    fun load(context: Context, origin: String, onIcon: (Bitmap) -> Unit) {
        val page = key(origin) ?: return
        memory.get(page)?.let { onIcon(it); return }
        // Varias tarjetas del mismo sitio comparten una sola búsqueda.
        val waiting = inFlight.compute(page) { _, list -> (list ?: mutableListOf()).apply { add(onIcon) } }!!
        if (waiting.size > 1) return
        val dir = File(context.applicationContext.cacheDir, "site-icons")
        val name = MessageDigest.getInstance("SHA-256").digest(page.toByteArray()).joinToString("") { "%02x".format(it) }
        worker.execute {
            val icon = runCatching { find(dir, name, page) }.getOrNull()
            val callbacks = inFlight.remove(page).orEmpty()
            if (icon != null) {
                memory.put(page, icon)
                main.post { callbacks.forEach { runCatching { it(icon) } } }
            }
        }
    }

    private fun find(dir: File, name: String, page: String): Bitmap? {
        dir.mkdirs()
        val cached = File(dir, "$name.png")
        if (cached.isFile && cached.length() <= MAX_ICON_BYTES) {
            decode(cached.readBytes())?.let { return it }
        }
        val now = System.currentTimeMillis()
        val previous = attempted.putIfAbsent(page, now)
        if (previous != null) {
            if (now - previous < 3_600_000L || !attempted.replace(page, previous, now)) return null
        }
        if (attempted.size > 250) attempted.entries.removeIf { now - it.value > 3_600_000L }

        val (finalUrl, html) = fetchPage(page) ?: (page to "")
        for (candidate in SiteIconDiscovery.candidates(html, finalUrl)) {
            val bytes = fetch(candidate, MAX_ICON_BYTES, accept = "image/*", sameSiteOnly = false) ?: continue
            val bitmap = decode(bytes.second) ?: continue
            if (bitmap.width < 8 || bitmap.height < 8) continue
            val scaled = Bitmap.createScaledBitmap(bitmap, ICON_PX, ICON_PX, true)
            runCatching { cached.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
            return scaled
        }
        return null
    }

    /** Página principal del sitio (solo su cabecera importa), siguiendo redirecciones del mismo sitio. */
    private fun fetchPage(page: String): Pair<String, String>? {
        val (url, bytes) = fetch(page, MAX_PAGE_BYTES, accept = "text/html", sameSiteOnly = true, truncate = true) ?: return null
        return url to String(bytes, Charsets.UTF_8)
    }

    /**
     * GET anónimo con límite de tamaño. Sigue hasta 3 redirecciones HTTPS
     * (del mismo sitio si [sameSiteOnly]). [truncate]: lee solo el principio.
     */
    private fun fetch(start: String, limit: Int, accept: String, sameSiteOnly: Boolean, truncate: Boolean = false): Pair<String, ByteArray>? {
        var current = start
        val firstHost = runCatching { URI(start).host }.getOrNull()
        repeat(4) {
            if (!current.startsWith("https://")) return null
            val connection = java.net.URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 4000; connection.readTimeout = 4000
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("Cookie", "")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    val next = SiteIconDiscovery.resolve(current, location) ?: return null
                    if (sameSiteOnly && !SiteIconDiscovery.sameSite(firstHost, runCatching { URI(next).host }.getOrNull())) return null
                    current = next
                    return@repeat
                }
                if (status != 200) return null
                if (!truncate && connection.contentLengthLong > limit) return null
                val type = connection.contentType.orEmpty().lowercase(Locale.ROOT)
                if (accept.startsWith("image") && (type.contains("svg") || type.startsWith("text/"))) return null
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= limit) {
                        val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                if (bytes.size > limit && !truncate) return null
                return current to bytes
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val payload = IconPayload.pngOrImage(bytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
        val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 192).coerceAtLeast(1) }
        return BitmapFactory.decodeByteArray(payload, 0, payload.size, options)
    }

    fun trimMemory() = memory.evictAll()
}
