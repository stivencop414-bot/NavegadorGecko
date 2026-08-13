package com.ejemplo.navegador

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object TabPreviewStore {
    private fun dir(context: Context): File =
        File(context.filesDir, "tab_previews").apply { mkdirs() }

    private fun file(context: Context, id: String) = File(dir(context), "$id.jpg")

    fun save(context: Context, id: String, bitmap: Bitmap) {
        runCatching {
            val maxWidth = 480
            val scaled = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
                Bitmap.createScaledBitmap(
                    bitmap,
                    maxWidth,
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap

            file(context, id).outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 78, it)
            }
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun load(context: Context, id: String): Bitmap? =
        runCatching {
            file(context, id).takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(it.absolutePath)
            }
        }.getOrNull()

    fun remove(context: Context, id: String) {
        runCatching { file(context, id).delete() }
    }

    fun clear(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }
}
