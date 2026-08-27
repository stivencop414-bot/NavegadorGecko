package com.ejemplo.navegador

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors

data class DownloadEntry(
    val id: String,
    val fileName: String,
    val filePath: String,
    val sourceUrl: String,
    val mimeType: String,
    val size: Long,
    val timestamp: Long
)

object DownloadStore {
    private const val PREF = "nexo_downloads"
    private const val KEY = "entries"
    private val io = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "NexoDownloadIO").apply { isDaemon = true }
    }

    fun saveResponse(
        context: Context,
        response: WebResponse,
        callback: (Boolean, String) -> Unit
    ) {
        val app = context.applicationContext

        io.execute {
            runCatching {
                val body = response.body ?: error("Sin cuerpo descargable")
                val headers = response.headers
                val mime = headers["Content-Type"]
                    ?.substringBefore(";")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "application/octet-stream" }

                val requested = resolveFileName(
                    response.uri,
                    headers["Content-Disposition"]
                )

                val folder = downloadsFolder(app)
                val file = uniqueFile(folder, requested)

                body.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output, 512 * 1024)
                    }
                }

                add(
                    app,
                    DownloadEntry(
                        UUID.randomUUID().toString(),
                        file.name,
                        file.absolutePath,
                        redactSourceUrl(response.uri),
                        mime,
                        file.length(),
                        System.currentTimeMillis()
                    )
                )

                Handler(Looper.getMainLooper()).post {
                    callback(true, "Descargado: ${file.name}")
                }
            }.onFailure {
                Handler(Looper.getMainLooper()).post {
                    callback(false, "Descarga fallida: ${it.message}")
                }
            }
        }
    }

    @Synchronized
    fun list(context: Context): List<DownloadEntry> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"

        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        DownloadEntry(
                            o.optString("id"),
                            o.optString("fileName"),
                            o.optString("filePath"),
                            o.optString("sourceUrl"),
                            o.optString("mimeType"),
                            o.optLong("size"),
                            o.optLong("timestamp")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val items = list(context).toMutableList()
        val item = items.firstOrNull { it.id == id } ?: return

        runCatching {
            val root = downloadsFolder(context).canonicalFile
            val file = File(item.filePath).canonicalFile
            if (isChildOf(root, file)) file.delete()
        }

        items.remove(item)
        save(context, items)
    }

    fun clearList(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    @Synchronized
    private fun add(context: Context, entry: DownloadEntry) {
        val items = list(context).toMutableList()
        items.add(0, entry)
        save(context, items.take(250))
    }

    private fun save(context: Context, items: List<DownloadEntry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("fileName", it.fileName)
                put("filePath", it.filePath)
                put("sourceUrl", it.sourceUrl)
                put("mimeType", it.mimeType)
                put("size", it.size)
                put("timestamp", it.timestamp)
            })
        }

        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun downloadsFolder(context: Context): File {
        val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        folder.mkdirs()
        return folder
    }

    private fun resolveFileName(url: String, disposition: String?): String {
        val fromHeader = disposition
            ?.substringAfter("filename=", "")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }

        if (fromHeader != null) return sanitize(fromHeader)

        val fromUrl = runCatching {
            URLDecoder.decode(
                url.substringBefore("?").substringAfterLast("/"),
                "UTF-8"
            )
        }.getOrNull()

        return sanitize(
            fromUrl?.takeIf { it.contains(".") }
                ?: "descarga-${System.currentTimeMillis()}.bin"
        )
    }

    private fun sanitize(value: String): String =
        value
            .replace(Regex("""[\/:*?"<>|\p{Cntrl}]"""), "_")
            .replace("..", "_")
            .trim()
            .trimStart('.')
            .take(120)
            .ifBlank { "descarga.bin" }

    private fun uniqueFile(folder: File, requested: String): File {
        val root = folder.canonicalFile
        val safeRequested = sanitize(requested)
        val dot = safeRequested.lastIndexOf('.')
        val base = if (dot > 0) safeRequested.substring(0, dot) else safeRequested
        val ext = if (dot > 0) safeRequested.substring(dot) else ""

        fun candidate(name: String): File {
            val file = File(root, name).canonicalFile
            require(isChildOf(root, file)) { "Nombre de archivo inseguro" }
            return file
        }

        var file = candidate(safeRequested)
        var n = 1
        while (file.exists()) {
            file = candidate("$base ($n)$ext")
            n++
        }
        return file
    }

    private fun isChildOf(root: File, file: File): Boolean {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        return file.canonicalPath.startsWith(rootPath)
    }

    private fun redactSourceUrl(url: String): String =
        runCatching {
            val uri = Uri.parse(url)
            uri.buildUpon()
                .clearQuery()
                .fragment(null)
                .build()
                .toString()
        }.getOrDefault("")
}
