package com.ejemplo.navegador

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class DownloadEntry(
    val id: String,
    val fileName: String,
    val filePath: String,
    val sourceUrl: String,
    val mimeType: String,
    val size: Long,
    val timestamp: Long
)

/** Descarga en curso: progreso para la pantalla Descargas y la notificación. */
class ActiveDownload internal constructor(val id: String, val fileName: String, val total: Long) {
    val received = AtomicLong(0)
    val startedAt: Long = SystemClock.elapsedRealtime()
    @Volatile var connections = 1
        internal set
    @Volatile var bytesPerSecond = 0L
        internal set
    @Volatile var cancelled = false
        internal set
    internal var sampleBytes = 0L
    internal var sampleAt = startedAt

    val percent: Int
        get() = if (total > 0) ((received.get() * 100) / total).toInt().coerceIn(0, 100) else -1
}

/**
 * Datos para pedir partes del archivo con la misma sesión (cookies y modo
 * privado). Solo se pasa cuando la pestaña no usa proxy ni Burner: esas
 * descargas nunca repiten la petición fuera de su ruta.
 */
data class DownloadSource(val isPrivate: Boolean, val referrer: String?)

object DownloadStore {
    private const val PREF = "nexo_downloads"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 250

    /** Margen libre que se deja siempre en el almacenamiento. */
    private const val FREE_SPACE_MARGIN = 64L * 1024L * 1024L
    private const val BUFFER_BYTES = 512 * 1024
    private const val MIN_WRITE_BYTES = 64 * 1024
    private const val MAX_PART_RETRIES = 3

    private const val CHANNEL = "nexo_downloads"
    private const val NOTIFY_PROGRESS = 7100
    private const val NOTIFY_DONE = 7101

