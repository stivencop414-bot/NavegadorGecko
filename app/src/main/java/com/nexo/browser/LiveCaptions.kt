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
        @Volatile var stopped = false
        val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
        var deviceAudio = false
        // Captura de Android: nivel del audio que llega (0–100), desde cuándo hay señal y si ya se reabrió el audio.
        var deviceLevel = 0
        var deviceSignalSince = 0L
        var devicePlaying = false
        var deviceNudged = false
        // El usuario eligió la captura de Android y Android aún pide permiso: la página queda
        // fuera para que un error suyo (p. ej. "no deja grabar") no cancele la solicitud.
        var deviceRequested = false
        // Etapas para el diagnóstico (hoja de CC).
        var videoFound = false
        var socketOpenedAt = 0L
        // Motivo del último fallo de la captura en la página y si ya se ofreció la de Android.
        var pageFailure: String? = null
        var offeredDevice = false
        // El audio del video solo sale hacia Deepgram si el usuario lo eligió (o lo dejó automático en Ajustes).
        var audioAllowed = false
        var offeredChoice = false
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
            // Las peticiones llevan claves: nunca seguir una redirección a otro destino.
            .followRedirects(false).followSslRedirects(false)
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
                // La captura de Android tiene su propio diagnóstico (nivel y Deepgram), al segundo.
                if (!stream.deviceAudio && stream.startedAt > 0 && now - maxOf(stream.startedAt, stream.lastResultAt) > 15000 && stream.state != State.ERROR) {
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

    /** Subtítulos leídos del reproductor de la página (sin Deepgram). */
    fun isDomMode(session: GeckoSession?): Boolean = session?.let { active[it]?.domMode } == true

    /**
     * Línea de estado cuando aún no hay texto. Los diagnósticos concretos
     * (mensaje del flujo, errores) se mantienen; el aviso genérico solo dura
     * [HINT_MS] tras el último cambio para no tapar el video sin motivo.
     */
    private fun statusLine(stream: Stream): CaptionDisplay? {
        stream.message?.let { return CaptionDisplay(it, "", status = true) }
        if (SystemClock.uptimeMillis() - stream.stateChangedAt > HINT_MS) return null
        val text = when {
            !stream.delivered -> "CC · esperando a que cargue la página…"
            stream.state == State.CONNECTING -> "CC · conectando con Deepgram…"
            stream.trackMode -> "CC · pista detectada, esperando subtítulos…"
            stream.domMode -> "CC · leyendo los subtítulos del reproductor"
            stream.state == State.LISTENING -> "CC · esperando voz…"
            else -> "CC · buscando el video en la página y sus marcos…"
        }
        return CaptionDisplay(text, "", status = true)
    }

    /** Hay un aviso de estado que caduca: la interfaz debe volver a mirar pasado [HINT_MS]. */
    fun statusExpires(session: GeckoSession?): Boolean =
        session?.let { active[it] }?.let { it.message == null && it.state != State.ERROR } == true

    private fun setState(stream: Stream, state: State, message: String? = stream.message) {
        if (stream.state != state || stream.message != message) stream.stateChangedAt = SystemClock.uptimeMillis()
        stream.state = state
        stream.message = message
    }

    /** Qué mostrar ahora según el modo elegido (original, traducido o ambos). */
    fun display(context: Context, session: GeckoSession?): CaptionDisplay? {
        val stream = session?.let { active[it] } ?: return null
        if (stream.state == State.ERROR) return CaptionDisplay(stream.message ?: "Error de subtítulos", "", status = true)
        val mode = BrowserPrefs.captionMode(context)
        val translating = BrowserPrefs.captionTranslateTo(context).isNotBlank() && mode != "original"
        if (stream.trackMode) {
            val lines = stream.activeCues.mapNotNull { stream.cues[it] }
            if (lines.isEmpty()) return statusLine(stream)
            val done = lines.filter { !translating || it.translated != null }
            val primary = done.joinToString("\n") { if (translating) it.translated!! else it.text }
            val pending = if (translating) lines.filter { it.translated == null }.joinToString("\n") { it.text } else ""
            val secondary = if (translating && mode == "both") done.joinToString("\n") { it.text } else ""
            return CaptionDisplay(primary, pending,
                if (stream.message?.startsWith("No se pudo traducir") == true) stream.message.orEmpty() else secondary)
        }
        // Red de seguridad: aunque se pierda el aviso programado, un subtítulo sin voz
        // reciente no se vuelve a dibujar (el texto en vivo nunca se queda colgado).
        if (!stream.domMode && !stream.text.isEmpty &&
            CaptionText.expired(stream.lastResultAt, System.currentTimeMillis(), clearDelay(context) + 400L)) stream.text.clear()
        if (stream.text.isEmpty) return statusLine(stream)
        // Longitud elegida en Ajustes: cuánto texto reciente se conserva en pantalla.
        stream.text.maxChars = maxChars(BrowserPrefs.captionLines(context))
        return stream.text.view(mode, translating).let { view ->
            if (stream.message?.startsWith("No se pudo traducir") == true) view.copy(secondary = stream.message.orEmpty()) else view
        }.takeIf { !it.isEmpty }
    }

    /** Caracteres visibles según las líneas elegidas (1: frase corta … 3: varias). */
    fun maxChars(lines: Int): Int = when (lines) { 1 -> 64; 2 -> 120; else -> 170 }

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
        // Texto primero (pista, reproductor, archivo .vtt/.srt). El audio solo con permiso explícito.
        stream.delivered = ExtensionManager.setCaptions(
            stream.session,
            true,
            live = audioAllowed(stream),
            language = stream.language,
            target = BrowserPrefs.captionTranslateTo(context)
        )
        stream.stateChangedAt = SystemClock.uptimeMillis()
    }

    /**
     * Un marco de la página conectó y ya recibió la orden: la página está lista.
     * No se reinicia nada (los iframes de anuncios conectan a menudo).
     */
    fun onBridgeConnected(session: GeckoSession) {
        val stream = active[session] ?: return
        if (stream.delivered) return
        stream.delivered = true
        stream.stateChangedAt = SystemClock.uptimeMillis()
        notifyChanged(session)
    }

    /** Idioma preferido del video (para elegir la pista del reproductor). */
    fun language(session: GeckoSession): String = active[session]?.language ?: "multi"

    private fun resetSource(stream: Stream) {
        main.removeCallbacksAndMessages(stream)
        invalidateTranslations(stream)
        stream.trackMode = false
        stream.domMode = false
        stream.dom.reset()
        stream.text.clear()
        stream.cues.clear()
        stream.activeCues = emptyList()
        stream.bulkQueued.clear()
        stream.pendingCueIds.clear()
    }

    fun stop(session: GeckoSession) {
        val stream = active.remove(session) ?: return
        PlaybackAudioCapture.cancel(session)
        main.removeCallbacksAndMessages(stream)
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
            provider == BrowserPrefs.TRANSLATE_AI && aiTranslators(context).isEmpty() -> BrowserPrefs.TRANSLATE_GOOGLE
            else -> provider
        }
    }

    private fun cacheKey(provider: String, target: String, text: String) = provider + "\u0000" + target + "\u0000" + text

    private fun warmUp(context: Context) { /* No provider request before actual text. */ }

    /** Cuerpo de respuesta de un servicio con clave, como mucho 1 MB (si no, vacío). */
    private fun limitedBody(response: Response, max: Long = 1_000_000L): String {
        val source = response.body?.source() ?: return ""
        source.request(max + 1)
        return if (source.buffer.size > max) "" else source.buffer.readUtf8()
    }

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
        if (stream.deviceAudio) { close(stream); connect(context.applicationContext,stream,CaptionProtocol.modelFor(stream.language)) }
        else if (!stream.trackMode && !stream.domMode) ExtensionManager.restartCaptions(stream.session)
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
        stream.delivered = true
        stream.videoFound = true
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
        stream.domMode = false
        stream.text.clear()
        stream.receivedBytes = 0
        stream.headerChecked = false
        stream.signal = false
        stream.startedAt = System.currentTimeMillis()
        stream.lastResultAt = 0
        setState(stream, State.CONNECTING, "Captura Opus iniciada · conectando…")
        connect(app, stream, stream.model.ifBlank { CaptionProtocol.modelFor(stream.language) })
    }

    fun captureToken(session: GeckoSession): Any? = active[session]
    fun matchesCapture(session: GeckoSession, token: Any): Boolean = active[session] === token
    fun usingDeviceAudio(session: GeckoSession?): Boolean = session?.let { active[it]?.deviceAudio } == true

    /** La página no participa: captura de Android activa o pidiendo permiso. */
    fun pageSuspended(session: GeckoSession?): Boolean =
        session?.let { active[it] }?.let { it.deviceAudio || it.deviceRequested } == true

    /**
     * El usuario aceptó la captura de Android: se apaga la captura de la
     * página (sus errores ya no llegan) mientras Android pide permiso.
     */
    fun beginDeviceRequest(session: GeckoSession, token: Any): Boolean {
        val stream = active[session] ?: return false
        if (stream !== token) return false
        stream.deviceRequested = true
        stream.pageFailure = null
        ExtensionManager.setCaptions(session, false)
        close(stream)
        setState(stream, State.WAITING, "Esperando la autorización de Android para escuchar el video…")
        notifyChanged(session)
        return true
    }

    /** Android no llegó a capturar (permiso denegado, cancelado…): se explica y la página vuelve a buscar texto. */
    fun endDeviceRequest(context: Context, session: GeckoSession, message: String) {
        val stream = active[session] ?: return
        if (!stream.deviceRequested || stream.deviceAudio) return
        stream.deviceRequested = false
        // Sin volver a ofrecer lo mismo en bucle: solo pistas y texto del reproductor.
        stream.offeredDevice = true
        stream.audioAllowed = false
        setState(stream, State.WAITING, message)
        deliver(context.applicationContext, stream)
        notifyChanged(session)
    }

    /** Etapas de los subtítulos para la hoja de CC: qué funciona y dónde se corta. */
    fun stages(session: GeckoSession?): List<Pair<String, Boolean?>> {
        val stream = session?.let { active[it] } ?: return emptyList()
        val text = stream.lastResultAt > 0L || !stream.text.isEmpty || stream.cues.isNotEmpty()
        val textSource = stream.trackMode || stream.domMode
        val list = mutableListOf<Pair<String, Boolean?>>()
        list += (if (stream.deviceAudio) "Captura de Android activa" else "Reproductor encontrado") to (stream.deviceAudio || stream.videoFound || textSource || stream.startedAt > 0)
        if (textSource) {
            list += (if (stream.trackMode) "Pista de subtítulos del video" else "Texto del reproductor") to true
            list += "Texto recibido" to text
            return list
        }
        list += "Pista de subtítulos del video" to false
        if (!stream.deviceAudio && stream.startedAt == 0L) {
            list += "Audio" to (if (audioAllowed(stream)) null else false)
            return list
        }
        list += (if (stream.deviceAudio) "Señal de audio (nivel ${stream.deviceLevel} %)" else "Señal de audio") to (if (stream.receivedBytes == 0L) null else stream.signal)
        list += "Conexión con Deepgram" to (if (stream.opened) true else if (stream.state == State.ERROR) false else null)
        list += "Texto recibido" to (if (text) true else if (stream.socketOpenedAt > 0 && System.currentTimeMillis() - stream.socketOpenedAt > 20_000 && stream.signal) false else null)
        return list
    }

    fun startDeviceAudio(session: GeckoSession, token: Any): Boolean {
        val stream = active[session] ?: return false
        if (stream !== token || limitReached(AppContext.get())) return false
        stream.deviceRequested = false
        ExtensionManager.setCaptions(session,false)
        close(stream); resetSource(stream)
        stream.deviceAudio = true
        stream.deviceLevel = 0; stream.deviceSignalSince = 0L; stream.devicePlaying = false; stream.deviceNudged = false
        stream.trackMode = false; stream.domMode = false
        stream.text.clear(); stream.headerChecked = true
        stream.receivedBytes = 0; stream.lastAudioAt = 0; stream.lastResultAt = 0
        stream.startedAt = System.currentTimeMillis(); stream.signal = false
        setState(stream,State.CONNECTING,"Capturando el audio con Android · conectando…")
        connect(AppContext.get(),stream,CaptionProtocol.modelFor(stream.language))
        return true
    }
    /**
     * Diagnóstico de la captura de Android, una vez por segundo: [peak] es el
     * pico PCM16 (0–32767) del último segundo y [playing] si el video suena.
     */
    fun onDeviceLevel(session: GeckoSession, peak: Int, playing: Boolean) {
        val stream = active[session] ?: return
        if (!stream.deviceAudio || stream.state == State.ERROR) return
        val level = (peak * 100 / 32767).coerceIn(0, 100).let { if (peak > 120 && it == 0) 1 else it }
        stream.deviceLevel = level
        stream.devicePlaying = playing
        val now = SystemClock.uptimeMillis()
        if (level > 0 && stream.deviceSignalSince == 0L) stream.deviceSignalSince = now
        // Con texto en pantalla el diagnóstico sobra.
        if (stream.lastResultAt > 0L) return
        val message = deviceMessage(stream, now)
        if (message != stream.message) { stream.message = message; notifyChanged(session) }
    }

    private fun languageLabel(stream: Stream): String =
        if (stream.language == "multi") "detección automática" else CaptionProtocol.TARGET_LANGUAGES.firstOrNull { it.code == stream.language }?.label ?: stream.language

    private fun deviceMessage(stream: Stream, now: Long): String {
        val level = "nivel ${(stream.deviceLevel / 5) * 5} %".let { if (stream.deviceLevel in 1..4) "nivel bajo" else it }
        return when {
            stream.deviceLevel == 0 && !stream.devicePlaying -> "Android escuchando · aún no llega sonido (¿el video está en pausa?)"
            stream.deviceLevel == 0 -> "Android captura pero solo llega silencio (nivel 0 %)" +
                if (stream.deviceNudged) " · reabriendo el audio del video…" else " · comprueba que el video suene"
            !stream.opened -> "Android oye el video ($level) · conectando con Deepgram…"
            stream.deviceSignalSince > 0 && now - stream.deviceSignalSince > 15000 ->
                "Android oye el video ($level), pero Deepgram no devuelve texto. Revisa el idioma del audio en CC (ahora: ${languageLabel(stream)})."
            else -> "Android oye el video ($level) · Deepgram escuchando (${languageLabel(stream)})…"
        }
    }

    fun onDeviceAudio(session: GeckoSession, bytes: ByteArray) {
        val stream = active[session] ?: return
        if (!stream.deviceAudio) return
        if (!stream.signal && PlaybackPcm.hasSignal(bytes)) onStatus(session,"signal")
        acceptAudio(session,stream,bytes)
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
        acceptAudio(session,stream,bytes)
    }

    private fun acceptAudio(session: GeckoSession, stream: Stream, bytes: ByteArray) {
        if (stream.state == State.ERROR || stream.startedAt == 0L || bytes.isEmpty() || bytes.size > 256*1024) return
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
                restartAudio(stream)
            }
        } else if (stream.queue.size < 40) {
            stream.queue.addLast(chunk)
        } else {
            onPageError(AppContext.get(), session, "buffer", "La conexión tarda demasiado en aceptar audio. Apaga y activa CC para reintentar.")
        }
    }

    fun onRecordingStopped(session: GeckoSession) {
        val stream = active[session] ?: return
        // La captura de Android no depende de la página: que un marco se desconecte no la corta.
        if (stream.deviceAudio || stream.deviceRequested) return
        close(stream)
        invalidateTranslations(stream)
        stream.trackMode = false
        stream.activeCues = emptyList()
        stream.text.clear()
        flushUsage(stream, force = true)
        stream.lastAudioAt = 0
        stream.startedAt = 0
        // Con la pista o el texto del reproductor no hay grabación que esperar.
        if (!stream.domMode && !stream.trackMode) setState(stream, State.WAITING, "Esperando reproducción o un nuevo video…")
        notifyChanged(session)
    }

    private fun audioAllowed(stream: Stream): Boolean =
        BrowserPrefs.deepgramKey(AppContext.get()).isNotBlank() &&
            (stream.audioAllowed || BrowserPrefs.captionAudioAuto(AppContext.get()))

    fun audioAllowed(session: GeckoSession): Boolean = active[session]?.let { audioAllowed(it) } == true

    /** El usuario eligió transcribir el audio de este video con Deepgram. */
    fun allowAudio(context: Context, session: GeckoSession?) {
        val stream = session?.let { active[it] } ?: return
        stream.audioAllowed = true
        stream.pageFailure = null
        setState(stream, State.WAITING, "Transcribiendo el audio con Deepgram · reproduce el video…")
        deliver(context.applicationContext, stream)
        notifyChanged(stream.session)
    }

    /**
     * true una sola vez por activación cuando el video no trae ningún texto y
     * el audio no está permitido: la interfaz pregunta qué hacer.
     */
    fun takeNoTextChoice(session: GeckoSession?): Boolean {
        val stream = session?.let { active[it] } ?: return false
        if (stream.offeredChoice || stream.pageFailure != "need_key" || audioAllowed(stream)) return false
        stream.offeredChoice = true
        return true
    }

    /** Fallos de la página que la captura de Android puede resolver (no claves, límites ni red). */
    private val DEVICE_FIXABLE = setOf("no_audio", "silent", "capture", "unsupported", "mime", "SecurityError", "NotSupportedError", "suspended")

    /**
     * true una sola vez por activación cuando la página no pudo dar su audio:
     * la interfaz ofrece entonces la captura de Android en un toque.
     */
    fun takeDeviceSuggestion(session: GeckoSession?): Boolean {
        val stream = session?.let { active[it] } ?: return false
        if (stream.offeredDevice || stream.deviceAudio || stream.pageFailure !in DEVICE_FIXABLE) return false
        stream.offeredDevice = true
        return true
    }

    /** Errores que vienen de la captura de Android o de Deepgram (no de la página). */
    private val NATIVE_ERRORS = setOf("android_capture", "deepgram", "closed", "limit", "buffer", "need_deepgram", "header", "mime")

    fun onPageError(context: Context, session: GeckoSession, code: String, message: String) {
        val stream = active[session] ?: return
        // Con Android capturando o pidiendo permiso, un error de la página no cancela nada.
        if ((stream.deviceAudio || stream.deviceRequested) && code !in NATIVE_ERRORS) return
        stream.deviceRequested = false
        stream.delivered = true
        stream.pageFailure = code
        PlaybackAudioCapture.cancel(session)
        stream.deviceAudio = false
        if (code == "need_key") {
            // La página sigue vigilando: si el usuario enciende el CC del reproductor, Nexo lo toma solo.
            setState(stream, State.ERROR,
                "Este video no trae subtítulos de texto. Si enciendes los del reproductor, Nexo los toma solo; " +
                    "también puedes usar los subtítulos instantáneos de Android o transcribir el audio.")
            notifyChanged(session)
            return
        }
        close(stream)
        invalidateTranslations(stream)
        // Stop page resources without dropping the error from the native UI.
        ExtensionManager.setCaptions(session, false)
        setState(stream, State.ERROR, message)
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
        invalidateTranslations(stream)
        stream.startedAt = 0
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
            // El reproductor dejó su texto fijo mucho rato (sin voz): no taparlo indefinidamente.
            main.postAtTime({
                stream.text.clear()
                notifyChanged(stream.session)
            }, stream, SystemClock.uptimeMillis() + maxOf(clearDelay(app) * 3, 9000L))
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
            onPageError(context, stream.session, "limit", "Límite diario de subtítulos alcanzado (${BrowserPrefs.captionDailyLimit(context)} min).")
        }
    }

    private fun connect(context: Context, stream: Stream, model: String) {
        val key = BrowserPrefs.deepgramKey(context)
        if (key.isBlank()) {
            onPageError(context,stream.session,"need_deepgram","Añade tu clave de Deepgram en Ajustes › Subtítulos.")
            return
        }
        setState(stream, State.CONNECTING)
        notifyChanged(stream.session)
        val generation = ++stream.generation
        val request = Request.Builder()
            .url((if(stream.deviceAudio) PlaybackPcm.listenUrl(stream.language,model) else CaptionProtocol.listenUrl(stream.language, model)).replaceFirst("wss://", "https://"))
            .header("Authorization", "Token $key")
            .build()
        stream.socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (active[stream.session] !== stream || stream.generation != generation) {
                        webSocket.close(1000, null); return@post
                    }
                    stream.opened = true
                    stream.socketOpenedAt = System.currentTimeMillis()
                    setState(stream, State.LISTENING)
                    while (stream.queue.isNotEmpty()) webSocket.send(stream.queue.removeFirst())
                    notifyChanged(stream.session)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    if (active[stream.session] !== stream || stream.generation != generation) return@post
                    // Fin de frase: el borrado ya está programado desde el último texto con voz.
                    if (CaptionProtocol.isUtteranceEnd(text)) return@post
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
                    // Solo la voz reprograma el borrado. Antes cualquier mensaje (Deepgram manda
                    // resultados vacíos durante el silencio) lo cancelaba y el último subtítulo
                    // se quedaba en pantalla minutos después de que dejaran de hablar.
                    if (transcript.text.isNotBlank()) scheduleClear(stream, clearDelay(context))
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
                        restartAudio(stream)
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
                                restartAudio(stream)
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
        if (code in setOf("signal", "quiet", "paused", "muted", "meter_unavailable")) stream.videoFound = true
        if (code == "signal") stream.signal = true
        // Con texto en pantalla los avisos de señal sobran (el texto ya prueba que hay audio).
        if (code == "signal" && stream.lastResultAt > 0L) return
        stream.message = when (code) {
            "signal" -> "Audio con señal detectada · esperando transcripción…"
            "quiet" -> "No llega sonido del video desde hace un rato (¿pausa, anuncio o reproductor cambiado?). Nexo sigue escuchando."
            "meter_unavailable" -> "Audio capturado; medidor no disponible. Esperando transcripción…"
            "paused" -> "Esperando reproducción…"
            "muted" -> "El video está en silencio: activa su sonido y Nexo empezará a transcribir."
            "device_quiet" -> {
                // Silencio con el video sonando: puede seguir en la vía de baja latencia que
                // Android no captura. Se reabre su audio una vez (un salto al mismo punto).
                if (stream.deviceAudio && !stream.deviceNudged) {
                    stream.deviceNudged = true
                    ExtensionManager.nudgeMedia(session)
                }
                deviceMessage(stream, SystemClock.uptimeMillis())
            }
            else -> stream.message
        }
        notifyChanged(session)
    }

    /** La app concedió el video de un marco a los subtítulos (diagnóstico). */
    fun onVideoGranted(session: GeckoSession) {
        active[session]?.videoFound = true
    }

    fun reapply(context: Context) {
        active.keys.toList().forEach { session ->
            if (!BrowserPrefs.captionsEnabled(context)) stop(session)
            else { retarget(context, session); relisten(context, session); ExtensionManager.restoreCaptions(session) }
        }
    }

    private fun restartAudio(stream: Stream) {
        if (stream.deviceAudio) { close(stream); connect(AppContext.get(),stream,stream.model.ifBlank { CaptionProtocol.modelFor(stream.language) }) }
        else ExtensionManager.restartCaptions(stream.session)
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

    /** Segundos sin voz tras los que se borra el subtítulo (Ajustes de CC). */
    private fun clearDelay(context: Context): Long = BrowserPrefs.captionClearSeconds(context) * 1000L

    /** Tras una pausa en el habla, el texto se borra para no tapar el video. */
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
        // Menos espera: con frases a medias ya se adelanta la traducción (se corrige al llegar la final).
        val minLength = if (stream.domMode) 2 else 14
        val interval = if (stream.domMode) 300L else 450L
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
                var retry = false
                if (result == null) stream.message = "No se pudo traducir con el servicio elegido; se conserva el texto original."
                else if (stream.message?.startsWith("No se pudo traducir") == true) stream.message = null
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
     * vez de dejar todas esas líneas sin traducir. Las peticiones se cancelan
     * al cambiar de idioma o apagar los subtítulos.
     */
    private fun googleLines(stream: Stream, generation: Int, lines: List<String>, target: String, depth: Int = 0): List<String>? {
        val form = "application/x-www-form-urlencoded".toMediaType()
        val body = CaptionProtocol.googleBatchBody(lines, target)
        // GET para lo habitual (frases cortas); POST solo si la dirección quedaría demasiado larga.
        val request = if (body.length < 1800) Request.Builder().url(CaptionProtocol.GOOGLE_BATCH_URL + "&" + body).build()
        else Request.Builder().url(CaptionProtocol.GOOGLE_BATCH_URL).post(body.toRequestBody(form)).build()
        val aligned = withResponse(stream, generation, request) { response ->
            // Error de red o del servicio: no multiplicar peticiones.
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val text = limitedBody(response)
            CaptionProtocol.parseGoogleLines(text, lines.size)
                ?: if (lines.size == 1) CaptionProtocol.parseGoogle(text)?.let { listOf(it) } else null
        }
        if (aligned != null || lines.size < 2 || depth >= 3) return aligned
        val half = lines.size / 2
        val first = googleLines(stream, generation, lines.subList(0, half), target, depth + 1) ?: return null
        val second = googleLines(stream, generation, lines.subList(half, lines.size), target, depth + 1) ?: return null
        return first + second
    }

    private fun translateBatch(context: Context, stream: Stream, generation: Int, provider: String, lines: List<String>, target: String, textContext: List<String>): List<String>? {
        val form = "application/x-www-form-urlencoded".toMediaType()
        return when (provider) {
            BrowserPrefs.TRANSLATE_AI -> raceAi(stream, generation, aiTranslators(context),
                CaptionProtocol.LANGUAGE_NAMES[target] ?: target, lines, textContext)
            BrowserPrefs.TRANSLATE_DEEPL -> {
                val key = BrowserPrefs.deeplKey(context)
                if (key.isBlank()) return null
                val request = Request.Builder().url(CaptionProtocol.deeplEndpoint(key))
                    .header("Authorization", "DeepL-Auth-Key $key")
                    .post(CaptionProtocol.deeplBatchBody(lines, target).toRequestBody(form))
                    .build()
                withResponse(stream, generation, request) { response ->
                    if (response.isSuccessful) CaptionProtocol.parseDeeplLines(limitedBody(response), lines.size) else null
                }
            }
            else -> googleLines(stream, generation, lines, target)
        }
    }


    /** Un modelo de IA para traducir: el principal ("Modelo 1") o el segundo ("Modelo 2"). */
    private data class AiTranslator(val label: String, val provider: String, val endpoint: String, val model: String, val key: String) {
        val name: String get() = model.ifBlank { CaptionProtocol.AI_MODELS[provider].orEmpty() }.ifBlank { label }
    }

    /** Modelos configurados (con clave, o propio sin clave). */
    private fun aiTranslators(context: Context): List<AiTranslator> {
        val list = mutableListOf<AiTranslator>()
        fun add(label: String, provider: String, endpoint: String, model: String, key: String) {
            if (key.isNotBlank() || provider == "custom") list += AiTranslator(label, provider, endpoint, model, key)
        }
        add("Modelo 1", BrowserPrefs.aiTranslateProvider(context), BrowserPrefs.aiTranslateEndpoint(context),
            BrowserPrefs.aiTranslateModel(context), BrowserPrefs.aiTranslateKey(context))
        if (BrowserPrefs.aiTranslate2Enabled(context)) {
            add("Modelo 2", BrowserPrefs.aiTranslate2Provider(context), BrowserPrefs.aiTranslate2Endpoint(context),
                BrowserPrefs.aiTranslate2Model(context), BrowserPrefs.aiTranslate2Key(context))
        }
        return list
    }

    // Último modelo que ganó la carrera y cuánto tardó (para la hoja de CC).
    @Volatile private var lastAiWinner: String? = null
    @Volatile private var lastAiMs = 0L
    private val aiWins = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun aiCall(stream: Stream, generation: Int, translator: AiTranslator, targetName: String,
                       lines: List<String>, textContext: List<String>): okhttp3.Call? {
        val request = CaptionProtocol.aiRequest(translator.provider, translator.endpoint, translator.model, translator.key,
            targetName, lines, textContext)
        // Una clave nunca viaja sin cifrar (salvo a un servidor del propio teléfono).
        if (!CaptionProtocol.isSafeKeyedEndpoint(request.url)) return null
        val builder = Request.Builder().url(request.url).post(request.body.toRequestBody("application/json".toMediaType()))
        request.headers.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
        return synchronized(stream) {
            if (stream.stopped || stream.translationGeneration != generation) null
            else translateClient.newCall(builder.build()).also { stream.calls.add(it) }
        }
    }

    /**
     * Traducción con IA. Con dos modelos se piden los dos a la vez y se usa la
     * primera respuesta válida; la otra se cancela. Si uno falla, se espera al
     * otro. Así el subtítulo sale con el más rápido en cada momento y una caída
     * de un proveedor no deja el video sin traducción.
     */
    private fun raceAi(stream: Stream, generation: Int, translators: List<AiTranslator>, targetName: String,
                       lines: List<String>, textContext: List<String>): List<String>? {
        if (translators.isEmpty()) return null
        val started = SystemClock.uptimeMillis()
        val result = FirstAnswer.race(translators.map { translator ->
            { done: (List<String>?) -> Unit ->
                aiCall(stream, generation, translator, targetName, lines, textContext)?.let { call ->
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { stream.calls.remove(call); done(null) }
                        override fun onResponse(call: okhttp3.Call, response: Response) {
                            val parsed = runCatching {
                                response.use { if (it.isSuccessful) CaptionProtocol.parseAiLines(limitedBody(it), lines.size) else null }
                            }.getOrNull()
                            stream.calls.remove(call)
                            done(parsed)
                        }
                    })
                    FirstAnswer.Attempt { call.cancel(); stream.calls.remove(call) }
                }
            }
        }, 26_000L)
        if (result != null) {
            val translator = translators[result.first]
            lastAiWinner = translator.label + " · " + translator.name
            lastAiMs = SystemClock.uptimeMillis() - started
            aiWins.merge(translator.label, 1, Int::plus)
        }
        return result?.second
    }

    /** "Modelo 2 · gemini-2.5-flash · 0,8 s" (último en responder primero), si hubo carrera. */
    fun aiTranslationWinner(context: Context): String? {
        if (BrowserPrefs.translationProvider(context) != BrowserPrefs.TRANSLATE_AI) return null
        val winner = lastAiWinner ?: return null
        val wins = if (aiWins.size > 1 || BrowserPrefs.aiTranslate2Enabled(context))
            " · gana ${aiWins["Modelo 1"] ?: 0}–${aiWins["Modelo 2"] ?: 0}" else ""
        return "$winner · ${"%.1f".format(lastAiMs / 1000.0)} s$wins"
    }

    private fun notifyChanged(session: GeckoSession) {
        listener?.onCaptionsChanged(session)
    }
}
