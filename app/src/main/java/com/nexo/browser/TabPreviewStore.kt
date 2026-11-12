package com.nexo.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Miniaturas de pestañas: JPEG pequeño en disco y una caché en memoria.
 *
 * - Solo se guarda la parte superior de la página (lo que muestra la
 *   tarjeta) en RGB_565: caben cuatro veces más miniaturas en memoria.
 * - Una captura en blanco o negro (al salir de la app o antes de pintar) no
 *   sustituye a una miniatura buena.
 * - Quien muestra miniaturas puede escuchar los cambios y actualizar solo esa
 *   tarjeta, sin recargar el selector entero.
 */
object TabPreviewStore {
    private const val MAX_WIDTH = 420
    /** Alto máximo respecto al ancho: la tarjeta solo enseña la parte de arriba. */
    private const val MAX_ASPECT = 1.35f

    private fun dir(context: Context): File =
        File(context.filesDir, "tab_previews").apply { mkdirs() }

    private fun file(context: Context, id: String) = File(dir(context), "$id.jpg")

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "NexoPreviews").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private val memoryBytes: Int =
        (Runtime.getRuntime().maxMemory() / 24L).coerceIn(4L * 1024 * 1024, 16L * 1024 * 1024).toInt()

    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** Avisos en el hilo principal con el id de la pestaña cuya miniatura cambió. */
    fun addListener(listener: (String) -> Unit) { listeners += listener }
    fun removeListener(listener: (String) -> Unit) { listeners -= listener }

    fun save(context: Context, id: String, bitmap: Bitmap) {
        val app = context.applicationContext
        io.execute { saveNow(app, id, bitmap) }
    }

    /** Vuelve a poner una miniatura conocida (p. ej. al deshacer el cierre de una pestaña). */
    fun restore(context: Context, id: String, bitmap: Bitmap) {
        memory.put(id, bitmap)
        val app = context.applicationContext
        io.execute {
            runCatching { file(app, id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        }
        notify(id)
    }

    private fun saveNow(context: Context, id: String, bitmap: Bitmap) {
        runCatching {
            if (isBlank(bitmap) && (memory.get(id) != null || file(context, id).exists())) {
                bitmap.recycle()
                return
            }
            val thumbnail = thumbnail(bitmap)
            if (thumbnail !== bitmap) bitmap.recycle()
            file(context, id).outputStream().use {
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, it)
            }
            memory.put(id, thumbnail)
            notify(id)
        }
    }

    /** Recorta la parte de arriba y la escala a [MAX_WIDTH] en RGB_565. */
    private fun thumbnail(source: Bitmap): Bitmap {
        val width = minOf(MAX_WIDTH, source.width).coerceAtLeast(1)
        val scale = width.toFloat() / source.width.coerceAtLeast(1)
        val sourceHeight = minOf(source.height, (source.width * MAX_ASPECT).toInt()).coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        Canvas(output).drawBitmap(
            source,
            Rect(0, 0, source.width, sourceHeight),
            Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        )
        return output
    }

    /**
     * Una cuadrícula de 9×9 puntos del mismo color = captura vacía (superficie
     * aún sin pintar o ya destruida).
     */
    fun isBlank(bitmap: Bitmap): Boolean = runCatching {
        if (bitmap.width < 2 || bitmap.height < 2) return true
        val first = bitmap.getPixel(0, 0)
        for (y in 0..8) for (x in 0..8) {
            val pixel = bitmap.getPixel(
                (x * (bitmap.width - 1)) / 8,
                (y * (bitmap.height - 1)) / 8
            )
            if (pixel != first) return false
        }
        true
    }.getOrDefault(false)

    private fun notify(id: String) {
        main.post { listeners.forEach { runCatching { it(id) } } }
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

    /** Decodifica de antemano las miniaturas que el selector va a mostrar. */
    fun prefetch(context: Context, ids: List<String>) {
        val app = context.applicationContext
        val missing = ids.filter { memory.get(it) == null }.take(12)
        if (missing.isEmpty()) return
        io.execute { missing.forEach { if (memory.get(it) == null) load(app, it) } }
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

    /**
     * Antes se vaciaba todo con cualquier aviso, incluido el de "la app pasó a
     * segundo plano": al volver, el selector recargaba cada miniatura del disco.
     */
    fun trimMemory(level: Int = 80 /* COMPLETE */) {
        when {
            level >= 80 /* COMPLETE */ || level == 15 /* RUNNING_CRITICAL */ -> memory.evictAll()
            level >= 60 /* MODERATE */ || level == 10 /* RUNNING_LOW */ -> memory.trimToSize(memoryBytes / 2)
            else -> Unit
        }
    }
}
