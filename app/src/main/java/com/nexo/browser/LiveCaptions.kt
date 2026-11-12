package com.nexo.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.mozilla.geckoview.GeckoSession
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Subtítulos en vivo de una pestaña. Dos fuentes:
 *  - Pista de subtítulos del propio video (WebVTT): no usa Deepgram; todas
 *    las líneas se traducen por adelantado en lotes grandes.
 *  - Sin pista: la página envía el audio comprimido (Opus, ~24 kbps) y
 *    Deepgram lo transcribe en streaming; las frases terminadas se
 *    traducen en lotes cortos, con una sola petición en curso.
 * Las claves solo se usan aquí (proceso nativo); nunca llegan a la página.
 */
object LiveCaptions {
    enum class State { OFF, WAITING, CONNECTING, LISTENING, ERROR }

    interface Listener {
        fun onCaptionsChanged(session: GeckoSession)
    }

    private data class Cue(val start: Double, val end: Double, val text: String, var translated: String? = null)

    private class Stream(val session: GeckoSession) {
        @Volatile var stopped = false
        val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
        var socket: WebSocket? = null
        var opened = false
        val queue = ArrayDeque<ByteString>()
        var lastAudioAt = 0L
        var receivedBytes = 0L
        var startedAt = 0L
        var lastResultAt = 0L
        var signal = false
        var model = ""
        var headerChecked = false
        var unsavedMs = 0L
        var triedFallback = false
        var retries = 0
        var language = "multi"
        val text = CaptionText()
        var state = State.WAITING
        var message: String? = null
        var generation = 0
        // Pista de subtítulos del video (modo sin Deepgram).
        var trackMode = false
        val cues = HashMap<Int, Cue>()
        var activeCues = emptyList<Int>()
        var bulkQueued = HashSet<Int>()
        // Traducción.
        lateinit var batcher: TranslationBatcher
        val pendingCueIds = ArrayDeque<Int>()
        // Traducción anticipada del texto provisional.
        var lastSpeculation = 0L
        var speculating = false
        // Cambia al cambiar de idioma o servicio: invalida traducciones en vuelo (no el audio).
        @Volatile var translationGeneration = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    // Peticiones rápidas (Google): anticipadas y respaldo mientras responde el modelo de IA.
    private val fast = Executors.newSingleThreadExecutor()
    private val active = WeakHashMap<GeckoSession, Stream>()
    private var listener: Listener? = null
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 800
    }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    private val translateClient by lazy {
        client.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    }

