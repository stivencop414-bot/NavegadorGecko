package com.ejemplo.navegador

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
 * Subtítulos en vivo de una pestaña. Tres fuentes, por orden:
 *  - Pista de subtítulos del propio video (WebVTT/HLS): no usa Deepgram; las
 *    líneas se traducen por adelantado en lotes grandes.
 *  - Texto que dibuja el reproductor (YouTube, Netflix, Prime Video…): no usa
 *    Deepgram; se trata como una transcripción en vivo.
 *  - Sin ninguna de las dos: la página envía el audio comprimido (Opus,
 *    ~24 kbps) y Deepgram lo transcribe en streaming.
 * Las claves solo se usan aquí (proceso nativo); nunca llegan a la página.
 */
object LiveCaptions {
    enum class State { OFF, WAITING, CONNECTING, LISTENING, ERROR }

    interface Listener {
        fun onCaptionsChanged(session: GeckoSession)
    }

    private data class Cue(
        val start: Double,
        val end: Double,
        val text: String,
        var translated: String? = null,
        var failures: Int = 0
    )

    private class Stream(val session: GeckoSession) {
        var socket: WebSocket? = null
        var opened = false
        val queue = ArrayDeque<ByteString>()
        var lastAudioAt = 0L
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
        var translationGeneration = 0
        // Texto dibujado por el reproductor de la página (YouTube, Netflix…).
        var domMode = false
        val dom = DomCaptionTracker()
        // false mientras la página no confirma la orden (puente aún sin conectar).
        var delivered = false
        var stateChangedAt = SystemClock.uptimeMillis()
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
    /** Tiempo que se muestra el aviso de estado cuando aún no hay texto. */
    const val HINT_MS = 6000L
    private const val DOM_SETTLE_MS = 900L

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (stream in active.values.toList()) {
                // Deepgram cierra a los ~10 s sin audio: mantener la conexión en pausas cortas del habla.
                if (stream.opened && now - stream.lastAudioAt > 3500L) stream.socket?.send(CaptionProtocol.KEEP_ALIVE)
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

    /** Subtítulos leídos del reproductor de la página (sin Deepgram). */
    fun isDomMode(session: GeckoSession?): Boolean = session?.let { active[it]?.domMode } == true

    /**
     * Aviso breve cuando aún no hay texto que mostrar: así se sabe que los
     * subtítulos están activos y qué esperan. null pasado [HINT_MS] tras el
     * último cambio de estado (los errores se muestran en un aviso aparte).
     */
    fun statusHint(session: GeckoSession?): String? {
        val stream = session?.let { active[it] } ?: return null
        if (SystemClock.uptimeMillis() - stream.stateChangedAt > HINT_MS) return null
        return when {
            stream.state == State.ERROR -> null
            !stream.delivered -> "CC · esperando a que cargue la página…"
            stream.state == State.CONNECTING -> "CC · conectando con Deepgram…"
            stream.trackMode -> "CC · subtítulos del video listos"
            stream.domMode -> "CC · leyendo los subtítulos del reproductor"
            stream.state == State.LISTENING -> "CC · escuchando el video…"
            else -> "CC · buscando subtítulos…"
        }
    }

    private fun setState(stream: Stream, state: State, message: String? = stream.message) {
        if (stream.state != state || stream.message != message) stream.stateChangedAt = SystemClock.uptimeMillis()
        stream.state = state
        stream.message = message
    }

    /** Qué mostrar ahora según el modo elegido (original, traducido o ambos). */
    fun display(context: Context, session: GeckoSession?): CaptionDisplay? {
        val stream = session?.let { active[it] } ?: return null
        val mode = BrowserPrefs.captionMode(context)
        val translating = BrowserPrefs.captionTranslateTo(context).isNotBlank() && mode != "original"
        if (stream.trackMode) {
            val lines = stream.activeCues.mapNotNull { stream.cues[it] }
            if (lines.isEmpty()) return null
            val done = lines.filter { !translating || it.translated != null }
            val primary = done.joinToString("\n") { if (translating) it.translated!! else it.text }
            val pending = if (translating) lines.filter { it.translated == null }.joinToString("\n") { it.text } else ""
            val secondary = if (translating && mode == "both") done.joinToString("\n") { it.text } else ""
            return CaptionDisplay(primary, pending, secondary)
        }
        if (stream.text.isEmpty) return null
        return stream.text.view(mode, translating).takeIf { !it.isEmpty }
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
        deliver(app, stream)
        notifyChanged(session)
        // Si la página no respondió, avisar en vez de quedarse en silencio.
        main.postDelayed({
            if (active[session] === stream && !stream.delivered) {
                setState(stream, State.ERROR, "La página aún no está lista para los subtítulos. Recárgala y vuelve a tocar CC.")
                notifyChanged(session)
            }
        }, 8000L)
        return true
    }

    /** Envía la orden a la página; si el puente aún no conecta, se reenvía al conectar. */
    private fun deliver(context: Context, stream: Stream) {
        // Con clave, la página puede usar Deepgram si el video no trae subtítulos propios.
        stream.delivered = ExtensionManager.setCaptions(
            stream.session,
            true,
            live = BrowserPrefs.deepgramKey(context).isNotBlank(),
            language = stream.language
        )
        stream.stateChangedAt = SystemClock.uptimeMillis()
    }

    /** El puente de la página (re)conectó: si los subtítulos seguían activos, reanudarlos allí. */
    fun onBridgeConnected(session: GeckoSession) {
        val stream = active[session] ?: return
        close(stream)
        resetSource(stream)
        setState(stream, State.WAITING, null)
        deliver(AppContext.get(), stream)
        notifyChanged(session)
    }

    private fun resetSource(stream: Stream) {
        main.removeCallbacksAndMessages(stream)
        stream.trackMode = false
        stream.domMode = false
        stream.dom.reset()
        stream.text.clear()
        stream.cues.clear()
        stream.activeCues = emptyList()
        stream.bulkQueued.clear()
        stream.pendingCueIds.clear()
        stream.batcher.clear()
        stream.translationGeneration++
    }

    fun stop(session: GeckoSession) {
        val stream = active.remove(session) ?: return
        main.removeCallbacksAndMessages(stream)
        close(stream)
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

    /** Abre de antemano la conexión TLS con el traductor: la primera frase no paga el saludo. */
    private fun warmUp(context: Context) {
        if (!translating(context)) return
        val target = BrowserPrefs.captionTranslateTo(context)
        val provider = effectiveProvider(context)
        fast.execute {
            runCatching {
                val url = when (provider) {
                    BrowserPrefs.TRANSLATE_DEEPL -> CaptionProtocol.deeplEndpoint(BrowserPrefs.deeplKey(context))
                    BrowserPrefs.TRANSLATE_AI -> BrowserPrefs.aiTranslateEndpoint(context)
                        .ifBlank { CaptionProtocol.AI_ENDPOINTS[BrowserPrefs.aiTranslateProvider(context)].orEmpty() }
                    else -> CaptionProtocol.googleUrl("ok", target)
                }
                if (url.startsWith("https://")) {
                    val request = Request.Builder().url(url).head().build()
                    translateClient.newCall(request).execute().close()
                }
                // La IA usa Google como respaldo rápido: calentar también esa conexión.
                if (provider == BrowserPrefs.TRANSLATE_AI) {
                    translateClient.newCall(Request.Builder().url(CaptionProtocol.googleUrl("ok", target)).head().build()).execute().close()
                }
            }
        }
    }

    /** Cambia el idioma de destino o el servicio sin cortar el audio: se retraduce lo visible. */
    fun retarget(context: Context, session: GeckoSession?) {
        val stream = session?.let { active[it] } ?: return
        val app = context.applicationContext
        stream.translationGeneration++
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
        if (!stream.trackMode && !stream.domMode) ExtensionManager.restartCaptions(stream.session)
    }

    // --- Mensajes del puente de la página --------------------------------

    fun onRecordingStarted(context: Context, session: GeckoSession) {
        val stream = active[session] ?: return
        val app = context.applicationContext
        stream.delivered = true
        if (limitReached(app)) {
            setState(stream, State.ERROR, "Límite diario de subtítulos alcanzado (${BrowserPrefs.captionDailyLimit(app)} min). Puedes cambiarlo en Ajustes.")
            notifyChanged(session)
            stop(session)
            return
        }
        close(stream)
        stream.trackMode = false
        stream.domMode = false
        stream.generation++
        stream.triedFallback = false
        stream.message = null
        connect(app, stream, CaptionProtocol.modelFor(stream.language))
    }

    fun onAudio(session: GeckoSession, base64: String) {
        val stream = active[session] ?: return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        if (bytes.isEmpty() || bytes.size > 256 * 1024) return
        val now = System.currentTimeMillis()
        if (stream.lastAudioAt > 0) stream.unsavedMs += (now - stream.lastAudioAt).coerceIn(0L, 1000L)
        stream.lastAudioAt = now
        val chunk = bytes.toByteString()
        val socket = stream.socket
        if (stream.opened && socket != null) {
            // Control de flujo: con la red atascada se descarta audio en vez de acumularlo.
            if (socket.queueSize() < MAX_SOCKET_QUEUE) socket.send(chunk)
        } else if (stream.queue.size < 40) {
            stream.queue.addLast(chunk)
        }
    }

    fun onRecordingStopped(session: GeckoSession) {
        val stream = active[session] ?: return
        close(stream)
        flushUsage(stream, force = true)
        stream.lastAudioAt = 0
        if (!stream.domMode && !stream.trackMode) setState(stream, State.WAITING)
        notifyChanged(session)
    }

    fun onPageError(context: Context, session: GeckoSession, code: String, message: String) {
        val stream = active[session] ?: return
        stream.delivered = true
        setState(stream, State.ERROR, if (code == "need_key")
            "Este video no trae subtítulos que Nexo pueda leer. Activa los subtítulos del reproductor " +
                "(Nexo los tomará solo) o añade una clave de Deepgram para transcribir el audio." else message)
        notifyChanged(session)
    }

    /**
     * El video trae su propia pista ("track") o el reproductor dibuja el texto
     * en la página ("dom"): se usa eso en lugar de Deepgram.
     */
    fun onTrack(session: GeckoSession, source: String = "track") {
        val stream = active[session] ?: return
        stream.delivered = true
        close(stream)
        resetSource(stream)
        stream.trackMode = source != "dom"
        stream.domMode = source == "dom"
        setState(stream, State.LISTENING, null)
        notifyChanged(session)
    }

    /** Texto visible en el reproductor de la página (vacío cuando desaparece). */
    fun onDomText(context: Context, session: GeckoSession, raw: String) {
        val stream = active[session] ?: return
        if (!stream.domMode) return
        val app = context.applicationContext
        main.removeCallbacksAndMessages(stream)
        applyTranscripts(app, stream, stream.dom.update(raw.take(400)))
        if (raw.isBlank()) {
            // El sitio quitó su subtítulo: el de Nexo también se va, un poco después.
            scheduleClear(stream, 2500L)
        } else {
            // Sin cambios durante un momento: frase terminada (traducción definitiva).
            main.postAtTime({
                stream.dom.settle()?.let { applyTranscripts(app, stream, listOf(it)) }
                notifyChanged(stream.session)
            }, stream, SystemClock.uptimeMillis() + DOM_SETTLE_MS)
        }
        notifyChanged(session)
    }

    private fun applyTranscripts(context: Context, stream: Stream, updates: List<CaptionProtocol.Transcript>) {
        for (transcript in updates) {
            val segment = stream.text.onTranscript(transcript)
            if (segment != null) enqueueLive(context, stream, segment)
            else if (!transcript.isFinal) speculate(context, stream)
        }
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
            setState(stream, State.ERROR, "Límite diario de subtítulos alcanzado (${BrowserPrefs.captionDailyLimit(context)} min).")
            notifyChanged(stream.session)
            stop(stream.session)
        }
    }

    private fun connect(context: Context, stream: Stream, model: String) {
        val key = BrowserPrefs.deepgramKey(context)
        if (key.isBlank()) {
            setState(stream, State.ERROR, "Añade tu clave de Deepgram en Ajustes › Subtítulos.")
            notifyChanged(stream.session)
            return
        }
        setState(stream, State.CONNECTING)
        notifyChanged(stream.session)
        val generation = stream.generation
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
                    setState(stream, State.LISTENING)
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
                    val transcript = CaptionProtocol.parseResult(text) ?: return@post
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
                main.post {
                    if (active[stream.session] !== stream || stream.generation != generation) return@post
                    stream.opened = false
                    stream.socket = null
                    // Si el modelo no admite el idioma, reintentar una vez con nova-2.
                    if (code == 400 && model != "nova-2" && !stream.triedFallback) {
                        stream.triedFallback = true
                        connect(context, stream, "nova-2")
                        return@post
                    }
                    // Corte de red: hasta 3 reintentos con espera creciente; cada uno empieza
                    // una grabación nueva (el audio necesita su cabecera).
                    val transient = code == null || code == 429 || code >= 500
                    if (transient && stream.retries < 3) {
                        val delay = 1000L * (1 shl stream.retries) + (0..400).random()
                        stream.retries++
                        setState(stream, State.CONNECTING, null)
                        notifyChanged(stream.session)
                        main.postDelayed({
                            if (active[stream.session] === stream && stream.generation == generation) {
                                ExtensionManager.restartCaptions(stream.session)
                            }
                        }, delay)
                        return@post
                    }
                    setState(stream, State.ERROR, CaptionProtocol.errorFor(code) + " Toca CC para reintentar.")
                    notifyChanged(stream.session)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post {
                    if (stream.socket === webSocket) {
                        stream.socket = null
                        stream.opened = false
                    }
                }
            }
        })
    }

