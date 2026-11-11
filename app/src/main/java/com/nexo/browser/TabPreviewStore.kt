package com.nexo.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File

/**
 * Miniaturas de pestañas: JPEG pequeño en disco y una caché en memoria
 * limitada, para que el selector de pestañas no decodifique imágenes en el
 * hilo principal cada vez que se desplaza.
 */
object TabPreviewStore {
    private const val MAX_WIDTH = 480
    private const val MEMORY_BYTES = 6 * 1024 * 1024

    private fun dir(context: Context): File =
        File(context.filesDir, "tab_previews").apply { mkdirs() }

    private fun file(context: Context, id: String) = File(dir(context), "$id.jpg")

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "NexoPreviews").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun save(context: Context, id: String, bitmap: Bitmap) {
        val app = context.applicationContext
        io.execute { saveNow(app, id, bitmap) }
    }

    private fun saveNow(context: Context, id: String, bitmap: Bitmap) {
        runCatching {
            val scaled = if (bitmap.width > MAX_WIDTH) {
                val ratio = MAX_WIDTH.toFloat() / bitmap.width.toFloat()
                Bitmap.createScaledBitmap(
                    bitmap,
                    MAX_WIDTH,
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                ).also { bitmap.recycle() }
            } else bitmap

            file(context, id).outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 78, it)
            }
            memory.put(id, scaled)
        }
    }

    /** Miniatura ya decodificada, si está en memoria. */
    fun cached(id: String): Bitmap? = memory.get(id)

    /** Decodifica la miniatura fuera del hilo principal. */
    fun loadAsync(context: Context, id: String, onLoaded: (Bitmap?) -> Unit) {
        memory.get(id)?.let {
            onLoaded(it)
            return
        }
        val app = context.applicationContext
        io.execute {
            val bitmap = load(app, id)
            main.post { onLoaded(bitmap) }
        }
    }

    private fun load(context: Context, id: String): Bitmap? =
        runCatching {
            file(context, id).takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(
                    it.absolutePath,
                    // JPEG sin transparencia: RGB_565 usa la mitad de memoria.
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                )
            }
        }.getOrNull()?.also { memory.put(id, it) }

    fun remove(context: Context, id: String) {
        memory.remove(id)
        val app = context.applicationContext
        io.execute { runCatching { file(app, id).delete() } }
    }

    fun clear(context: Context) {
        memory.evictAll()
        runCatching { dir(context).deleteRecursively() }
    }

    fun trimMemory() {
        memory.evictAll()
    }
}