    private const val MAX_SOCKET_QUEUE = 256L * 1024L
    private const val MAX_CUES = 3000

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (stream in active.values.toList()) {
                // Deepgram cierra a los ~10 s sin audio: mantener la conexión en pausas cortas del habla.
                if (stream.opened && now - stream.lastAudioAt > 3500L) stream.socket?.send(CaptionProtocol.KEEP_ALIVE)
                if (stream.startedAt > 0 && now - maxOf(stream.startedAt, stream.lastResultAt) > 15000 && stream.state != State.ERROR) {
                    stream.message = if (stream.receivedBytes == 0L) "No llega audio del reproductor. Puede estar pausado o protegido."
                        else "Audio recibido: ${stream.receivedBytes} bytes · ${if (stream.signal) "señal detectada" else "señal no confirmada"}. Deepgram aún no devuelve texto."
                    notifyChanged(stream.session)
                }
                flushUsage(stream)
            }
            if (active.isNotEmpty()) main.postDelayed(this, 4000L)
        }
    }

    fun attach(value: Listener?) { listener = value }

    fun isActive(session: GeckoSession?): Boolean = session != null && active.containsKey(session)

    fun state(session: GeckoSession?): State = session?.let { active[it]?.state } ?: State.OFF

    fun message(session: GeckoSession?): String? = session?.let { active[it]?.message }

    fun isTrackMode(session: GeckoSession?): Boolean = session?.let { active[it]?.trackMode } == true

    /** Qué mostrar ahora según el modo elegido (original, traducido o ambos). */
    fun display(context: Context, session: GeckoSession?): CaptionDisplay? {
        val stream = session?.let { active[it] } ?: return null
        if (stream.state == State.ERROR) return CaptionDisplay(stream.message ?: "Error de subtítulos", "")
        val mode = BrowserPrefs.captionMode(context)
        val translating = BrowserPrefs.captionTranslateTo(context).isNotBlank() && mode != "original"
        if (stream.trackMode) {
            val lines = stream.activeCues.mapNotNull { stream.cues[it] }
            if (lines.isEmpty()) return CaptionDisplay(stream.message ?: "Pista detectada · esperando subtítulos…", "")
            val done = lines.filter { !translating || it.translated != null }
            val primary = done.joinToString("\n") { if (translating) it.translated!! else it.text }
            val pending = if (translating) lines.filter { it.translated == null }.joinToString("\n") { it.text } else ""
            val secondary = if (translating && mode == "both") done.joinToString("\n") { it.text } else ""
            return CaptionDisplay(primary, pending,
                if (stream.message?.startsWith("No se pudo traducir") == true) stream.message.orEmpty() else secondary)
        }
        if (stream.text.isEmpty) return CaptionDisplay(stream.message ?: when (stream.state) {
            State.WAITING -> "Buscando video en la página y sus marcos…"
            State.CONNECTING -> "Conectando con Deepgram…"
            State.LISTENING -> "Esperando voz…"
            State.ERROR -> "No se pudieron iniciar los subtítulos."
            State.OFF -> ""
        }, "")
        return stream.text.view(mode, translating).let { view ->
            if (stream.message?.startsWith("No se pudo traducir") == true) view.copy(secondary = stream.message.orEmpty()) else view
        }.takeIf { !it.isEmpty }
    }

    /** Minutos de audio enviados hoy y límite diario (0 = sin límite). */
    fun usageText(context: Context): String {
        val minutes = (BrowserPrefs.captionSecondsToday(context) + 59) / 60
        val limit = BrowserPrefs.captionDailyLimit(context)
        return if (limit > 0) "Hoy: $minutes de $limit min de audio" else "Hoy: $minutes min de audio"
    }

    /** Activa o apaga los subtítulos del video de esta pestaña. */
    fun toggle(context: Context, session: GeckoSession): Boolean {
        if (active.containsKey(session)) {
            stop(session)
            return false
        }
        val app = context.applicationContext
        val stream = Stream(session).apply {
            language = BrowserPrefs.captionLanguage(app)
            batcher = newBatcher(app)
        }
        active[session] = stream
        main.removeCallbacks(tick)
        main.postDelayed(tick, 4000L)
        warmUp(app)
        // Con clave, la página puede usar Deepgram si el video no trae subtítulos propios.
        ExtensionManager.setCaptions(session, true, live = BrowserPrefs.deepgramKey(app).isNotBlank())
        notifyChanged(session)
        return true
    }

    fun stop(session: GeckoSession) {
        val stream = active.remove(session) ?: return
        synchronized(stream) { stream.stopped = true; stream.calls.forEach { it.cancel() }; stream.calls.clear() }
        close(stream)
        stream.translationGeneration++
        flushUsage(stream, force = true)
        stream.batcher.clear()
        ExtensionManager.setCaptions(session, false)
        notifyChanged(session)
    }

    fun stopAll() {
        active.keys.toList().forEach { stop(it) }
    }

    /**
     * Sin espera artificial: si no hay ninguna petición en curso, la frase sale
     * al momento (solo se agrupan las que llegan juntas en ~0,1 s). Mientras una
     * petición está en curso, lo nuevo se acumula y viaja en la siguiente: así
     * hay pocas peticiones sin añadir retraso.
     */
    private fun newBatcher(context: Context): TranslationBatcher =
        when (effectiveProvider(context)) {
            BrowserPrefs.TRANSLATE_AI -> TranslationBatcher(delayMs = 120, maxChars = 1200, maxLines = 24)
            BrowserPrefs.TRANSLATE_DEEPL -> TranslationBatcher(delayMs = 60, maxChars = 1500, maxLines = 30)
            else -> TranslationBatcher(delayMs = 40, maxChars = 900, maxLines = 20)
        }

    /** DeepL sin clave o sin ese idioma, o IA sin configurar: se usa Google. */
    private fun effectiveProvider(context: Context): String {
        val provider = BrowserPrefs.translationProvider(context)
        val target = BrowserPrefs.captionTranslateTo(context)
        return when {
            provider == BrowserPrefs.TRANSLATE_DEEPL &&
                (BrowserPrefs.deeplKey(context).isBlank() || !CaptionProtocol.deeplSupports(target)) -> BrowserPrefs.TRANSLATE_GOOGLE
            provider == BrowserPrefs.TRANSLATE_AI && BrowserPrefs.aiTranslateKey(context).isBlank() &&
                BrowserPrefs.aiTranslateProvider(context) != "custom" -> BrowserPrefs.TRANSLATE_GOOGLE
            else -> provider
        }
    }

    private fun cacheKey(provider: String, target: String, text: String) = provider + "\u0000" + target + "\u0000" + text

    private fun warmUp(context: Context) { /* No provider request before actual text. */ }

    private inline fun <T> withResponse(stream: Stream, generation: Int, request: Request, block: (Response) -> T): T {
        val call = synchronized(stream) {
            if (stream.stopped || stream.translationGeneration != generation) throw java.io.InterruptedIOException()
            translateClient.newCall(request).also { stream.calls.add(it) }
        }
        return try { call.execute().use(block) } finally { stream.calls.remove(call) }
    }

    fun retarget(context: Context, session: GeckoSession?) {
        val stream = session?.let { active[it] } ?: return
        val app = context.applicationContext
        synchronized(stream) { stream.translationGeneration++; stream.calls.forEach { it.cancel() }; stream.calls.clear() }
        stream.batcher.clear()
        stream.batcher = newBatcher(app)
        warmUp(app)
        if (stream.trackMode) {
            stream.cues.values.forEach { it.translated = null }
            stream.bulkQueued.clear()
            queueAhead(app, stream)
        } else {
            stream.text.resetTranslations().forEach { enqueueLive(app, stream, it) }
        }
        notifyChanged(stream.session)
    }

    /** Cambia el idioma del audio: nueva conexión con Deepgram (grabación nueva). */
    fun relisten(context: Context, session: GeckoSession?) {
        val stream = session?.let { active[it] } ?: return
        stream.language = BrowserPrefs.captionLanguage(context)
        if (!stream.trackMode) ExtensionManager.restartCaptions(stream.session)
    }

    // --- Mensajes del puente de la página --------------------------------

    private fun invalidateTranslations(stream: Stream) {
        synchronized(stream) { stream.translationGeneration++; stream.calls.forEach { it.cancel() }; stream.calls.clear() }
        stream.batcher.clear()
        stream.bulkQueued.clear()
        stream.speculating = false
    }

    fun onRecordingStarted(context: Context, session: GeckoSession, mime: String) {
        val stream = active[session] ?: return
        val app = context.applicationContext
        if (!CaptionProtocol.supportedMime(mime)) {
            onPageError(context, session, "mime", "El reproductor no entrega Opus en WebM/Ogg.")
            return
        }
        if (limitReached(app)) {
            onPageError(app, session, "limit", "Límite diario de subtítulos alcanzado (${BrowserPrefs.captionDailyLimit(app)} min). Puedes cambiarlo en Ajustes.")
            return
        }
        close(stream)
        invalidateTranslations(stream)
        stream.trackMode = false
        stream.text.clear()
        stream.receivedBytes = 0
        stream.headerChecked = false
        stream.signal = false
        stream.startedAt = System.currentTimeMillis()
        stream.lastResultAt = 0
        stream.message = "Captura Opus iniciada · conectando…"
        connect(app, stream, stream.model.ifBlank { CaptionProtocol.modelFor(stream.language) })
    }

    fun onAudio(session: GeckoSession, base64: String) {
        val stream = active[session] ?: return
        if (stream.state == State.ERROR || stream.startedAt == 0L || base64.length > 350000) return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        if (bytes.isEmpty() || bytes.size > 256 * 1024) return
        if (!stream.headerChecked) {
            if (!CaptionProtocol.hasContainerHeader(bytes)) {
                onPageError(AppContext.get(), session, "header", "La captura no contiene una cabecera WebM/Ogg válida.")
                return
            }
            stream.headerChecked = true
        }
        stream.receivedBytes += bytes.size
        val now = System.currentTimeMillis()
        if (stream.lastAudioAt > 0) stream.unsavedMs += (now - stream.lastAudioAt).coerceIn(0L, 1000L)
        stream.lastAudioAt = now
        val chunk = bytes.toByteString()
        val socket = stream.socket
        if (stream.opened && socket != null) {
            // Control de flujo: con la red atascada se descarta audio en vez de acumularlo.
            if (socket.queueSize() < MAX_SOCKET_QUEUE) socket.send(chunk)
            else {
                close(stream)
                stream.message = "La red no puede enviar el audio. Reiniciando captura…"
                ExtensionManager.restartCaptions(session)
            }
        } else if (stream.queue.size < 40) {
            stream.queue.addLast(chunk)
        } else {
            onPageError(AppContext.get(), session, "buffer", "La conexión tarda demasiado en aceptar audio. Apaga y activa CC para reintentar.")
        }
    }

    fun onRecordingStopped(session: GeckoSession) {
        val stream = active[session] ?: return
        close(stream)
        invalidateTranslations(stream)
        stream.trackMode = false
        stream.activeCues = emptyList()
        stream.text.clear()
        flushUsage(stream, force = true)
        stream.lastAudioAt = 0
        stream.startedAt = 0
        stream.state = State.WAITING
        stream.message = "Esperando reproducción o un nuevo video…"
        notifyChanged(session)
    }

    fun onPageError(context: Context, session: GeckoSession, code: String, message: String) {
        val stream = active[session] ?: return
        close(stream)
        invalidateTranslations(stream)
        // Stop page resources without dropping the error from the native UI.
        ExtensionManager.setCaptions(session, false)
        stream.state = State.ERROR
        stream.message = if (code == "need_key")
            "Este video no trae subtítulos propios: añade tu clave de Deepgram para transcribirlo." else message
        notifyChanged(session)
    }

    /** El video trae su propia pista de subtítulos: se usa en lugar de Deepgram. */
    fun onTrack(session: GeckoSession) {
        val stream = active[session] ?: return
        close(stream)
        invalidateTranslations(stream)
        stream.startedAt = 0
        stream.text.clear()
        stream.trackMode = true
        stream.cues.clear()
        stream.activeCues = emptyList()
        stream.bulkQueued.clear()
        stream.pendingCueIds.clear()
        stream.batcher.clear()
        stream.state = State.LISTENING
        stream.message = null
        notifyChanged(session)
    }

    fun onCues(context: Context, session: GeckoSession, cues: JSONArray) {
        val stream = active[session] ?: return
        if (!stream.trackMode) return
        for (i in 0 until cues.length()) {
            if (stream.cues.size >= MAX_CUES) break
            val item = cues.optJSONObject(i) ?: continue
            val id = item.optInt("i", -1)
            val text = item.optString("t").trim().take(300)
            if (id < 0 || text.isEmpty()) continue
            stream.cues[id] = Cue(item.optDouble("s", 0.0), item.optDouble("e", 0.0), text)
        }
        queueAhead(context.applicationContext, stream)
    }

    fun onActiveCues(context: Context, session: GeckoSession, ids: JSONArray) {
        val stream = active[session] ?: return
        if (!stream.trackMode) return
        stream.activeCues = (0 until ids.length()).map { ids.optInt(it, -1) }.filter { it >= 0 }.take(4)
        queueAhead(context.applicationContext, stream)
        notifyChanged(session)
    }

    // --- Conexión con Deepgram -----------------------------------------------

    private fun limitReached(context: Context): Boolean {
        val limit = BrowserPrefs.captionDailyLimit(context)
        return limit > 0 && BrowserPrefs.captionSecondsToday(context) >= limit * 60
    }

    private fun flushUsage(stream: Stream, force: Boolean = false) {
        if (stream.unsavedMs < 1000 || (!force && stream.unsavedMs < 10_000)) return
        val context = AppContext.get()
        BrowserPrefs.addCaptionSeconds(context, (stream.unsavedMs / 1000).toInt())
        stream.unsavedMs %= 1000
        if (limitReached(context) && !stream.trackMode && active[stream.session] === stream) {
            onPageError(context, stream.session, "limit", "Límite diario de subtítulos alcanzado (${BrowserPrefs.captionDailyLimit(context)} min).")
        }
    }

    private fun connect(context: Context, stream: Stream, model: String) {
        val key = BrowserPrefs.deepgramKey(context)
        if (key.isBlank()) {
            stream.state = State.ERROR
            stream.message = "Añade tu clave de Deepgram en Ajustes › Subtítulos."
            notifyChanged(stream.session)
            return
        }
        stream.state = State.CONNECTING
        notifyChanged(stream.session)
        val generation = ++stream.generation
        val request = Request.Builder()
            .url(CaptionProtocol.listenUrl(stream.language, model).replaceFirst("wss://", "https://"))
            .header("Authorization", "Token $key")
            .build()
        stream.socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (active[stream.session] !== stream || stream.generation != generation) {
                        webSocket.close(1000, null); return@post
                    }
                    stream.opened = true
                    stream.state = State.LISTENING
                    while (stream.queue.isNotEmpty()) webSocket.send(stream.queue.removeFirst())
                    notifyChanged(stream.session)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    if (active[stream.session] !== stream || stream.generation != generation) return@post
                    if (CaptionProtocol.isUtteranceEnd(text)) {
                        scheduleClear(stream)
                        return@post
                    }
                    CaptionProtocol.parseError(text)?.let {
                        onPageError(context, stream.session, "deepgram", "Deepgram: $it")
                        return@post
                    }
                    val transcript = CaptionProtocol.parseResult(text) ?: return@post
                    if (transcript.text.isNotBlank()) {
                        stream.lastResultAt = System.currentTimeMillis()
                        stream.message = null
                    }
                    stream.retries = 0
                    val segment = stream.text.onTranscript(transcript)
                    if (segment != null) enqueueLive(context, stream, segment)
                    else if (!transcript.isFinal) speculate(context, stream)
                    main.removeCallbacksAndMessages(stream)
                    notifyChanged(stream.session)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                response?.close()
                main.post {
                    if (active[stream.session] !== stream || stream.generation != generation) return@post
                    stream.opened = false
                    stream.socket = null
                    // Si el modelo no admite el idioma, reintentar una vez con nova-2.
                    if (code == 400 && model != "nova-2" && !stream.triedFallback) {
                        stream.triedFallback = true
                        stream.model = "nova-2"
                        close(stream)
                        ExtensionManager.restartCaptions(stream.session)
                        return@post
                    }
                    // Corte de red: hasta 3 reintentos con espera creciente; cada uno empieza
                    // una grabación nueva (el audio necesita su cabecera).
                    val transient = code == null || code == 429 || code >= 500
                    if (transient && stream.retries < 3) {
                        val delay = 1000L * (1 shl stream.retries) + (0..400).random()
                        stream.retries++
                        stream.state = State.CONNECTING
                        stream.message = null
                        notifyChanged(stream.session)
                        main.postDelayed({
                            if (active[stream.session] === stream && stream.generation == generation) {
                                ExtensionManager.restartCaptions(stream.session)
                            }
                        }, delay)
                        return@post
                    }
                    stream.state = State.ERROR
                    onPageError(context, stream.session, "deepgram", CaptionProtocol.errorFor(code) + " Apaga y activa CC para reintentar.")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post {
                    if (active[stream.session] === stream && stream.generation == generation && stream.socket === webSocket) {
                        onPageError(context, stream.session, "closed", "Deepgram cerró la conexión ($code). Apaga y activa CC para reintentar.")
                    }
                }
            }
        })
    }

    fun onStatus(session: GeckoSession, code: String) {
        val stream = active[session] ?: return
        if (code == "signal") stream.signal = true
        stream.message = when (code) {
            "signal" -> "Audio con señal detectada · esperando transcripción…"
            "meter_unavailable" -> "Audio capturado; medidor no disponible. Esperando transcripción…"
            "paused" -> "Esperando reproducción…"
            else -> stream.message
        }
        notifyChanged(session)
    }

    fun reapply(context: Context) {
        active.keys.toList().forEach { session ->
            if (!BrowserPrefs.captionsEnabled(context)) stop(session)
            else { retarget(context, session); relisten(context, session); ExtensionManager.restoreCaptions(session) }
        }
    }

    private fun close(stream: Stream) {
        ++stream.generation
        val socket = stream.socket
        stream.socket = null
        stream.opened = false
        stream.queue.clear()
        if (socket != null) {
            runCatching { socket.send(CaptionProtocol.CLOSE_STREAM) }
            runCatching { socket.close(1000, null) }
        }
    }

    /** Tras una pausa larga en el habla, el texto se borra para no tapar el video. */
    private fun scheduleClear(stream: Stream) {
        main.removeCallbacksAndMessages(stream)
        main.postAtTime({
            stream.text.clear()
            notifyChanged(stream.session)
        }, stream, SystemClock.uptimeMillis() + 3500L)
    }

    // --- Traducción por lotes ---------------------------------------------------

    private fun translating(context: Context): Boolean =
        BrowserPrefs.captionTranslateTo(context).isNotBlank() && BrowserPrefs.captionMode(context) != "original"

    private fun enqueueLive(context: Context, stream: Stream, segment: CaptionText.Segment) {
        if (!translating(context)) return
        val target = BrowserPrefs.captionTranslateTo(context)
        val provider = effectiveProvider(context)
        cache[cacheKey(provider, target, segment.original)]?.let {
            stream.text.setTranslation(segment.id, it)
            return
        }
        // Con IA: lo que Google ya tradujo por anticipado se muestra mientras responde el modelo.
        if (provider == BrowserPrefs.TRANSLATE_AI) {
            cache[cacheKey(BrowserPrefs.TRANSLATE_GOOGLE, target, segment.original)]?.let {
                stream.text.setTranslation(segment.id, it, provisional = true)
            }
        }
        stream.batcher.add(TranslationBatcher.Item(segment.id, segment.original), SystemClock.uptimeMillis())
        pump(context, stream)
    }

    /**
     * Traducción anticipada del texto provisional con Google (rápido y sin
     * clave): casi siempre coincide con la frase final, que entonces aparece
     * traducida al instante. Como mucho una petición cada 0,7 s.
     */
    private fun speculate(context: Context, stream: Stream) {
        if (!translating(context) || stream.speculating) return
        val provider = effectiveProvider(context)
        if (provider == BrowserPrefs.TRANSLATE_DEEPL) return
        val interim = stream.text.interimText
        val now = SystemClock.uptimeMillis()
        if (interim.length < 24 || now - stream.lastSpeculation < 700) return
        val target = BrowserPrefs.captionTranslateTo(context)
        cache[cacheKey(BrowserPrefs.TRANSLATE_GOOGLE, target, interim)]?.let {
            stream.text.setInterimTranslation(interim, it)
            return
        }
        stream.speculating = true
        stream.lastSpeculation = now
        val generation = stream.translationGeneration
        fast.execute {
            val translated = runCatching { googleLines(stream, generation, listOf(interim), target)?.firstOrNull() }.getOrNull()
            main.post {
                if (active[stream.session] !== stream || stream.translationGeneration != generation) return@post
                stream.speculating = false
                if (translated.isNullOrBlank()) return@post
                cache[cacheKey(BrowserPrefs.TRANSLATE_GOOGLE, target, interim)] = translated
                stream.text.setInterimTranslation(interim, translated)
                notifyChanged(stream.session)
            }
        }
    }

    /**
     * Pista propia: se traduce por adelantado desde la línea actual hacia
     * delante, en lotes grandes. Sin una petición por línea.
     */
    private fun queueAhead(context: Context, stream: Stream) {
        if (!translating(context) || stream.cues.isEmpty()) return
        val target = BrowserPrefs.captionTranslateTo(context)
        val current = stream.activeCues.minOrNull()?.let { stream.cues[it]?.start } ?: 0.0
        val ordered = stream.cues.entries
            .filter { it.value.translated == null && it.key !in stream.bulkQueued && it.value.end >= current - 1 }
            .sortedBy { it.value.start }
            .take(120)
        val provider = effectiveProvider(context)
        for ((id, cue) in ordered) {
            cache[cacheKey(provider, target, cue.text)]?.let { cue.translated = it } ?: run {
                stream.bulkQueued += id
                stream.batcher.add(TranslationBatcher.Item(id.toLong(), cue.text), 0L)
            }
        }
        pump(context, stream)
    }

    private fun pump(context: Context, stream: Stream) {
        val now = SystemClock.uptimeMillis()
        val due = stream.batcher.dueAt() ?: return
        if (due > now) {
            main.postDelayed({ if (active[stream.session] === stream) pump(context, stream) }, due - now)
            return
        }
        val batch = stream.batcher.take(now) ?: return
        val target = BrowserPrefs.captionTranslateTo(context)
        val provider = effectiveProvider(context)
        val textContext = if (stream.trackMode) emptyList() else stream.text.context()
        val generation = stream.translationGeneration
        val lines = batch.map { it.text }
        // Respaldo rápido: con IA en vivo, Google muestra una traducción mientras el modelo responde.
        if (provider == BrowserPrefs.TRANSLATE_AI && !stream.trackMode) {
            fast.execute {
                val quick = runCatching { googleLines(stream, generation, lines, target) }.getOrNull() ?: return@execute
                main.post {
                    if (active[stream.session] !== stream || stream.translationGeneration != generation) return@post
                    batch.forEachIndexed { index, item ->
                        val text = quick.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                        cache[cacheKey(BrowserPrefs.TRANSLATE_GOOGLE, target, item.text)] = text
                        stream.text.setTranslation(item.id, text, provisional = true)
                    }
                    notifyChanged(stream.session)
                }
            }
        }
        io.execute {
            val result = runCatching { translateBatch(context, stream, generation, provider, lines, target, textContext) }.getOrNull()
            main.post {
                if (stream.translationGeneration == generation) stream.batcher.done()
                if (active[stream.session] !== stream || stream.translationGeneration != generation) return@post
                stream.message = if (result == null) "No se pudo traducir con el servicio elegido; se conserva el texto original." else null
                batch.forEachIndexed { index, item ->
                    // Si el traductor falla, se queda el texto original (sin bloquear los siguientes).
                    val translated = result?.getOrNull(index)?.takeIf { it.isNotBlank() }
                    if (translated == null && provider == BrowserPrefs.TRANSLATE_AI && !stream.trackMode) return@forEachIndexed
                    if (translated != null) cache[cacheKey(provider, target, item.text)] = translated
                    val shown = translated ?: item.text
                    if (stream.trackMode) stream.cues[item.id.toInt()]?.translated = shown
                    else stream.text.setTranslation(item.id, shown)
                }
                notifyChanged(stream.session)
                if (stream.trackMode) queueAhead(context, stream) else pump(context, stream)
            }
        }
    }

    /** Una petición por lote; si las líneas no se pueden alinear, se traducen unidas. */
    private fun googleLines(stream: Stream, generation: Int, lines: List<String>, target: String): List<String>? {
        val form = "application/x-www-form-urlencoded".toMediaType()
        val body = CaptionProtocol.googleBatchBody(lines, target)
        // GET para lo habitual (frases cortas); POST solo si la dirección quedaría demasiado larga.
        val request = if (body.length < 1800) Request.Builder().url(CaptionProtocol.GOOGLE_BATCH_URL + "&" + body).build()
        else Request.Builder().url(CaptionProtocol.GOOGLE_BATCH_URL).post(body.toRequestBody(form)).build()
        return withResponse(stream, generation, request) { response ->
            if (!response.isSuccessful) return@withResponse null
            val body = response.body?.string().orEmpty()
            CaptionProtocol.parseGoogleLines(body, lines.size)
                ?: if (lines.size == 1) CaptionProtocol.parseGoogle(body)?.let { listOf(it) } else null
        }
    }

    private fun translateBatch(context: Context, stream: Stream, generation: Int, provider: String, lines: List<String>, target: String, textContext: List<String>): List<String>? {
        val form = "application/x-www-form-urlencoded".toMediaType()
        return when (provider) {
            BrowserPrefs.TRANSLATE_AI -> {
                val key = BrowserPrefs.aiTranslateKey(context)
                val aiProvider = BrowserPrefs.aiTranslateProvider(context)
                if (key.isBlank() && aiProvider != "custom") return null
                val request = CaptionProtocol.aiRequest(
                    aiProvider, BrowserPrefs.aiTranslateEndpoint(context), BrowserPrefs.aiTranslateModel(context), key,
                    CaptionProtocol.LANGUAGE_NAMES[target] ?: target, lines, textContext
                )
                val builder = Request.Builder().url(request.url)
                    .post(request.body.toRequestBody("application/json".toMediaType()))
                request.headers.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
                withResponse(stream, generation, builder.build()) { response ->
                    if (response.isSuccessful) CaptionProtocol.parseAiLines(response.body?.string().orEmpty(), lines.size) else null
                }
            }
            BrowserPrefs.TRANSLATE_DEEPL -> {
                val key = BrowserPrefs.deeplKey(context)
                if (key.isBlank()) return null
                val request = Request.Builder().url(CaptionProtocol.deeplEndpoint(key))
                    .header("Authorization", "DeepL-Auth-Key $key")
                    .post(CaptionProtocol.deeplBatchBody(lines, target).toRequestBody(form))
                    .build()
                withResponse(stream, generation, request) { response ->
                    if (response.isSuccessful) CaptionProtocol.parseDeeplLines(response.body?.string().orEmpty(), lines.size) else null
                }
            }
            else -> googleLines(stream, generation, lines, target)
        }
    }

    private fun notifyChanged(session: GeckoSession) {
        listener?.onCaptionsChanged(session)
    }
}