    private fun close(stream: Stream) {
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
    private fun scheduleClear(stream: Stream, delayMs: Long = 3500L) {
        main.removeCallbacksAndMessages(stream)
        main.postAtTime({
            stream.text.clear()
            notifyChanged(stream.session)
        }, stream, SystemClock.uptimeMillis() + delayMs)
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
        // El texto del reproductor ya es definitivo: vale la pena traducirlo antes y aunque sea corto.
        val minLength = if (stream.domMode) 2 else 24
        val interval = if (stream.domMode) 300L else 700L
        if (interim.length < minLength || now - stream.lastSpeculation < interval) return
        val target = BrowserPrefs.captionTranslateTo(context)
        cache[cacheKey(BrowserPrefs.TRANSLATE_GOOGLE, target, interim)]?.let {
            stream.text.setInterimTranslation(interim, it)
            return
        }
        stream.speculating = true
        stream.lastSpeculation = now
        val generation = stream.translationGeneration
        fast.execute {
            val translated = runCatching { googleLines(listOf(interim), target)?.firstOrNull() }.getOrNull()
            main.post {
                stream.speculating = false
                if (translated.isNullOrBlank() || active[stream.session] !== stream || stream.translationGeneration != generation) return@post
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
        if (!stream.trackMode || !translating(context) || stream.cues.isEmpty()) return
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
                val quick = runCatching { googleLines(lines, target) }.getOrNull() ?: return@execute
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
            val result = runCatching { translateBatch(context, provider, lines, target, textContext) }.getOrNull()
            main.post {
                if (stream.translationGeneration == generation) stream.batcher.done()
                if (active[stream.session] !== stream || stream.translationGeneration != generation) return@post
                var retry = false
                batch.forEachIndexed { index, item ->
                    val translated = result?.getOrNull(index)?.takeIf { it.isNotBlank() }
                    if (translated != null) cache[cacheKey(provider, target, item.text)] = translated
                    if (stream.trackMode) {
                        val cue = stream.cues[item.id.toInt()] ?: return@forEachIndexed
                        if (translated == null && ++cue.failures < 3) {
                            // Corte de red: se reintenta en unos segundos en vez de quedarse sin traducir.
                            stream.bulkQueued -= item.id.toInt()
                            retry = true
                        } else {
                            cue.translated = translated ?: cue.text
                        }
                    } else {
                        // Con IA, sin respuesta se conserva el respaldo rápido de Google.
                        if (translated == null && provider == BrowserPrefs.TRANSLATE_AI) return@forEachIndexed
                        // Si el traductor falla, se queda el texto original (sin bloquear los siguientes).
                        stream.text.setTranslation(item.id, translated ?: item.text)
                    }
                }
                notifyChanged(stream.session)
                when {
                    !stream.trackMode -> pump(context, stream)
                    retry -> main.postDelayed({ if (active[stream.session] === stream) queueAhead(context, stream) }, 2500L)
                    else -> queueAhead(context, stream)
                }
            }
        }
    }

    /**
     * Una petición por lote. Si Google une o parte líneas y no se pueden
     * alinear, el lote se divide en dos (solo unas pocas peticiones más) en
     * vez de dejar todas esas líneas sin traducir.
     */
    private fun googleLines(lines: List<String>, target: String, depth: Int = 0): List<String>? {
        val form = "application/x-www-form-urlencoded".toMediaType()
        val body = CaptionProtocol.googleBatchBody(lines, target)
        // GET para lo habitual (frases cortas); POST solo si la dirección quedaría demasiado larga.
        val request = if (body.length < 1800) Request.Builder().url(CaptionProtocol.GOOGLE_BATCH_URL + "&" + body).build()
        else Request.Builder().url(CaptionProtocol.GOOGLE_BATCH_URL).post(body.toRequestBody(form)).build()
        val aligned = translateClient.newCall(request).execute().use { response ->
            // Error de red o del servicio: no multiplicar peticiones.
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            CaptionProtocol.parseGoogleLines(text, lines.size)
                ?: if (lines.size == 1) CaptionProtocol.parseGoogle(text)?.let { listOf(it) } else null
        }
        if (aligned != null || lines.size < 2 || depth >= 3) return aligned
        val half = lines.size / 2
        val first = googleLines(lines.subList(0, half), target, depth + 1) ?: return null
        val second = googleLines(lines.subList(half, lines.size), target, depth + 1) ?: return null
        return first + second
    }

    private fun translateBatch(context: Context, provider: String, lines: List<String>, target: String, textContext: List<String>): List<String>? {
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
                translateClient.newCall(builder.build()).execute().use { response ->
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
                translateClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) CaptionProtocol.parseDeeplLines(response.body?.string().orEmpty(), lines.size) else null
                }
            }
            else -> googleLines(lines, target)
        }
    }

    private fun notifyChanged(session: GeckoSession) {
        listener?.onCaptionsChanged(session)
    }
}
