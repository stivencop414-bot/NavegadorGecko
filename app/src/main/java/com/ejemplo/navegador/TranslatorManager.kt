package com.ejemplo.navegador

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.IdentityHashMap

object TranslatorManager {
    const val TRANSLATOR_ID =
        "nexo_translator@com.ejemplo.navegador"

    private const val TRANSLATOR_URI =
        "resource://android/assets/extensions/nexo_translator/"

    private const val NATIVE_APP = "cloud_translator"
    private const val MAX_TEXTS = 128
    private const val MAX_CHARS = 24000

    private val ports =
        IdentityHashMap<GeckoSession, WebExtension.Port>()
    private val pending =
        IdentityHashMap<GeckoSession, String>()

    private val languageIdentifier =
        LanguageIdentification.getClient()

    private var extension: WebExtension? = null
    private var initialized = false

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
                "Preparando traducción en el dispositivo…"
            )
            return
        }

        runCatching {
            sendTranslateCommand(port, target)
        }.onSuccess {
            callback(
                true,
                "Traduciendo en el dispositivo…"
            )
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

                            if (
                                json.optString("type") ==
                                "translator_status"
                            ) {
                                TabManager.notifyMessage(
                                    json.optString(
                                        "message",
                                        "Traducción finalizada"
                                    )
                                )
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
                        errorResponse(
                            "Mensaje de traducción inválido."
                        )
                    )

                if (json.optString("type") != "translate") {
                    return GeckoResult.fromValue(
                        errorResponse(
                            "Tipo de mensaje no soportado."
                        )
                    )
                }

                val textsJson = json.optJSONArray("texts")
                    ?: return GeckoResult.fromValue(
                        errorResponse(
                            "No llegaron textos para traducir."
                        )
                    )

                val texts = buildList {
                    for (i in 0 until textsJson.length()) {
                        val text =
                            textsJson.optString(i).trim()

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
                    BrowserPrefs.translatorTarget(
                        AppContext.get()
                    )
                )

                val result = GeckoResult<Any>()

                translateOnDevice(
                    texts,
                    target,
                    onSuccess = { translated ->
                        result.complete(
                            JSONObject().apply {
                                put("ok", true)
                                put(
                                    "translations",
                                    JSONArray(translated)
                                )
                            }
                        )
                    },
                    onError = { error ->
                        result.complete(
                            errorResponse(error)
                        )
                    }
                )

                return result
            }
        }

    private fun translateOnDevice(
        texts: List<String>,
        targetTag: String,
        onSuccess: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        val target =
            TranslateLanguage.fromLanguageTag(targetTag)

        if (target == null) {
            onError(
                "El idioma destino no es compatible."
            )
            return
        }

        val sample = texts
            .joinToString(" ")
            .take(4000)

        languageIdentifier
            .identifyLanguage(sample)
            .addOnSuccessListener { sourceTag ->
                if (sourceTag == "und") {
                    onError(
                        "No pude identificar el idioma de la página."
                    )
                    return@addOnSuccessListener
                }

                val source =
                    TranslateLanguage.fromLanguageTag(
                        sourceTag
                    )

                if (source == null) {
                    onError(
                        "El idioma detectado no puede traducirse sin conexión."
                    )
                    return@addOnSuccessListener
                }

                if (source == target) {
                    onSuccess(texts)
                    return@addOnSuccessListener
                }

                val options =
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()

                val translator =
                    Translation.getClient(options)

                val conditions =
                    DownloadConditions.Builder()
                        .build()

                translator
                    .downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        translateSequentially(
                            translator,
                            texts,
                            0,
                            ArrayList(texts.size),
                            onSuccess = {
                                translator.close()
                                onSuccess(it)
                            },
                            onError = {
                                translator.close()
                                onError(it)
                            }
                        )
                    }
                    .addOnFailureListener { error ->
                        translator.close()
                        onError(
                            "No pude descargar el modelo de idioma: " +
                                (error.message
                                    ?: "error desconocido")
                        )
                    }
            }
            .addOnFailureListener { error ->
                onError(
                    "No pude detectar el idioma: " +
                        (error.message
                            ?: "error desconocido")
                )
            }
    }

    private fun translateSequentially(
        translator: Translator,
        texts: List<String>,
        index: Int,
        output: ArrayList<String>,
        onSuccess: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (index >= texts.size) {
            onSuccess(output)
            return
        }

        translator
            .translate(texts[index])
            .addOnSuccessListener { translated ->
                output.add(translated)

                translateSequentially(
                    translator,
                    texts,
                    index + 1,
                    output,
                    onSuccess,
                    onError
                )
            }
            .addOnFailureListener { error ->
                onError(
                    "Error traduciendo la página: " +
                        (error.message
                            ?: "error desconocido")
                )
            }
    }

    private fun errorResponse(message: String) =
        JSONObject().apply {
            put("ok", false)
            put("error", message)
        }
}
