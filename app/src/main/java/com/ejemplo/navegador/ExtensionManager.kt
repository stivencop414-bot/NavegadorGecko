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

    /*
     * GeckoView entrega acciones de extensiones a través de
     * ActionDelegate. Nexo antes no guardaba esas acciones,
     * por eso muchas extensiones podían instalarse pero no
     * tenían ninguna forma de ejecutarse desde la interfaz.
     */
    private val defaultActions =
        mutableMapOf<
            String,
            WebExtension.Action
            >()

    private val sessionActions =
        java.util.WeakHashMap<
            GeckoSession,
            MutableMap<
                String,
                WebExtension.Action
                >
            >()

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val controller = GeckoRuntimeHolder.get(context).webExtensionController
        controller.setPromptDelegate(promptDelegate)
    controller.setAddonManagerDelegate(addonManagerDelegate)

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

    fun setTabActive(
        session: GeckoSession,
        active: Boolean
    ) {
        runCatching {
            GeckoRuntimeHolder
                .get(AppContext.get())
                .webExtensionController
                .setTabActive(
                    session,
                    active
                )
        }.onFailure {
            error ->

            android.util.Log.w(
                "NexoExtensions",
                "No se pudo notificar pestaña activa",
                error
            )
        }
    }

    fun runAction(
        extension: WebExtension,
        onDone:
            (Boolean, String) -> Unit
    ) {
        val session =
            TabManager.activeSession()

        val sessionAction =
            session
                ?.let {
                    sessionActions[it]
                        ?.get(
                            extension.id
                        )
                }

        val defaultAction =
            defaultActions[
                extension.id
            ]

        val action =
            when {
                sessionAction != null &&
                    defaultAction != null ->
                    runCatching {
                        sessionAction
                            .withDefault(
                                defaultAction
                            )
                    }.getOrDefault(
                        sessionAction
                    )

                sessionAction != null ->
                    sessionAction

                else ->
                    defaultAction
            }

        if (action == null) {
            onDone(
                false,
                "La extensión no expone una acción para esta pestaña."
            )
            return
        }

        if (action.enabled == false) {
            onDone(
                false,
                "La acción de la extensión está deshabilitada en esta página."
            )
            return
        }

        runCatching {
            action.click()
        }.fold(
            onSuccess = {
                onDone(
                    true,
                    action.title
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "Acción de extensión ejecutada"
                )
            },
            onFailure = {
                error ->

                onDone(
                    false,
                    "No se pudo ejecutar: " +
                        (
                            error.message
                                ?: "error desconocido"
                            )
                )
            }
        )
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

    fun isRemoteExtensionPackage(
        url: String
    ): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false

        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()

        return path.endsWith(".xpi") ||
            (host.endsWith("addons.mozilla.org") && path.contains("/downloads/"))
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
                "El paquete no tiene una firma válida de Mozilla. Si lo obtuviste desde Mozilla Add-ons, usa “Agregar a Firefox” dentro de Nexo para conservar la descarga oficial. Las carpetas o ZIP de código fuente no se pueden instalar como extensión normal hasta estar firmados."
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
        return "No se pudo instalar [código ${install.code}]: $detail"
    }


    data class LocalExtensionCandidate(
        val label: String,
        val document:
            androidx.documentfile.provider.DocumentFile,
        val isFolder: Boolean
    )

    fun scanExtensionFolder(
        context: Context,
        treeUri: Uri,
        onDone:
            (Result<List<LocalExtensionCandidate>>) -> Unit
    ) {
        Thread {
            val result = runCatching {
                val root =
                    androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(
                            context,
                            treeUri
                        )
                        ?: error(
                            "Android no permitió abrir esta carpeta."
                        )

                val candidates =
                    mutableListOf<
                        LocalExtensionCandidate
                        >()

                fun walk(
                    directory:
                        androidx.documentfile.provider.DocumentFile,
                    depth: Int
                ) {
                    if (
                        depth > 6 ||
                        candidates.size >= 100
                    ) {
                        return
                    }

                    val children =
                        runCatching {
                            directory.listFiles()
                        }.getOrDefault(
                            emptyArray()
                        )

                    val hasManifest =
                        children.any {
                            it.isFile &&
                                it.name.equals(
                                    "manifest.json",
                                    ignoreCase = true
                                )
                        }

                                        /*
             * Una carpeta con manifest.json es código fuente,
             * no un paquete instalable normal en GeckoView
             * release. Seguimos recorriéndola para encontrar
             * XPI/ZIP firmados en subcarpetas, pero no la
             * mostramos como candidato instalable.
             */
            if (hasManifest) {
                android.util.Log.d(
                    "NexoExtensions",
                    "Carpeta de código fuente omitida: " +
                        (directory.name ?: "Extensión")
                )
            }


                    children.forEach {
                        child ->

                        if (
                            candidates.size >= 100
                        ) {
                            return@forEach
                        }

                        if (child.isDirectory) {
                            walk(
                                child,
                                depth + 1
                            )
                        } else if (child.isFile) {
                            val name =
                                child.name
                                    ?.lowercase()
                                    .orEmpty()

                            if (
                                name.endsWith(".xpi") ||
                                name.endsWith(".zip")
                            ) {
                                candidates +=
                                    LocalExtensionCandidate(
                                        label =
                                            if (
                                                name.endsWith(
                                                    ".xpi"
                                                )
                                            ) {
                                                "XPI · " +
                                                    (
                                                        child.name
                                                            ?: "extensión.xpi"
                                                        )
                                            } else {
                                                "ZIP · " +
                                                    (
                                                        child.name
                                                            ?: "extensión.zip"
                                                        )
                                            },
                                        document =
                                            child,
                                        isFolder =
                                            false
                                    )
                            }
                        }
                    }
                }

                walk(
                    root,
                    0
                )

                candidates
                    .distinctBy {
                        it.document.uri
                            .toString()
                    }
                    .take(100)
            }

            Handler(
                Looper.getMainLooper()
            ).post {
                onDone(result)
            }
        }.start()
    }

    fun importCandidate(
        context: Context,
        candidate:
            LocalExtensionCandidate,
        onDone:
            (Boolean, String) -> Unit
    ) {
        if (candidate.isFolder) {
            onDone(
                false,
                "Esta carpeta contiene código fuente. GeckoView solo instala extensiones normales con firma válida de Mozilla. Busca un XPI firmado dentro de la carpeta o instálalo directamente desde Mozilla Add-ons."
            )
        } else {
            importXpi(
                context,
                candidate.document.uri,
                onDone
            )
        }
    }

    fun importXpi(
        context: Context,
        source: Uri,
        onDone:
            (Boolean, String) -> Unit
    ) {
        Thread {
            val prepared =
                runCatching {
                                        val cache =
                File(
                    context.filesDir,
                    "imported_extensions"
                )


                    cache.mkdirs()

                    val target =
                        File(
                            cache,
                            "import-" +
                                System.currentTimeMillis() +
                                ".xpi"
                        )

                    copyUriWithLimit(
                        context,
                        source,
                        target
                    )

                    normalizeArchive(
                        target
                    )
                }

            Handler(
                Looper.getMainLooper()
            ).post {
                prepared.onSuccess {
                    target ->

                    installInternal(
                        context,
                        Uri.fromFile(
                            target
                        ).toString(),
                        WebExtensionController
                            .INSTALLATION_METHOD_FROM_FILE,
                                                onDone

                    )
                }.onFailure {
                    error ->

                    onDone(
                        false,
                        "Importación fallida: " +
                            (
                                error.message
                                    ?: "paquete inválido"
                                )
                    )
                }
            }
        }.start()
    }

    private fun importFolder(
        context: Context,
        folder:
            androidx.documentfile.provider.DocumentFile,
        onDone:
            (Boolean, String) -> Unit
    ) {
        Thread {
            val prepared =
                runCatching {
                    val cache =
                        File(
                            context.cacheDir,
                            "extensions"
                        )

                    cache.mkdirs()

                    val target =
                        File(
                            cache,
                            "folder-" +
                                System.currentTimeMillis() +
                                ".xpi"
                        )

                    packageDocumentFolder(
                        context,
                        folder,
                        target
                    )

                    validateArchive(
                        target
                    )

                    target
                }

            Handler(
                Looper.getMainLooper()
            ).post {
                prepared.onSuccess {
                    target ->

                    installInternal(
                        context,
                        Uri.fromFile(
                            target
                        ).toString(),
                        WebExtensionController
                            .INSTALLATION_METHOD_FROM_FILE,
                        onDone,
                        cleanup = {
                            runCatching {
                                target.delete()
                            }
                        }
                    )
                }.onFailure {
                    error ->

                    onDone(
                        false,
                        "No se pudo preparar la carpeta: " +
                            (
                                error.message
                                    ?: "estructura inválida"
                                )
                    )
                }
            }
        }.start()
    }

    private fun copyUriWithLimit(
        context: Context,
        source: Uri,
        target: File
    ) {
        var total = 0L

        context.contentResolver
            .openInputStream(source)
            .use {
                input ->

                requireNotNull(input) {
                    "No se pudo abrir el archivo."
                }

                FileOutputStream(
                    target
                ).use {
                    output ->

                    val buffer =
                        ByteArray(
                            128 * 1024
                        )

                    while (true) {
                        val count =
                            input.read(buffer)

                        if (count < 0) {
                            break
                        }

                        total += count

                        if (
                            total >
                            120L *
                            1024L *
                            1024L
                        ) {
                            error(
                                "El archivo supera el límite de 120 MB."
                            )
                        }

                        output.write(
                            buffer,
                            0,
                            count
                        )
                    }
                }
            }

        if (total <= 0L) {
            error(
                "El archivo está vacío."
            )
        }
    }

    private fun normalizeArchive(
        input: File
    ): File {
        val rootManifest =
            java.util.zip.ZipFile(input).use { zip ->
                zip.getEntry("manifest.json") != null
            }

        if (!rootManifest) {
            error(
                "Este ZIP parece ser código fuente o contiene una carpeta raíz. Nexo no lo reempaqueta porque eso no crea una firma válida de Mozilla. Selecciona el XPI firmado oficial o instálalo desde Mozilla Add-ons."
            )
        }

        validateArchive(input)
        return input
    }

    private fun packageDocumentFolder(
        context: Context,
        root:
            androidx.documentfile.provider.DocumentFile,
        target: File
    ) {
        var files = 0
        var bytes = 0L

        java.util.zip.ZipOutputStream(
            FileOutputStream(
                target
            )
        ).use {
            output ->

            fun addDirectory(
                directory:
                    androidx.documentfile.provider.DocumentFile,
                prefix: String,
                depth: Int
            ) {
                if (depth > 15) {
                    error(
                        "La carpeta tiene demasiados niveles."
                    )
                }

                val children =
                    directory.listFiles()

                children.forEach {
                    child ->

                    val name =
                        child.name
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: return@forEach

                    val entryName =
                        if (
                            prefix.isBlank()
                        ) {
                            name
                        } else {
                            "$prefix/$name"
                        }

                    if (
                        child.isDirectory
                    ) {
                        addDirectory(
                            child,
                            entryName,
                            depth + 1
                        )
                    } else if (
                        child.isFile
                    ) {
                        files += 1

                        if (
                            files > 4000
                        ) {
                            error(
                                "La extensión contiene demasiados archivos."
                            )
                        }

                        output.putNextEntry(
                            java.util.zip.ZipEntry(
                                entryName
                            )
                        )

                        context
                            .contentResolver
                            .openInputStream(
                                child.uri
                            )
                            .use {
                                input ->

                                requireNotNull(
                                    input
                                ) {
                                    "No se pudo leer $name"
                                }

                                val buffer =
                                    ByteArray(
                                        128 * 1024
                                    )

                                while (true) {
                                    val count =
                                        input.read(
                                            buffer
                                        )

                                    if (
                                        count < 0
                                    ) {
                                        break
                                    }

                                    bytes += count

                                    if (
                                        bytes >
                                        120L *
                                        1024L *
                                        1024L
                                    ) {
                                        error(
                                            "La carpeta supera el límite de 120 MB."
                                        )
                                    }

                                    output.write(
                                        buffer,
                                        0,
                                        count
                                    )
                                }
                            }

                        output.closeEntry()
                    }
                }
            }

            addDirectory(
                root,
                "",
                0
            )
        }
    }

    private fun validateArchive(
        target: File
    ) {
        if (
            target.length() <= 0L
        ) {
            error(
                "El paquete está vacío."
            )
        }

        java.util.zip.ZipFile(
            target
        ).use {
            zip ->

            val manifest =
                zip.getEntry(
                    "manifest.json"
                )
                    ?: error(
                        "No existe manifest.json en la raíz."
                    )

            zip.getInputStream(
                manifest
            )
                .bufferedReader()
                .use {
                    reader ->

                    val json =
                        JSONObject(
                            reader.readText()
                        )

                    val name =
                        json.optString(
                            "name"
                        )

                    val version =
                        json.optString(
                            "version"
                        )

                    if (
                        name.isBlank() ||
                        version.isBlank()
                    ) {
                        error(
                            "manifest.json no contiene nombre y versión."
                        )
                    }
                }
        }
    }

    fun updateExtension(
        context: Context,
        extension: WebExtension,
        onDone:
            (Boolean, String) -> Unit
    ) {
        GeckoRuntimeHolder
            .get(context)
            .webExtensionController
            .update(extension)
            .accept(
                {
                    updated ->

                    if (updated == null) {
                        onDone(
                            true,
                            "Ya tienes la versión más reciente de " +
                                (
                                    extension
                                        .metaData
                                        .name
                                        ?: extension.id
                                    )
                        )
                    } else {
                        registerExtension(
                            updated
                        )

                        onDone(
                            true,
                            "Actualizada: " +
                                (
                                    updated
                                        .metaData
                                        .name
                                        ?: updated.id
                                    )
                        )
                    }
                },
                {
                    error ->

                    onDone(
                        false,
                        "No se pudo buscar una actualización: " +
                            (
                                error?.message
                                    ?: "error desconocido"
                                )
                    )
                }
            )
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

    private fun registerExtension(
        extension: WebExtension
    ) {
        if (extension.id == BRIDGE_ID) {
            configureBridge(
                extension
            )
            return
        }

        if (
            extension.id ==
            TranslatorManager
                .TRANSLATOR_ID
        ) {
            return
        }

        extension.setActionDelegate(
            extensionActionDelegate
        )

        extension.setTabDelegate(
            object :
                WebExtension.TabDelegate {

                override fun onNewTab(
                    source: WebExtension,
                    createDetails:
                        WebExtension
                            .CreateTabDetails
                ): GeckoResult<GeckoSession>? {
                    val session =
                        TabManager
                            .createSessionForExtension(
                                createDetails.url
                                    ?: "about:blank",
                                createDetails.active !=
                                    false
                            )

                    return GeckoResult
                        .fromValue(
                            session
                        )
                }

                override fun onOpenOptionsPage(
                    source: WebExtension
                ) {
                    val url =
                        source
                            .metaData
                            ?.optionsPageUrl

                    if (
                        !url.isNullOrBlank()
                    ) {
                        TabManager.createTab(
                            url = url,
                            activate = true
                        )
                    }
                }
            }
        )

        TabManager.liveSessions()
            .forEach {
                bindExtensionToSession(
                    extension,
                    it
                )
            }
    }

    private fun bindExtensionToSession(
        extension: WebExtension,
        session: GeckoSession
    ) {
        /*
         * Recibir browser_action/page_action específicos
         * de esta pestaña.
         */
        session.webExtensionController
            .setActionDelegate(
                extension,
                extensionActionDelegate
            )

        session.webExtensionController
            .setTabDelegate(
                extension,
                object :
                    WebExtension
                        .SessionTabDelegate {

                    override fun onCloseTab(
                        source: WebExtension?,
                        session: GeckoSession
                    ): GeckoResult<AllowOrDeny> {
                        TabManager
                            .closeBySession(
                                session
                            )

                        return GeckoResult.allow()
                    }

                    override fun onUpdateTab(
                        extension: WebExtension,
                        session: GeckoSession,
                        details:
                            WebExtension
                                .UpdateTabDetails
                    ): GeckoResult<AllowOrDeny> {
                        if (
                            details.active == true
                        ) {
                            TabManager
                                .activateBySession(
                                    session
                                )
                        }

                        return GeckoResult.allow()
                    }
                }
            )
    }

    private fun rememberAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action
    ) {
        if (session == null) {
            defaultActions[
                extension.id
            ] = action
        } else {
            sessionActions
                .getOrPut(
                    session
                ) {
                    mutableMapOf()
                }[
                    extension.id
                ] = action
        }
    }

    private fun createExtensionPopupSession():
        GeckoResult<GeckoSession> {
        val session =
            TabManager
                .createSessionForExtension(
                    "about:blank",
                    true
                )

        return GeckoResult.fromValue(
            session
        )
    }

    private val extensionActionDelegate =
        object :
            WebExtension.ActionDelegate {

            override fun onBrowserAction(
                extension: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action
            ) {
                rememberAction(
                    extension,
                    session,
                    action
                )
            }

            override fun onPageAction(
                extension: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action
            ) {
                rememberAction(
                    extension,
                    session,
                    action
                )
            }

            override fun onOpenPopup(
                extension: WebExtension,
                action: WebExtension.Action
            ): GeckoResult<GeckoSession>? =
                createExtensionPopupSession()

            override fun onTogglePopup(
                extension: WebExtension,
                action: WebExtension.Action
            ): GeckoResult<GeckoSession>? =
                createExtensionPopupSession()
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
                                        json.optBoolean("present", false),
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

    /*
     * Esperar también onReady es importante para extensiones
     * importadas desde archivo: en ese punto Gecko terminó
     * de iniciar el add-on y ya puede resolver popup/opciones.
     */
    private val addonManagerDelegate =
        object :
            WebExtensionController.AddonManagerDelegate {

            override fun onInstalled(
                extension: WebExtension
            ) {
                registerExtension(extension)
            }

            override fun onReady(
                extension: WebExtension
            ) {
                registerExtension(extension)
            }

            override fun onEnabled(
                extension: WebExtension
            ) {
                registerExtension(extension)
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
            showPermissionPrompt(
                extension = extension,
                title = "Actualizar extensión",
                permissions = newPermissions,
                origins = newOrigins,
                dataCollectionPermissions =
                    newDataCollectionPermissions
            )

        override fun onOptionalPrompt(
            extension: WebExtension,
            permissions: Array<out String>,
            origins: Array<out String>,
            dataCollectionPermissions:
                Array<out String>
        ): GeckoResult<AllowOrDeny>? =
            showPermissionPrompt(
                extension = extension,
                title = "Permisos adicionales",
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions =
                    dataCollectionPermissions
            )

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
        title: String,
        permissions:
            Array<out String> =
            emptyArray<String>(),
        origins:
            Array<out String> =
            emptyArray<String>(),
        dataCollectionPermissions:
            Array<out String> =
            emptyArray<String>()
    ): GeckoResult<AllowOrDeny> {
        val result =
            GeckoResult<AllowOrDeny>()

        val activity =
            promptActivity.get()

        if (
            activity == null ||
            activity.isFinishing
        ) {
            result.complete(
                AllowOrDeny.DENY
            )
            return result
        }

        val details =
            buildString {
                append(
                    extension
                        .metaData
                        ?.name
                        ?: extension.id
                )

                append(
                    "\nVersión: "
                )

                append(
                    extension
                        .metaData
                        ?.version
                        ?: "?"
                )

                if (
                    permissions
                        .isNotEmpty()
                ) {
                    append(
                        "\n\nPermisos: "
                    )

                    append(
                        permissions
                            .joinToString(
                                ", "
                            )
                    )
                }

                if (
                    origins
                        .isNotEmpty()
                ) {
                    append(
                        "\n\nSitios: "
                    )

                    append(
                        origins
                            .joinToString(
                                ", "
                            )
                    )
                }

                if (
                    dataCollectionPermissions
                        .isNotEmpty()
                ) {
                    append(
                        "\n\nDatos solicitados: "
                    )

                    append(
                        dataCollectionPermissions
                            .joinToString(
                                ", "
                            )
                    )
                }

                append(
                    "\n\nPermite únicamente si confías en la extensión."
                )
            }

        activity.runOnUiThread {
            AlertDialog.Builder(
                activity
            )
                .setTitle(title)
                .setMessage(details)
                .setNegativeButton(
                    "Cancelar"
                ) {
                        _,
                        _ ->

                    result.complete(
                        AllowOrDeny.DENY
                    )
                }
                .setPositiveButton(
                    "Permitir"
                ) {
                        _,
                        _ ->

                    result.complete(
                        AllowOrDeny.ALLOW
                    )
                }
                .setOnCancelListener {
                    result.complete(
                        AllowOrDeny.DENY
                    )
                }
                .show()
        }

        return result
    }

}
