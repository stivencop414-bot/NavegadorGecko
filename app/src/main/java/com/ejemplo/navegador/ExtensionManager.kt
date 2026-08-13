package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.zip.ZipFile

object ExtensionManager {
    const val BRIDGE_ID = "nexo_bridge@com.ejemplo.navegador"
    private const val BRIDGE_URI =
        "resource://android/assets/extensions/nexo_bridge/"
    private const val NATIVE_APP = "browser"

    private var promptActivity = WeakReference<Activity>(null)
    private var bridge: WebExtension? = null
    private val ports = mutableSetOf<WebExtension.Port>()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val controller = GeckoRuntimeHolder.get(context).webExtensionController
        controller.setPromptDelegate(promptDelegate)

        controller.ensureBuiltIn(BRIDGE_URI, BRIDGE_ID).accept(
            { extension ->
                if (extension != null) {
                    bridge = extension
                    configureBridge(extension)

                    // ensureBuiltIn es asíncrono: si las pestañas ya existen,
                    // enlaza el puente también a esas sesiones.
                    TabManager.liveSessions().forEach { session ->
                        bindBridgeToSession(extension, session)
                    }
                }
            },
            { error ->
                android.util.Log.e("NexoExtensions", "Puente interno", error)
            }
        )

        controller.list().accept(
            { extensions ->
                extensions.orEmpty()
                    .filter {
                        it.id != BRIDGE_ID &&
                            it.id != TranslatorManager.TRANSLATOR_ID
                    }
                    .forEach { registerExtension(it) }
            },
            { error ->
                android.util.Log.e("NexoExtensions", "Lista inicial", error)
            }
        )
    }

    fun attachPromptActivity(activity: Activity?) {
        promptActivity = WeakReference(activity)
    }

    fun bindSession(session: GeckoSession) {
        bridge?.let {
            bindBridgeToSession(it, session)
        }

        GeckoRuntimeHolder.get(AppContext.get())
            .webExtensionController
            .list()
            .accept(
                { extensions ->
                    extensions.orEmpty()
                        .filter {
                            it.id != BRIDGE_ID &&
                                it.id != TranslatorManager.TRANSLATOR_ID
                        }
                        .forEach { bindExtensionToSession(it, session) }
                },
                { error ->
                    android.util.Log.e(
                        "NexoExtensions",
                        "No se pudieron enlazar extensiones",
                        error
                    )
                }
            )
    }

    fun list(
        onSuccess: (List<WebExtension>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        GeckoRuntimeHolder.get(AppContext.get())
            .webExtensionController
            .list()
            .accept(
                {
                    onSuccess(
                        it.orEmpty().filter { extension ->
                            extension.id != BRIDGE_ID &&
                                extension.id != TranslatorManager.TRANSLATOR_ID
                        }
                    )
                },
                { onError(it ?: IllegalStateException("GeckoView devolvió un error nulo")) }
            )
    }

    fun installUrl(
        context: Context,
        url: String,
        onDone: (Boolean, String) -> Unit
    ) {
        installInternal(
            context,
            url,
            WebExtensionController.INSTALLATION_METHOD_MANAGER,
            onDone
        )
    }

    private fun installInternal(
        context: Context,
        url: String,
        installationMethod: String,
        onDone: (Boolean, String) -> Unit,
        cleanup: (() -> Unit)? = null
    ) {
        GeckoRuntimeHolder.get(context)
            .webExtensionController
            .install(url, installationMethod)
            .accept(
                { extension ->
                    cleanup?.invoke()
                    if (extension == null) {
                        onDone(false, "GeckoView no devolvió la extensión.")
                    } else {
                        registerExtension(extension)
                        onDone(
                            true,
                            "Instalada: ${extension.metaData?.name ?: extension.id}"
                        )
                    }
                },
                { error ->
                    cleanup?.invoke()
                    onDone(false, installErrorMessage(error))
                }
            )
    }

    private fun installErrorMessage(error: Throwable?): String {
        val install = error as? WebExtension.InstallException
            ?: return "No se pudo instalar: ${error?.message ?: "error desconocido"}"

        val detail = when (install.code) {
            WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED ->
                "El XPI no está firmado por Mozilla."
            WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE ->
                "El archivo XPI está corrupto o no es un paquete válido."
            WebExtension.InstallException.ErrorCodes.ERROR_FILE_ACCESS ->
                "Nexo no pudo acceder al archivo seleccionado."
            WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE ->
                "La extensión no es compatible con esta versión de GeckoView."
            WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE ->
                "Falló la descarga por un problema de red."
            WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED ->
                "Mozilla bloqueó esta extensión por seguridad."
            WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED ->
                "Mozilla marcó esta extensión como potencialmente problemática."
            WebExtension.InstallException.ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE ->
                "Este tipo de complemento no es compatible."
            WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED ->
                "Instalación cancelada."
            else ->
                install.message ?: "Error de instalación (${install.code})."
        }
        return "No se pudo instalar: $detail"
    }

    fun importXpi(
        context: Context,
        source: Uri,
        onDone: (Boolean, String) -> Unit
    ) {
        Thread {
            val prepared = runCatching {
                val folder = File(context.cacheDir, "extensions")
                folder.mkdirs()

                val target = File(
                    folder,
                    "import-${System.currentTimeMillis()}.xpi"
                )

                context.contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "No se pudo abrir el archivo seleccionado." }
                    FileOutputStream(target).use { out ->
                        input.copyTo(out, 64 * 1024)
                    }
                }

                if (target.length() <= 0L) {
                    error("El archivo está vacío.")
                }

                ZipFile(target).use { zip ->
                    val manifest = zip.getEntry("manifest.json")
                        ?: error("El XPI no contiene manifest.json en la raíz.")

                    zip.getInputStream(manifest).bufferedReader().use { reader ->
                        val json = JSONObject(reader.readText())
                        val name = json.optString("name")
                        val version = json.optString("version")

                        if (name.isBlank() || version.isBlank()) {
                            error("El manifest.json no contiene nombre o versión.")
                        }
                    }
                }

                target
            }

            Handler(Looper.getMainLooper()).post {
                prepared.onSuccess { target ->
                    installInternal(
                        context,
                        Uri.fromFile(target).toString(),
                        WebExtensionController.INSTALLATION_METHOD_FROM_FILE,
                        onDone,
                        cleanup = { runCatching { target.delete() } }
                    )
                }.onFailure {
                    onDone(
                        false,
                        "Importación fallida: ${it.message ?: "archivo XPI inválido"}"
                    )
                }
            }
        }.start()
    }

    fun setEnabled(
        context: Context,
        extension: WebExtension,
        enabled: Boolean,
        onDone: (Boolean, String) -> Unit
    ) {
        val controller = GeckoRuntimeHolder.get(context).webExtensionController

        val result = if (enabled) {
            controller.enable(
                extension,
                WebExtensionController.EnableSource.USER
            )
        } else {
            controller.disable(
                extension,
                WebExtensionController.EnableSource.USER
            )
        }

        result.accept(
            { updated ->
                updated?.let { registerExtension(it) }
                onDone(
                    true,
                    if (enabled) "Extensión habilitada" else "Extensión deshabilitada"
                )
            },
            { error ->
                onDone(false, error?.message ?: "No se pudo cambiar el estado")
            }
        )
    }

    fun uninstall(
        context: Context,
        extension: WebExtension,
        onDone: (Boolean, String) -> Unit
    ) {
        if (extension.id == BRIDGE_ID) {
            onDone(false, "El puente interno de Nexo no se puede eliminar.")
            return
        }

        GeckoRuntimeHolder.get(context)
            .webExtensionController
            .uninstall(extension)
            .accept(
                { onDone(true, "Extensión desinstalada") },
                { onDone(false, it?.message ?: "No se pudo desinstalar") }
            )
    }

    fun setPrivateAllowed(
        context: Context,
        extension: WebExtension,
        allowed: Boolean,
        onDone: (Boolean, String) -> Unit
    ) {
        GeckoRuntimeHolder.get(context)
            .webExtensionController
            .setAllowedInPrivateBrowsing(extension, allowed)
            .accept(
                {
                    onDone(
                        true,
                        if (allowed) "Permitida en privado" else "Bloqueada en privado"
                    )
                },
                { onDone(false, it?.message ?: "No se pudo cambiar el permiso") }
            )
    }

    fun setPipMode(
        session: GeckoSession?,
        active: Boolean
    ) {
        if (session == null) return

        val message = JSONObject().apply {
            put("type", "pip_mode")
            put("active", active)
        }

        ports.toList()
            .filter {
                it.sender.session === session &&
                    it.sender.isTopLevel()
            }
            .forEach { port ->
                runCatching {
                    port.postMessage(message)
                }
            }
    }

    fun sendBrowserState(context: Context) {
        val message = JSONObject().apply {
            put("type", "browser_state")
            put("accent", BrowserPrefs.accent(context))
            put("theme", BrowserPrefs.theme(context))
            put("showBadge", false)
            put("backgroundMedia", BrowserPrefs.backgroundMedia(context))
            put("smartPip", BrowserPrefs.smartPip(context))
        }

        ports.toList().forEach { port ->
            runCatching { port.postMessage(message) }
        }
    }

    private fun configureBridge(extension: WebExtension) {
        extension.setMessageDelegate(
            bridgeMessageDelegate,
            NATIVE_APP
        )
    }

    private fun bindBridgeToSession(
        extension: WebExtension,
        session: GeckoSession
    ) {
        session.webExtensionController.setMessageDelegate(
            extension,
            bridgeMessageDelegate,
            NATIVE_APP
        )
    }

    private fun registerExtension(extension: WebExtension) {
        if (extension.id == BRIDGE_ID) {
            configureBridge(extension)
            return
        }

        if (extension.id == TranslatorManager.TRANSLATOR_ID) {
            return
        }

        extension.setTabDelegate(
            object : WebExtension.TabDelegate {
                override fun onNewTab(
                    source: WebExtension,
                    createDetails: WebExtension.CreateTabDetails
                ): GeckoResult<GeckoSession>? {
                    val session = TabManager.createSessionForExtension(
                        createDetails.url ?: "about:blank",
                        createDetails.active != false
                    )
                    return GeckoResult.fromValue(session)
                }
            }
        )

        TabManager.liveSessions().forEach {
            bindExtensionToSession(extension, it)
        }
    }

    private fun bindExtensionToSession(
        extension: WebExtension,
        session: GeckoSession
    ) {
        session.webExtensionController.setTabDelegate(
            extension,
            object : WebExtension.SessionTabDelegate {
                override fun onCloseTab(
                    source: WebExtension?,
                    session: GeckoSession
                ): GeckoResult<AllowOrDeny> {
                    TabManager.closeBySession(session)
                    return GeckoResult.allow()
                }

                override fun onUpdateTab(
                    extension: WebExtension,
                    session: GeckoSession,
                    details: WebExtension.UpdateTabDetails
                ): GeckoResult<AllowOrDeny> {
                    if (details.active == true) {
                        TabManager.activateBySession(session)
                    }
                    return GeckoResult.allow()
                }
            }
        )
    }

    private val bridgeMessageDelegate =
        object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? {
                if (nativeApp != NATIVE_APP) return null

                val context = AppContext.get()
                val tab = sender.session?.let { TabManager.tabForSession(it) }

                return GeckoResult.fromValue(
                    JSONObject().apply {
                        put("ok", true)
                        put("showBadge", false)
                        put("backgroundMedia", BrowserPrefs.backgroundMedia(context))
                        put("smartPip", BrowserPrefs.smartPip(context))
                        put("theme", BrowserPrefs.theme(context))
                        put("accent", BrowserPrefs.accent(context))
                        put("tabId", tab?.id ?: "")
                        put("private", tab?.isPrivate ?: false)
                        put("url", tab?.url ?: "")
                    }
                )
            }

            override fun onConnect(port: WebExtension.Port) {
                ports += port

                port.setDelegate(
                    object : WebExtension.PortDelegate {
                        override fun onPortMessage(
                            message: Any,
                            port: WebExtension.Port
                        ) {
                            val json = message as? JSONObject
                            if (json?.optString("type") == "video_state" && port.sender.isTopLevel()) {
                                val tab = port.sender.session?.let { TabManager.tabForSession(it) }
                                if (tab != null) {
                                    BrowserMediaController.onVideoState(
                                        tab.id,
                                        json.optBoolean("playing", false),
                                        json.optInt("width", 0),
                                        json.optInt("height", 0)
                                    )
                                }
                                return
                            }
                            port.postMessage(
                                JSONObject().apply {
                                    put("type", "native_ack")
                                    put("received", message.toString())
                                    put("showBadge", false)
                                    put("backgroundMedia", BrowserPrefs.backgroundMedia(AppContext.get()))
                                }
                            )
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            ports -= port
                        }
                    }
                )

                sendBrowserState(AppContext.get())
            }
        }

    /* GeckoView 153 PromptDelegate */
    private val promptDelegate =
        object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? =
                showInstallPermissionPrompt(
                    extension,
                    permissions,
                    origins,
                    dataCollectionPermissions
                )

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>,
                newDataCollectionPermissions: Array<out String>
            ): GeckoResult<AllowOrDeny>? =
                showPermissionPrompt(extension, "Actualizar extensión")
        }

    private fun showInstallPermissionPrompt(
        extension: WebExtension,
        permissions: Array<out String>,
        origins: Array<out String>,
        dataCollectionPermissions: Array<out String>
    ): GeckoResult<WebExtension.PermissionPromptResponse> {
        val result = GeckoResult<WebExtension.PermissionPromptResponse>()
        val activity = promptActivity.get()

        fun response(allowed: Boolean) =
            WebExtension.PermissionPromptResponse(
                allowed,
                false,
                allowed && dataCollectionPermissions.isNotEmpty()
            )

        if (activity == null || activity.isFinishing) {
            result.complete(response(false))
            return result
        }

        val details = buildString {
            append(extension.metaData?.name ?: extension.id)
            append("\nVersión: ")
            append(extension.metaData?.version ?: "?")

            if (permissions.isNotEmpty()) {
                append("\n\nPermisos: ")
                append(permissions.joinToString(", "))
            }

            if (origins.isNotEmpty()) {
                append("\n\nSitios: ")
                append(origins.joinToString(", "))
            }

            if (dataCollectionPermissions.isNotEmpty()) {
                append("\n\nDatos solicitados: ")
                append(dataCollectionPermissions.joinToString(", "))
            }
        }

        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Instalar extensión")
                .setMessage(details)
                .setNegativeButton("Cancelar") { _, _ ->
                    result.complete(response(false))
                }
                .setPositiveButton("Permitir") { _, _ ->
                    result.complete(response(true))
                }
                .setOnCancelListener {
                    result.complete(response(false))
                }
                .show()
        }

        return result
    }

    private fun showPermissionPrompt(
        extension: WebExtension,
        title: String
    ): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        val activity = promptActivity.get()

        if (activity == null || activity.isFinishing) {
            result.complete(AllowOrDeny.DENY)
            return result
        }

        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(
                    "${extension.metaData?.name ?: extension.id}\n" +
                        "Versión: ${extension.metaData?.version ?: "?"}\n\n" +
                        "Instala únicamente extensiones de fuentes confiables."
                )
                .setNegativeButton("Cancelar") { _, _ ->
                    result.complete(AllowOrDeny.DENY)
                }
                .setPositiveButton("Permitir") { _, _ ->
                    result.complete(AllowOrDeny.ALLOW)
                }
                .setOnCancelListener {
                    result.complete(AllowOrDeny.DENY)
                }
                .show()
        }

        return result
    }
}
