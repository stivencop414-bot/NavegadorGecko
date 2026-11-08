package com.ejemplo.navegador

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Carga iconos remotos (tienda de extensiones) con límites de tamaño. */
object RemoteImageLoader {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_DIMENSION = 512

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "NexoImageLoader").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, target: ImageView) {
        target.setImageResource(R.drawable.ic_extension_placeholder)
        target.tag = url
        if (url.isBlank() || UrlSafety.scheme(url) != "https") return

        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }

        pool.execute {
            val bitmap = runCatching { fetch(url) }.getOrNull() ?: return@execute

            cache.put(url, bitmap)
            main.post {
                if (target.tag == url) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.useCaches = true
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "NexoBrowser")
            if (connection.responseCode !in 200..299) return null

            // Una redirección no debe sacar la carga de HTTPS.
            if (connection.url.protocol != "https") return null

            val bytes = connection.inputStream.use { readLimited(it) } ?: return null
            return decodeBounded(bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_BYTES) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
