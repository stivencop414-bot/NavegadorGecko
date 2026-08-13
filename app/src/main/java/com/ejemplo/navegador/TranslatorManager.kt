package com.ejemplo.navegador

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.net.URLEncoder
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TranslatorManager {
    const val TRANSLATOR_ID =
        "nexo_translator@com.ejemplo.navegador"

    private const val TRANSLATOR_URI =
        "resource://android/assets/extensions/nexo_translator/"

    private const val NATIVE_APP = "cloud_translator"
    private const val MAX_TEXTS = 128
    private const val MAX_CHARS = 24000

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val ports =
        IdentityHashMap<GeckoSession, WebExtension.Port>()
    private val pending =
        IdentityHashMap<GeckoSession, String>()

    private var extension: WebExtension? = null
    private var initialized = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        GeckoRuntimeHolder.get(context)
            .webExtensionController
            .ensureBuiltIn(
                TRANSLATOR_URI,
                TRANSLATOR_ID
            )
            .accept(
                { installed ->
                    if (installed == null) return@accept

                    extension = installed

                    TabManager.liveSessions().forEach {
                        bindSession(it)
                    }
                },
                { error ->
                    Log.e(
                        "NexoTranslator",
                        "No se pudo registrar el traductor",
                        error
                    )
                }
            )
    }

    fun bindSession(session: GeckoSession) {
        val ext = extension ?: return

        session.webExtensionController
            .setMessageDelegate(
                ext,
                messageDelegate,
                NATIVE_APP
            )
    }

    fun translateActive(
        context: Context,
        callback: (Boolean, String) -> Unit
    ) {
        val key = BrowserPrefs.translatorApiKey(context).trim()

        if (key.isBlank()) {
            callback(
                false,
                "Configura tu API key de Google Translate en Configuración."
            )
            return
        }

        val session = TabManager.activeSession()

        if (session == null) {
            callback(false, "No hay una pestaña activa.")
            return
        }

        val target = BrowserPrefs.translatorTarget(context)
        val port = synchronized(ports) { ports[session] }

        if (port == null) {
            synchronized(pending) {
                pending[session] = target
            }
            bindSession(session)

            callback(
                true,
                "Preparando el traductor de esta página…"
            )
            return
        }

        runCatching {
            sendTranslateCommand(port, target)
        }.onSuccess {
            callback(true, "Traduciendo página…")
        }.onFailure {
            callback(
                false,
                "No se pudo iniciar la traducción: ${it.message}"
            )
        }
    }

    private fun sendTranslateCommand(
        port: WebExtension.Port,
        target: String
    ) {
        port.postMessage(
            JSONObject().apply {
                put("type", "translate_page")
                put("target", target)
            }
        )
    }

    private val messageDelegate =
        object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val session = port.sender.session

                if (
                    session == null ||
                    !port.sender.isTopLevel()
                ) {
                    runCatching { port.disconnect() }
                    return
                }

                synchronized(ports) {
                    ports[session] = port
                }

                port.setDelegate(
                    object : WebExtension.PortDelegate {
                        override fun onPortMessage(
                            message: Any,
                            port: WebExtension.Port
                        ) {
                            val json = message as? JSONObject
                                ?: return

                            when (json.optString("type")) {
                                "translator_status" -> {
                                    val msg = json.optString(
                                        "message",
                                        "Traducción finalizada"
                                    )
                                    TabManager.notifyMessage(msg)
                                }
                            }
                        }

                        override fun onDisconnect(
                            port: WebExtension.Port
                        ) {
                            synchronized(ports) {
                                ports.entries.removeAll {
                                    it.value === port
                                }
                            }
                        }
                    }
                )

                val target = synchronized(pending) {
                    pending.remove(session)
                }

                if (target != null) {
                    runCatching {
                        sendTranslateCommand(port, target)
                    }
                }
            }

            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? {
                if (
                    nativeApp != NATIVE_APP ||
                    !sender.isTopLevel() ||
                    sender.session == null
                ) {
                    return null
                }

                val json = message as? JSONObject
                    ?: return GeckoResult.fromValue(
                        errorResponse("Mensaje de traducción inválido.")
                    )

                if (json.optString("type") != "translate") {
                    return GeckoResult.fromValue(
                        errorResponse("Tipo de mensaje no soportado.")
                    )
                }

                val textsJson = json.optJSONArray("texts")
                    ?: return GeckoResult.fromValue(
                        errorResponse("No llegaron textos para traducir.")
                    )

                val texts = buildList {
                    for (i in 0 until textsJson.length()) {
                        val text = textsJson.optString(i).trim()
                        if (text.isNotBlank()) add(text)
                    }
                }

                if (
                    texts.isEmpty() ||
                    texts.size > MAX_TEXTS ||
                    texts.sumOf { it.length } > MAX_CHARS
                ) {
                    return GeckoResult.fromValue(
                        errorResponse(
                            "El lote de traducción es demasiado grande."
                        )
                    )
                }

                val target = json.optString(
                    "target",
                    BrowserPrefs.translatorTarget(AppContext.get())
                ).take(10)

                val result = GeckoResult<Any>()

                executor.execute {
                    val response = runCatching {
                        translateGoogle(
                            AppContext.get(),
                            texts,
                            target
                        )
                    }

                    main.post {
                        response.onSuccess { translated ->
                            result.complete(
                                JSONObject().apply {
                                    put("ok", true)
                                    put(
                                        "translations",
                                        JSONArray(translated)
                                    )
                                }
                            )
                        }.onFailure { error ->
                            result.complete(
                                errorResponse(
                                    error.message
                                        ?: "Error de traducción."
                                )
                            )
                        }
                    }
                }

                return result
            }
        }

    private fun translateGoogle(
        context: Context,
        texts: List<String>,
        target: String
    ): List<String> {
        val key = BrowserPrefs.translatorApiKey(context).trim()
        if (key.isBlank()) {
            error("Falta la API key de Google Translate.")
        }

        val bodyJson = JSONObject().apply {
            put("q", JSONArray(texts))
            put("target", target)
            put("format", "text")
        }

        val encodedKey = URLEncoder.encode(key, "UTF-8")
        val url =
            "https://translation.googleapis.com/" +
            "language/translate/v2?key=$encodedKey"

        val request = Request.Builder()
            .url(url)
            .post(
                bodyJson.toString()
                    .toRequestBody(
                        "application/json; charset=utf-8"
                            .toMediaType()
                    )
            )
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body.string()

            if (!response.isSuccessful) {
                val apiMessage = runCatching {
                    JSONObject(body)
                        .optJSONObject("error")
                        ?.optString("message")
                }.getOrNull()

                error(
                    apiMessage
                        ?.takeIf { it.isNotBlank() }
                        ?: "Google Translate HTTP ${response.code}"
                )
            }

            val translations = JSONObject(body)
                .getJSONObject("data")
                .getJSONArray("translations")

            if (translations.length() != texts.size) {
                error(
                    "Google devolvió ${translations.length()} " +
                    "resultados para ${texts.size} textos."
                )
            }

            return buildList {
                for (i in 0 until translations.length()) {
                    val raw = translations
                        .getJSONObject(i)
                        .getString("translatedText")

                    add(
                        Html.fromHtml(
                            raw,
                            Html.FROM_HTML_MODE_LEGACY
                        ).toString()
                    )
                }
            }
        }
    }

    private fun errorResponse(message: String) =
        JSONObject().apply {
            put("ok", false)
            put("error", message)
        }
}