    private val io = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "NexoDownloadIO").apply { isDaemon = true }
    }
    // Tramos en paralelo de las descargas grandes.
    private val parts = Executors.newCachedThreadPool { task ->
        Thread(task, "NexoDownloadPart").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val fileLock = Any()
    private val running = ConcurrentHashMap<String, ActiveDownload>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var appContext: Context? = null

    // ---------------------------------------------------------------
    // Descargas en curso
    // ---------------------------------------------------------------

    fun activeDownloads(): List<ActiveDownload> = running.values.sortedBy { it.startedAt }

    fun cancel(id: String) {
        running[id]?.cancelled = true
    }

    /** Aviso periódico (hilo principal) mientras hay descargas en curso. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun progressText(context: Context, job: ActiveDownload): String {
        val received = Formatter.formatShortFileSize(context, job.received.get())
        val size = if (job.total > 0) "$received de ${Formatter.formatShortFileSize(context, job.total)}" else received
        val speed = if (job.bytesPerSecond > 0) Formatter.formatShortFileSize(context, job.bytesPerSecond) + "/s" else null
        val connections = if (job.connections > 1) "${job.connections} conexiones" else null
        return listOfNotNull(job.percent.takeIf { it >= 0 }?.let { "$it %" }, size, speed, connections).joinToString(" · ")
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            running.values.forEach { job ->
                val elapsed = (now - job.sampleAt).coerceAtLeast(1)
                val bytes = job.received.get()
                val instant = (bytes - job.sampleBytes) * 1000 / elapsed
                // Media suavizada: la cifra no salta en cada muestra.
                job.bytesPerSecond = if (job.bytesPerSecond == 0L) instant else (job.bytesPerSecond * 2 + instant) / 3
                job.sampleBytes = bytes
                job.sampleAt = now
            }
            appContext?.let { showProgress(it) }
            listeners.forEach { runCatching { it() } }
            if (running.isNotEmpty()) main.postDelayed(this, 800L)
        }
    }

    private fun startTicker(context: Context) {
        appContext = context.applicationContext
        main.post {
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, 400L)
        }
    }

    // ---------------------------------------------------------------
    // Iniciar descargas
    // ---------------------------------------------------------------

    /**
     * Descarga una URL (menú contextual) a través de Gecko: envía las cookies
     * de la sesión, respeta el modo privado y guarda en la misma carpeta que
     * el resto de descargas, de modo que aparece en la pantalla Descargas.
     * [parallel]: permite repartir archivos grandes en varias conexiones.
     */
    fun download(
        context: Context,
        url: String,
        isPrivate: Boolean,
        referrer: String?,
        parallel: Boolean = true,
        callback: (Boolean, String) -> Unit
    ) {
        if (!UrlSafety.isWebUrl(url)) {
            callback(false, "Este recurso no se puede descargar directamente")
            return
        }

        val app = context.applicationContext
        val request = WebRequest.Builder(url)
            .cacheMode(WebRequest.CACHE_MODE_NO_STORE)
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
                            saveResponse(app, response, if (parallel) DownloadSource(isPrivate, referrer) else null, callback)
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
    ) = saveResponse(context, response, null, callback)

    fun saveResponse(
        context: Context,
        response: WebResponse,
        source: DownloadSource?,
        callback: (Boolean, String) -> Unit
    ) {
        val app = context.applicationContext
        io.execute { receive(app, response, source, callback) }
    }

    /** Captura de la página como PNG en Descargas. */
    fun saveBitmap(context: Context, bitmap: Bitmap, title: String, callback: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        io.execute {
            var file: File? = null
            runCatching {
                val target = reserveFile(downloadsFolder(app), FileNames.sanitize("$title.png", "captura.png"))
                file = target
                target.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                add(app, DownloadEntry(UUID.randomUUID().toString(), target.name, target.absolutePath, "", "image/png", target.length(), System.currentTimeMillis()))
                main.post { callback(true, "Captura guardada en Descargas: ${target.name}") }
            }.onFailure {
                file?.let { partial -> runCatching { partial.delete() } }
                main.post { callback(false, "No se pudo guardar la captura: ${it.message}") }
            }
        }
    }

    /** Guarda un flujo ya abierto (p. ej. la página como PDF). */
    fun saveStream(
        context: Context,
        input: InputStream,
        fileName: String,
        mime: String,
        sourceUrl: String,
        callback: (Boolean, String) -> Unit
    ) {
        val app = context.applicationContext
        io.execute {
            var file: File? = null
            runCatching {
                val folder = downloadsFolder(app)
                val target = reserveFile(folder, FileNames.sanitize(fileName))
                file = target
                val job = ActiveDownload(UUID.randomUUID().toString(), target.name, -1)
                input.use { stream ->
                    RandomAccessFile(target, "rw").use { raf -> copy(stream, raf.channel, 0, { Long.MAX_VALUE }, job, folder) }
                }
                add(app, DownloadEntry(UUID.randomUUID().toString(), target.name, target.absolutePath, redactSourceUrl(sourceUrl), mime, target.length(), System.currentTimeMillis()))
                main.post { callback(true, "Guardado en Descargas: ${target.name}") }
            }.onFailure {
                file?.let { partial -> runCatching { partial.delete() } }
                main.post { callback(false, "No se pudo guardar: ${it.message}") }
            }
        }
    }

    // ---------------------------------------------------------------
    // Recepción
    // ---------------------------------------------------------------

    private fun receive(app: Context, response: WebResponse, source: DownloadSource?, callback: (Boolean, String) -> Unit) {
        var file: File? = null
        var job: ActiveDownload? = null
        try {
            val body = response.body ?: error("Sin cuerpo descargable")
            val headers = response.headers
            val mime = header(headers, "Content-Type")
                ?.substringBefore(";")
                ?.trim()
                .orEmpty()
                .ifBlank { "application/octet-stream" }

            val requested = resolveFileName(response.uri, header(headers, "Content-Disposition"))
            val folder = downloadsFolder(app)
            val declared = header(headers, "Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
            // Con compresión de transporte el tamaño declarado no es el del archivo.
            val encoding = header(headers, "Content-Encoding")?.trim()?.lowercase()
            val total = if (encoding.isNullOrEmpty() || encoding == "identity") declared else -1L

            if (declared > 0 && declared > folder.usableSpace - FREE_SPACE_MARGIN) {
                runCatching { body.close() }
                error("No hay espacio suficiente para este archivo")
            }

            val target = reserveFile(folder, requested)
            file = target
            val active = ActiveDownload(UUID.randomUUID().toString(), target.name, total)
            job = active
            running[active.id] = active
            startTicker(app)

            RandomAccessFile(target, "rw").use { raf ->
                if (total > 0) preallocate(raf, total)
                val channel = raf.channel
                val rangeable = source != null && total > 0 && UrlSafety.isWebUrl(response.uri) &&
                    header(headers, "Accept-Ranges")?.trim()?.equals("bytes", ignoreCase = true) == true
                if (rangeable && DownloadPlan.canSplit(headers, total)) {
                    receiveSplit(app, active, response, body, channel, folder, source!!)
                } else {
                    receiveSequential(app, active, response, body, channel, folder, if (rangeable) source else null)
                }
                if (total > 0 && raf.length() != total) raf.setLength(total)
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
            showDone(app, target.name)
            main.post { callback(true, "Descargado: ${target.name}") }
        } catch (error: Throwable) {
            // No dejar archivos a medio descargar.
            job?.cancelled = true
            file?.let { partial -> runCatching { partial.delete() } }
            val cancelled = error is CancellationException
            main.post { callback(false, if (cancelled) "Descarga cancelada" else "Descarga fallida: ${error.message ?: "error de red"}") }
        } finally {
            job?.let { running.remove(it.id) }
            main.post {
                appContext?.let { showProgress(it) }
                listeners.forEach { runCatching { it() } }
            }
        }
    }

    /**
     * Una conexión. Si se corta y el servidor admite rangos ([source] no
     * nulo), se reanuda desde el último byte guardado en vez de fallar.
     */
    private fun receiveSequential(
        app: Context,
        job: ActiveDownload,
        response: WebResponse,
        body: InputStream,
        channel: FileChannel,
        folder: File,
        source: DownloadSource?
    ) {
        var position = 0L
        try {
            body.use { position = copy(it, channel, 0, { Long.MAX_VALUE }, job, folder) }
            if (job.total > 0 && position < job.total) throw IOException("conexión interrumpida")
        } catch (error: IOException) {
            if (source == null || job.total <= 0 || job.cancelled) {
                throw if (job.total > 0 && position < job.total) IOException("Descarga incompleta (${error.message})", error) else error
            }
            position = job.received.get()
            fetchPart(app, job, response, channel, folder, source, position, job.total, null)
        }
        if (job.total > 0 && job.received.get() != job.total) throw IOException("Descarga incompleta")
    }

    /**
     * Varias conexiones: la respuesta original descarga el primer tramo
     * mientras se pide el último para comprobar que el servidor entrega
     * rangos del mismo archivo; si es así, el hueco se reparte entre más
     * conexiones. Si no, la original sigue sola hasta el final.
     */
    private fun receiveSplit(
        app: Context,
        job: ActiveDownload,
        response: WebResponse,
        body: InputStream,
        channel: FileChannel,
        folder: File,
        source: DownloadSource
    ) {
        val total = job.total
        val connections = DownloadPlan.connectionsFor(total)
        val tail = DownloadPlan.tailStart(total, connections)
        val lock = Any()
        var position = 0L
        var limit = total
        val tasks = CopyOnWriteArrayList<Future<*>>()

        val probe = parts.submit {
            val first = runCatching { openRange(app, job, response, source, tail, total) }.getOrNull() ?: return@submit
            val plan = synchronized(lock) {
                if (job.cancelled) null else DownloadPlan.split(position, tail, connections - 2)?.also { limit = it.first }
            }
            if (plan == null) {
                runCatching { first.body?.close() }
                return@submit
            }
            job.connections = 2 + plan.second.size
            plan.second.forEach { range ->
                tasks += parts.submit { fetchPart(app, job, response, channel, folder, source, range.first, range.last + 1, null) }
            }
            fetchPart(app, job, response, channel, folder, source, tail, total, first)
        }

        try {
            var end = 0L
            try {
                body.use { input ->
                    end = copy(input, channel, 0, { synchronized(lock) { limit } }, job, folder) { advanced ->
                        synchronized(lock) { position = advanced }
                    }
                }
            } catch (error: IOException) {
                if (job.cancelled) throw error
                end = synchronized(lock) { position }
            }
            // Esperar la decisión: hasta entonces el límite puede cambiar.
            probe.get()
            val own = synchronized(lock) { limit }
            // La conexión original se cortó antes de su límite: se completa con rangos.
            if (end < own) fetchPart(app, job, response, channel, folder, source, end, own, null)
            tasks.forEach { it.get() }
        } catch (error: Throwable) {
            job.cancelled = true
            tasks.forEach { it.cancel(false) }
            throw (error as? java.util.concurrent.ExecutionException)?.cause ?: error
        }
        if (job.received.get() != total) throw IOException("Descarga incompleta")
    }

    /** Descarga [start, end) con reintentos y reanudación desde lo ya escrito. */
    private fun fetchPart(
        app: Context,
        job: ActiveDownload,
        original: WebResponse,
        channel: FileChannel,
        folder: File,
        source: DownloadSource,
        start: Long,
        end: Long,
        first: WebResponse?
    ) {
        var position = start
        var attempt = 0
        var pending = first
        while (position < end) {
            if (job.cancelled) throw CancellationException("cancelada")
            try {
                val response = pending ?: openRange(app, job, original, source, position, end)
                pending = null
                val input = response.body ?: throw IOException("sin cuerpo")
                input.use { position = copy(it, channel, position, { end }, job, folder) }
                if (position < end) throw IOException("tramo incompleto")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (job.cancelled) throw CancellationException("cancelada")
                if (++attempt > MAX_PART_RETRIES) throw IOException("No se pudo completar la descarga (${error.message})", error)
                Thread.sleep(700L * attempt)
            }
        }
    }

    /** Pide [start, end) y comprueba que es el tramo correcto del mismo archivo. */
    private fun openRange(app: Context, job: ActiveDownload, original: WebResponse, source: DownloadSource, start: Long, end: Long): WebResponse {
        val request = WebRequest.Builder(original.uri)
            .header("Range", "bytes=$start-${end - 1}")
            .cacheMode(WebRequest.CACHE_MODE_NO_STORE)
            .apply { source.referrer?.takeIf { UrlSafety.isWebUrl(it) }?.let { referrer(it) } }
            .build()
        val flags = if (source.isPrivate) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        val response = fetchBlocking(app, request, flags)
        val range = DownloadPlan.parseContentRange(header(response.headers, "Content-Range"))
        val valid = response.statusCode == 206 && range != null && range.start == start &&
            (range.total < 0 || range.total == job.total) && DownloadPlan.sameEntity(original.headers, response.headers)
        if (!valid) {
            runCatching { response.body?.close() }
            throw IOException("el servidor no entregó el tramo pedido")
        }
        return response
    }

    /** GeckoWebExecutor responde en el hilo que lo llama: se pide desde el principal y se espera aquí. */
    private fun fetchBlocking(app: Context, request: WebRequest, flags: Int): WebResponse {
        val latch = CountDownLatch(1)
        var result: WebResponse? = null
        var failure: Throwable? = null
        main.post {
            runCatching {
                GeckoRuntimeHolder.webExecutor(app).fetch(request, flags).accept(
                    { response -> result = response; latch.countDown() },
                    { error -> failure = error; latch.countDown() }
                )
            }.onFailure { failure = it; latch.countDown() }
        }
        if (!latch.await(25, TimeUnit.SECONDS)) throw IOException("sin respuesta del servidor")
        failure?.let { throw IOException(it.message ?: "error de red", it) }
        return result ?: throw IOException("sin respuesta del servidor")
    }

    /**
     * Copia hasta [limit] (puede bajar mientras se copia) escribiendo en
     * posiciones absolutas. Llena el búfer antes de escribir: menos escrituras
     * al disco que con cada trozo pequeño que entrega la red.
     */
    private fun copy(
        input: InputStream,
        channel: FileChannel,
        start: Long,
        limit: () -> Long,
        job: ActiveDownload,
        folder: File,
        onAdvance: ((Long) -> Unit)? = null
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var position = start
        var sinceCheck = 0L
        var eof = false
        while (!eof) {
            if (job.cancelled) throw CancellationException("cancelada")
            val room = limit() - position
            if (room <= 0) break
            val want = minOf(buffer.size.toLong(), room).toInt()
            var filled = 0
            while (filled < want) {
                val count = input.read(buffer, filled, want - filled)
                if (count < 0) { eof = true; break }
                filled += count
                // Lo recibido se guarda si no hay más esperando: el progreso avanza fluido.
                if (filled >= MIN_WRITE_BYTES && input.available() <= 0) break
            }
            if (filled == 0) continue
            val chunk = ByteBuffer.wrap(buffer, 0, filled)
            var offset = position
            while (chunk.hasRemaining()) offset += channel.write(chunk, offset)
            position += filled
            job.received.addAndGet(filled.toLong())
            onAdvance?.invoke(position)

            // Comprobar espacio libre cada ~32 MB (sin reserva previa del archivo).
            sinceCheck += filled
            if (sinceCheck >= 32L * 1024L * 1024L) {
                sinceCheck = 0L
                if (job.total <= 0 && folder.usableSpace < FREE_SPACE_MARGIN) {
                    throw IOException("Almacenamiento casi lleno")
                }
            }
        }
        return position
    }

    /** Reserva el espacio de una vez: menos fragmentación y error de espacio antes de empezar. */
    private fun preallocate(raf: RandomAccessFile, total: Long) {
        try {
            android.system.Os.posix_fallocate(raf.fd, 0, total)
        } catch (error: android.system.ErrnoException) {
            if (error.errno == android.system.OsConstants.ENOSPC) throw IOException("No hay espacio suficiente para este archivo")
            // Sistemas de archivos sin fallocate: se escribe sin reserva.
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------
    // Notificaciones
    // ---------------------------------------------------------------

    private fun notifications(context: Context): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return null
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso de las descargas de Nexo"
                    setShowBadge(false)
                }
            )
        }
        return manager
    }

    private fun openDownloads(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 7102,
            Intent(context, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun showProgress(context: Context) {
        val manager = notifications(context) ?: return
        val jobs = activeDownloads()
        if (jobs.isEmpty()) {
            manager.cancel(NOTIFY_PROGRESS)
            return
        }
        val job = jobs.first()
        // En la pantalla de bloqueo no se muestran nombres de archivo.
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nexo)
            .setContentTitle("Descarga en curso")
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nexo)
            .setContentTitle(if (jobs.size == 1) job.fileName else "${jobs.size} descargas en curso")
            .setContentText(progressText(context, job))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openDownloads(context))
            .apply { if (job.total > 0) setProgress(100, job.percent, false) else setProgress(0, 0, true) }
            .build()
        runCatching { manager.notify(NOTIFY_PROGRESS, notification) }
    }

    private fun showDone(context: Context, name: String) {
        val manager = notifications(context) ?: return
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nexo)
            .setContentTitle("Descarga completada")
            .setContentText(name)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openDownloads(context))
            .build()
        runCatching { manager.notify(NOTIFY_DONE, notification) }
    }

    // ---------------------------------------------------------------
    // Lista de descargas
    // ---------------------------------------------------------------

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

    private fun header(headers: Map<String, String>, name: String): String? = DownloadPlan.header(headers, name)

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
            if (url.isBlank()) return@runCatching ""
            val uri = Uri.parse(url)
            uri.buildUpon()
                .clearQuery()
                .fragment(null)
                .build()
                .toString()
        }.getOrDefault("")
}
