package com.ejemplo.navegador

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import org.mozilla.geckoview.GeckoSession
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Subtítulos en vivo: la página envía el audio del video comprimido (Opus,
 * ~24 kbps) y Nexo lo transcribe con Deepgram en streaming. Las frases
 * terminadas se traducen al momento si el usuario lo pidió.
 *
 * Cada grabación de la página (al empezar o reanudar el video) abre una
 * conexión nueva: el primer fragmento lleva la cabecera del contenedor.
 */
object LiveCaptions {
    enum class State { OFF, WAITING, CONNECTING, LISTENING, ERROR }

    interface Listener {
        fun onCaptionsChanged(session: GeckoSession)
    }

    private class Stream(val session: GeckoSession) {
        var socket: WebSocket? = null
        var opened = false
        val queue = ArrayDeque<ByteString>()
        var lastAudioAt = 0L
        var triedFallback = false
        var language = "multi"
        val text = CaptionText()
        var state = State.WAITING
        var message: String? = null
        var generation = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val active = WeakHashMap<GeckoSession, Stream>()
    private var listener: Listener? = null
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (stream in active.values) {
                // Deepgram cierra a los ~10 s sin audio: mantener viva la conexión en pausas cortas.
                if (stream.opened && now - stream.lastAudioAt > 3500L) stream.socket?.send(CaptionProtocol.KEEP_ALIVE)
            }
            if (active.isNotEmpty()) main.postDelayed(this, 4000L)
        }
    }

    fun attach(value: Listener?) { listener = value }

    fun isActive(session: GeckoSession?): Boolean = session != null && active.containsKey(session)

    fun state(session: GeckoSession?): State = session?.let { active[it]?.state } ?: State.OFF

    fun message(session: GeckoSession?): String? = session?.let { active[it]?.message }

    /** Texto actual: principal (traducido o transcrito) y parte pendiente. */
    fun display(context: Context, session: GeckoSession?): Pair<String, String>? {
        val stream = session?.let { active[it] } ?: return null
        if (stream.text.isEmpty) return null
        return stream.text.display(BrowserPrefs.captionTranslateTo(context).isNotBlank())
    }

    /** Activa o apaga los subtítulos del video de esta pestaña. */
    fun toggle(context: Context, session: GeckoSession): Boolean {
        if (active.containsKey(session)) {
            stop(session)
            return false
        }
        if (BrowserPrefs.deepgramKey(context).isBlank()) return false
        active[session] = Stream(session).apply { language = BrowserPrefs.captionLanguage(context) }
        main.removeCallbacks(keepAlive)
        main.postDelayed(keepAlive, 4000L)
        ExtensionManager.setCaptions(session, true)
        notifyChanged(session)
        return true
    }

    fun stop(session: GeckoSession) {
        val stream = active.remove(session) ?: return
        close(stream)
        ExtensionManager.setCaptions(session, false)
        notifyChanged(session)
    }

    fun stopAll() {
        active.keys.toList().forEach { stop(it) }
    }

    // --- Mensajes del puente de la página --------------------------------

    fun onRecordingStarted(context: Context, session: GeckoSession) {
        val stream = active[session] ?: return
        close(stream)
        stream.generation++
        stream.triedFallback = false
        stream.message = null
        connect(context.applicationContext, stream, CaptionProtocol.modelFor(stream.language))
    }

    fun onAudio(session: GeckoSession, base64: String) {
        val stream = active[session] ?: return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        if (bytes.isEmpty() || bytes.size > 256 * 1024) return
        stream.lastAudioAt = System.currentTimeMillis()
        val chunk = bytes.toByteString()
        val socket = stream.socket
        if (stream.opened && socket != null) socket.send(chunk)
        else if (stream.queue.size < 120) stream.queue.addLast(chunk)
    }

    fun onRecordingStopped(session: GeckoSession) {
        val stream = active[session] ?: return
        close(stream)
        stream.state = State.WAITING
        notifyChanged(session)
    }

    fun onPageError(session: GeckoSession, message: String) {
        val stream = active[session] ?: return
        stream.state = State.ERROR
        stream.message = message
        notifyChanged(session)
    }

    // --- Conexión ----------------------------------------------------------

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
                    val transcript = CaptionProtocol.parseResult(text) ?: return@post
                    val segment = stream.text.onTranscript(transcript)
                    if (segment != null) translate(context, stream, segment)
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
                    stream.state = State.ERROR
                    stream.message = CaptionProtocol.errorFor(code)
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
    private fun scheduleClear(stream: Stream) {
        main.removeCallbacksAndMessages(stream)
        main.postAtTime({
            stream.text.clear()
            notifyChanged(stream.session)
        }, stream, android.os.SystemClock.uptimeMillis() + 3500L)
    }

    // --- Traducción ---------------------------------------------------------

    private fun translate(context: Context, stream: Stream, segment: CaptionText.Segment) {
        val target = BrowserPrefs.captionTranslateTo(context)
        if (target.isBlank()) return
        val provider = BrowserPrefs.translationProvider(context)
        val deeplKey = BrowserPrefs.deeplKey(context)
        io.execute {
            val translated = runCatching {
                if (provider == BrowserPrefs.TRANSLATE_DEEPL && deeplKey.isNotBlank()) {
                    val request = Request.Builder()
                        .url(CaptionProtocol.deeplEndpoint(deeplKey))
                        .header("Authorization", "DeepL-Auth-Key $deeplKey")
                        .post(CaptionProtocol.deeplBody(segment.original, target)
                            .toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) CaptionProtocol.parseDeepl(response.body?.string().orEmpty()) else null
                    }
                } else {
                    val request = Request.Builder().url(CaptionProtocol.googleUrl(segment.original, target)).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) CaptionProtocol.parseGoogle(response.body?.string().orEmpty()) else null
                    }
                }
            }.getOrNull() ?: segment.original
            main.post {
                if (active[stream.session] !== stream) return@post
                stream.text.setTranslation(segment.id, translated)
                notifyChanged(stream.session)
            }
        }
    }

    private fun notifyChanged(session: GeckoSession) {
        listener?.onCaptionsChanged(session)
    }
}
