package com.ejemplo.navegador

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object RemoteImageLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, target: ImageView) {
        target.setImageResource(R.drawable.ic_extension_placeholder)
        if (url.isBlank()) return
        target.tag = url

        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }

        pool.execute {
            val bitmap = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 7000
                connection.useCaches = true
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "NexoBrowser/0.9")
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
            }.getOrNull() ?: return@execute

            cache.put(url, bitmap)
            main.post {
                if (target.tag == url) target.setImageBitmap(bitmap)
            }
        }
    }
}
