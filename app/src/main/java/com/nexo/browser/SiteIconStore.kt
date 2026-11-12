package com.nexo.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Same-origin, anonymous favicon lookup. No third-party service or private-tab requests. */
object SiteIconStore {
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val attempted = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun load(context: Context, origin: String, onIcon: (Bitmap) -> Unit) {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return
        val url = URI("https", null, uri.host, uri.port, "/favicon.ico", null, null).toASCIIString()
        val dir = File(context.applicationContext.cacheDir, "site-icons")
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        worker.execute {
            runCatching {
                dir.mkdirs()
                val cached = File(dir, "$name.png")
                if (cached.isFile && cached.length() <= 262144L) {
                    decode(cached.readBytes())?.let { icon -> main.post { onIcon(icon) }; return@execute }
                }
                val now = System.currentTimeMillis()
                val previous = attempted.putIfAbsent(url, now)
                if (previous != null) {
                    if (now - previous < 3600000L || !attempted.replace(url, previous, now)) return@execute
                }
                if (attempted.size > 250) attempted.entries.removeIf { now - it.value > 3600000L }
                val connection = java.net.URL(url).openConnection() as HttpURLConnection
                val bytes = try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 3000; connection.readTimeout = 3000
                    connection.setRequestProperty("Accept", "image/*")
                    connection.setRequestProperty("Cookie", "")
                    if (connection.responseCode != 200 || connection.contentLengthLong > 262144L) return@execute
                    connection.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (out.size() <= 262144) {
                            val read = input.read(buffer, 0, minOf(buffer.size, 262145 - out.size()))
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                } finally { connection.disconnect() }
                if (bytes.size > 262144) return@execute
                val bitmap = decode(bytes) ?: return@execute
                val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
                cached.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(150)?.forEach { it.delete() }
                main.post { onIcon(scaled) }
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val payload = IconPayload.pngOrImage(bytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
        val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1) }
        return BitmapFactory.decodeByteArray(payload, 0, payload.size, options)
    }
}
