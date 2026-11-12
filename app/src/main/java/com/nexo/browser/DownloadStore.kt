package com.nexo.browser

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    private const val MAX_ENTRIES = 250

    /** Margen libre que se deja siempre en el almacenamiento. */
    private const val FREE_SPACE_MARGIN = 64L * 1024L * 1024L

    private val io = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "NexoDownloadIO").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val fileLock = Any()

    /**
     * Descarga una URL (menú contextual) a través de Gecko: envía las cookies
     * de la sesión, respeta el modo privado y guarda en la misma carpeta que
     * el resto de descargas, de modo que aparece en la pantalla Descargas.
     */
    fun download(
        context: Context,
        url: String,
        isPrivate: Boolean,
        referrer: String?,
        callback: (Boolean, String) -> Unit
    ) {
        if (!UrlSafety.isWebUrl(url)) {
            callback(false, "Este recurso no se puede descargar directamente")
            return
        }

        val app = context.applicationContext
        val request = WebRequest.Builder(url)
            .apply {
                if (referrer != null && UrlSafety.isWebUrl(referrer)) referrer(referrer)
            }
            .build()

        val flags =
            if (isPrivate) GeckoWebExecutor.FETCH_FLAGS_PRIVATE
            else GeckoWebExecutor.FETCH_FLAGS_NONE

        runCatching {
            GeckoRuntimeHolder.webExecutor(app)
                .fetch(request, flags)
                .accept(
                    { response ->
                        if (response == null) {
                            main.post { callback(false, "Descarga fallida: sin respuesta") }
                        } else if (response.statusCode !in 200..299) {
                            runCatching { response.body?.close() }
                            main.post {
                                callback(false, "Descarga fallida: HTTP ${response.statusCode}")
                            }
                        } else {
                            saveResponse(app, response, callback)
                        }
                    },
                    { error ->
                        main.post {
                            callback(false, "Descarga fallida: ${error?.message ?: "error de red"}")
                        }
                    }
                )
        }.onFailure {
            callback(false, "Descarga fallida: ${it.message}")
        }
    }

    fun saveResponse(
        context: Context,
        response: WebResponse,
        callback: (Boolean, String) -> Unit
    ) {
        val app = context.applicationContext

        io.execute {
            var file: File? = null

            runCatching {
                val body = response.body ?: error("Sin cuerpo descargable")
                val headers = response.headers
                val mime = header(headers, "Content-Type")
                    ?.substringBefore(";")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "application/octet-stream" }

                val requested = resolveFileName(
                    response.uri,
                    header(headers, "Content-Disposition")
                )

                val folder = downloadsFolder(app)
                val declaredLength = header(headers, "Content-Length")?.trim()?.toLongOrNull()

                if (declaredLength != null && declaredLength > folder.usableSpace - FREE_SPACE_MARGIN) {
                    runCatching { body.close() }
                    error("No hay espacio suficiente para este archivo")
                }

                val target = reserveFile(folder, requested)
                file = target

                body.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var sinceCheck = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)

                            // Comprobar espacio libre cada ~32 MB.
                            sinceCheck += count
                            if (sinceCheck >= 32L * 1024L * 1024L) {
                                sinceCheck = 0L
                                if (folder.usableSpace < FREE_SPACE_MARGIN) {
                                    throw IOException("Almacenamiento casi lleno")
                                }
                            }
                        }
                    }
                }

                add(
                    app,
                    DownloadEntry(
                        UUID.randomUUID().toString(),
                        target.name,
                        target.absolutePath,
                        redactSourceUrl(response.uri),
                        mime,
                        target.length(),
                        System.currentTimeMillis()
                    )
                )

                main.post { callback(true, "Descargado: ${target.name}") }
            }.onFailure {
                // No dejar archivos a medio descargar.
                file?.let { partial -> runCatching { partial.delete() } }
                main.post { callback(false, "Descarga fallida: ${it.message}") }
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

    /** Devuelve el archivo solo si sigue dentro de la carpeta de descargas. */
    fun fileFor(context: Context, entry: DownloadEntry): File? =
        runCatching {
            val root = downloadsFolder(context).canonicalFile
            val file = File(entry.filePath).canonicalFile
            file.takeIf { isChildOf(root, it) && it.isFile }
        }.getOrNull()

    @Synchronized
    fun remove(context: Context, id: String) {
        val items = list(context).toMutableList()
        val item = items.firstOrNull { it.id == id } ?: return

        fileFor(context, item)?.let { runCatching { it.delete() } }

        items.remove(item)
        save(context, items)
    }

    @Synchronized
    fun clearList(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    @Synchronized
    private fun add(context: Context, entry: DownloadEntry) {
        val items = list(context).toMutableList()
        items.add(0, entry)
        save(context, items.take(MAX_ENTRIES))
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

    private fun header(headers: Map<String, String>, name: String): String? =
        headers[name] ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun resolveFileName(url: String, disposition: String?): String {
        val fromHeader = FileNames.fromContentDisposition(disposition)
        if (fromHeader != null) return FileNames.sanitize(fromHeader)

        return FileNames.sanitize(
            FileNames.fromUrl(url) ?: "descarga-${System.currentTimeMillis()}.bin"
        )
    }

    /**
     * Crea el archivo de destino de forma atómica (createNewFile) para que
     * dos descargas simultáneas con el mismo nombre no se sobrescriban.
     */
    private fun reserveFile(folder: File, requested: String): File {
        val root = folder.canonicalFile
        val safeRequested = FileNames.sanitize(requested)
        val dot = safeRequested.lastIndexOf('.')
        val base = if (dot > 0) safeRequested.substring(0, dot) else safeRequested
        val ext = if (dot > 0) safeRequested.substring(dot) else ""

        fun candidate(name: String): File {
            val file = File(root, name).canonicalFile
            require(isChildOf(root, file)) { "Nombre de archivo inseguro" }
            return file
        }

        synchronized(fileLock) {
            var file = candidate(safeRequested)
            var n = 1
            while (!file.createNewFile()) {
                if (n > 999) error("Demasiados archivos con el mismo nombre")
                file = candidate("$base ($n)$ext")
                n++
            }
            return file
        }
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
