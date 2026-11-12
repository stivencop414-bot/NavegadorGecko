package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
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
    const val PHANTOMSHIELD_ID = "phantomshield@geckoview.internal"
    private const val PHANTOMSHIELD_URI = "resource://android/assets/extensions/phantomshield/"
    private var phantomInstallation: GeckoResult<WebExtension>? = null

    const val BRIDGE_ID = "nexo_bridge@com.ejemplo.navegador"
    private const val BRIDGE_URI =
        "resource://android/assets/extensions/nexo_bridge/"
    private const val NATIVE_APP = "browser"

    private const val IMPORT_DIR = "imported_extensions"
    private const val MAX_PACKAGE_BYTES = 120L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 250L * 1024L * 1024L
    private const val MAX_ENTRIES = 4000
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L

    private var promptActivity = WeakReference<Activity>(null)
    private var extensionPopup: Dialog? = null
    private var bridge: WebExtension? = null
    private val ports = mutableSetOf<WebExtension.Port>()
    private data class PipState(val resume: Boolean, val isolate: Boolean = true)
    private val pipStates = java.util.WeakHashMap<GeckoSession, PipState>()
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

        // ensureBuiltIn conserva el almacenamiento y la elección de activación al reiniciar.
        phantomInstallation = controller.ensureBuiltIn(PHANTOMSHIELD_URI, PHANTOMSHIELD_ID)
        phantomInstallation?.accept(
            { extension -> extension?.let { registerExtension(it) } },
            { error -> android.util.Log.e("NexoExtensions", "No se pudo cargar PhantomShield integrado", error) }
        )

        controller.ensureBuiltIn(BRIDGE_URI, BRIDGE_ID).accept(
            { extension ->
                if (extension != null) {
                    bridge = extension
                    configureBridge(extension)
                    // Mini video y subtítulos también en pestañas privadas y Burner. El puente
                    // no guarda nada ni envía URL o contenido de la página.
                    if (!extension.metaData.allowedInPrivateBrowsing) {
                        runCatching {
                            controller.setAllowedInPrivateBrowsing(extension, true).accept(
                                { allowed -> if (allowed != null) bridge = allowed },
                                { error -> android.util.Log.w("NexoExtensions", "Puente en privado", error) }
                            )
                        }
                    }

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
                        it.id != BRIDGE_ID && it.id != TabRouting.ID &&
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
        if (activity == null) extensionPopup?.dismiss()
        promptActivity = WeakReference(activity)
    }

    /**
     * Desvincula [activity] solo si sigue siendo la actual. Al volver de
     * Extensiones, su onDestroy llega DESPUÉS del onResume de la pantalla
     * anterior: limpiar sin comprobar dejaba a Nexo sin pantalla para los
     * paneles y avisos de instalación (se cancelaban solos).
     */
    fun detachPromptActivity(activity: Activity) {
        if (promptActivity.get() !== activity) return
        extensionPopup?.let { popup ->
            if (popup.ownerActivity === activity || popup.ownerActivity == null) popup.dismiss()
        }
        promptActivity = WeakReference(null)
    }

    /** Extensiones conocidas (instaladas/integradas), para no listar en cada pestaña. */
    private val known = linkedMapOf<String, WebExtension>()

    /** Acción pedida desde otra pantalla: se ejecuta al volver al navegador. */
    private var pendingActionId: String? = null

    fun requestActionInBrowser(extension: WebExtension) {
        pendingActionId = extension.id
    }

    /** Llamar en MainActivity.onResume: ejecuta la acción pendiente sobre la pestaña. */
    fun consumePendingAction(onDone: (Boolean, String) -> Unit) {
        val id = pendingActionId ?: return
        pendingActionId = null
        known[id]?.let { runAction(it, onDone) }
    }

    /** Estado real de la extensión incluida, también cuando está desactivada. */
    fun installedPhantom(): WebExtension? = known[PHANTOMSHIELD_ID]

    /** Standard browser-action badge; no native messaging or page data is exposed. */
    fun phantomBadge(global: Boolean = false): String? {
        if (known[PHANTOMSHIELD_ID]?.metaData?.enabled == false) return "OFF"
        val raw = if (global) defaultActions[PHANTOMSHIELD_ID]?.badgeText
        else TabManager.activeSession()?.let { sessionActions[it]?.get(PHANTOMSHIELD_ID)?.badgeText }
            ?: defaultActions[PHANTOMSHIELD_ID]?.badgeText?.takeIf { it == "OFF" }
        return raw?.takeIf { it == "OFF" || it == "PAUSE" || it.matches(Regex("""[0-9]{1,3}\+?""")) }
    }

    /** Extensiones activas que tienen botón (browser_action/page_action). */
    fun actionableExtensions(): List<WebExtension> {
        val session = TabManager.activeSession()
        return known.values.filter { ext ->
            ext.metaData.enabled &&
                (defaultActions.containsKey(ext.id) || session?.let { sessionActions[it]?.containsKey(ext.id) } == true)
        }
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

        // Las extensiones que se registren después se enlazan en
        // registerExtension(); aquí basta con las ya conocidas.
        known.values.forEach { bindExtensionToSession(it, session) }
    }

    fun list(
        onSuccess: (List<WebExtension>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (!initialized) initialize(AppContext.get())
        phantomInstallation?.accept(
            { listInstalled(onSuccess, onError) },
            { onError(IllegalStateException("No se pudo cargar PhantomShield integrado", it)) }
        ) ?: listInstalled(onSuccess, onError)
    }

    private fun listInstalled(
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
                            extension.id != BRIDGE_ID && extension.id != TabRouting.ID &&
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

        val trustedAmoHost =
            host == "addons.mozilla.org" ||
                host.endsWith(".addons.mozilla.org")

        // No auto-instalar XPI desde hosts arbitrarios. GeckoView valida
        // la firma, pero el origen también debe ser el AMO oficial.
        return trustedAmoHost &&
            (path.endsWith(".xpi") || path.contains("/downloads/"))
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
                     * no un paquete instalable en GeckoView release.
                     * Seguimos recorriéndola para encontrar XPI/ZIP
                     * firmados en subcarpetas, pero no la mostramos
                     * como candidato instalable.
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

    /** Datos leídos de un XPI local antes de instalarlo. */
    data class PackagePreview(
        val file: File,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val geckoId: String,
        val permissions: List<String>,
        val hosts: List<String>,
        val signed: Boolean,
        val sizeBytes: Long
    )

    /**
     * Copia y analiza un paquete local SIN instalarlo: nombre, versión,
     * permisos y si está firmado por Mozilla (GeckoView rechaza los no
     * firmados, así que se avisa antes de intentarlo).
     */
    fun inspectPackage(
        context: Context,
        source: Uri,
        onDone: (Result<PackagePreview>) -> Unit
    ) {
        val app = context.applicationContext
        Thread {
            val cache = File(app.filesDir, IMPORT_DIR)
            purgeStaleImports(cache)
            val target = File(cache, "import-${System.currentTimeMillis()}.xpi")

            val result = runCatching {
                cache.mkdirs()
                copyUriWithLimit(app, source, target)
                normalizeArchive(target)
                readPreview(target)
            }.onFailure { runCatching { target.delete() } }

            Handler(Looper.getMainLooper()).post { onDone(result) }
        }.start()
    }

    /** Instala un paquete ya analizado con [inspectPackage]. */
    fun installPreview(
        context: Context,
        preview: PackagePreview,
        onDone: (Boolean, String) -> Unit
    ) {
        installInternal(
            context.applicationContext,
            Uri.fromFile(preview.file).toString(),
            WebExtensionController.INSTALLATION_METHOD_FROM_FILE,
            onDone,
            cleanup = { runCatching { preview.file.delete() } }
        )
    }

    fun discardPreview(preview: PackagePreview) {
        runCatching { preview.file.delete() }
    }

    private fun readPreview(file: File): PackagePreview =
        ZipFile(file).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                .bufferedReader().use { JSONObject(it.readText()) }

            // Nombres traducidos: "__MSG_extName__" → _locales/<idioma>/messages.json
            val locale = manifest.optString("default_locale")
            val messages = listOf(java.util.Locale.getDefault().language, locale, "en")
                .filter { it.isNotBlank() }
                .firstNotNullOfOrNull { lang ->
                    zip.getEntry("_locales/$lang/messages.json")?.let { entry ->
                        runCatching {
                            JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                        }.getOrNull()
                    }
                }

            fun text(key: String): String {
                val raw = manifest.optString(key).trim()
                val msg = Regex("^__MSG_(.+)__$").find(raw)?.groupValues?.get(1) ?: return raw
                return messages?.let { m ->
                    m.keys().asSequence()
                        .firstOrNull { it.equals(msg, ignoreCase = true) }
                        ?.let { m.optJSONObject(it)?.optString("message") }
                }.orEmpty().ifBlank { raw }
            }

            fun strings(key: String): List<String> =
                manifest.optJSONArray(key)?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
                }.orEmpty()

            val allPermissions = strings("permissions") + strings("host_permissions")
            val hosts = allPermissions.filter { it.contains("://") || it == "<all_urls>" }
            val apis = allPermissions.filterNot { it in hosts }

            val gecko = manifest.optJSONObject("browser_specific_settings")?.optJSONObject("gecko")
                ?: manifest.optJSONObject("applications")?.optJSONObject("gecko")

            val signed = zip.getEntry("META-INF/mozilla.rsa") != null ||
                zip.getEntry("META-INF/cose.sig") != null

            PackagePreview(
                file = file,
                name = text("name").ifBlank { file.name },
                version = manifest.optString("version"),
                description = text("description"),
                author = text("author"),
                geckoId = gecko?.optString("id").orEmpty(),
                permissions = apis,
                hosts = hosts,
                signed = signed,
                sizeBytes = file.length()
            )
        }

    /**
     * Convierte la URL de una ficha de addons.mozilla.org
     * (…/addon/ublock-origin/) en la URL de su XPI actual.
     */
    fun resolveAmoListing(url: String, onDone: (String?) -> Unit) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val segments = uri?.pathSegments.orEmpty()
        val slugIndex = segments.indexOf("addon")
        if (host != "addons.mozilla.org" || slugIndex < 0 || slugIndex + 1 >= segments.size) {
            onDone(null)
            return
        }
        val slug = segments[slugIndex + 1]

        Thread {
            val xpi = runCatching {
                val connection = java.net.URL(
                    "https://addons.mozilla.org/api/v5/addons/addon/" +
                        java.net.URLEncoder.encode(slug, "UTF-8") + "/?app=android"
                ).openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 8000
                    connection.readTimeout = 12000
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    json.optJSONObject("current_version")
                        ?.optJSONObject("file")
                        ?.optString("url")
                        ?.takeIf { isRemoteExtensionPackage(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            Handler(Looper.getMainLooper()).post { onDone(xpi) }
        }.start()
    }

    /** Icono real de una extensión instalada (o null si no tiene). */
    fun loadIcon(extension: WebExtension, sizePx: Int, onDone: (android.graphics.Bitmap?) -> Unit) {
        val icon = extension.metaData.icon
        runCatching {
            icon.getBitmap(sizePx).accept({ onDone(it) }, { onDone(null) })
        }.onFailure { onDone(null) }
    }

    /** Paquetes .xpi que el usuario descargó con Nexo. */
    fun downloadedPackages(context: Context): List<DownloadEntry> =
        DownloadStore.list(context).filter {
            it.fileName.endsWith(".xpi", ignoreCase = true) && DownloadStore.fileFor(context, it) != null
        }

    /** Borra importaciones antiguas que hayan quedado de versiones anteriores. */
    private fun purgeStaleImports(folder: File) {
        val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
        folder.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun copyUriWithLimit(
        context: Context,
        source: Uri,
        target: File
    ) {
        var total = 0L

        (
            context.contentResolver.openInputStream(source)
                ?: error("No se pudo abrir el archivo.")
        ).use { stream ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break

                    total += count
                    if (total > MAX_PACKAGE_BYTES) {
                        error("El archivo supera el límite de 120 MB.")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }

        if (total <= 0L) error("El archivo está vacío.")
    }

    private fun normalizeArchive(input: File): File {
        val rootManifest = ZipFile(input).use { zip ->
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

    /*
     * Valida estructura y tamaño REAL del paquete. Los tamaños declarados en
     * el directorio central del ZIP pueden ser falsos (o -1), por eso se
     * descomprime cada entrada contando los bytes leídos.
     */
    private fun validateArchive(target: File) {
        if (target.length() <= 0L) error("El paquete está vacío.")

        ZipFile(target).use { zip ->
            var entries = 0
            var expandedBytes = 0L
            val buffer = ByteArray(64 * 1024)
            val allEntries = zip.entries()

            while (allEntries.hasMoreElements()) {
                val entry = allEntries.nextElement()
                entries += 1

                if (entries > MAX_ENTRIES) {
                    error("La extensión contiene demasiadas entradas.")
                }

                val normalized = entry.name.replace('\\', '/')
                val segments = normalized.split('/')

                if (
                    normalized.startsWith('/') ||
                    segments.any { it == ".." } ||
                    normalized.indexOf('\u0000') >= 0 ||
                    (segments.firstOrNull()?.endsWith(':') == true)
                ) {
                    error("Ruta insegura dentro del paquete: ${entry.name}")
                }

                if (entry.isDirectory) continue

                zip.getInputStream(entry).use { stream ->
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        expandedBytes += count
                        if (expandedBytes > MAX_EXPANDED_BYTES) {
                            error("El contenido expandido del XPI supera 250 MB.")
                        }
                    }
                }
            }

            val manifest = zip.getEntry("manifest.json")
                ?: error("No existe manifest.json en la raíz.")

            if (manifest.size > MAX_MANIFEST_BYTES) {
                error("manifest.json es demasiado grande.")
            }

            val json = zip.getInputStream(manifest)
                .bufferedReader()
                .use { JSONObject(it.readText()) }

            if (json.optString("name").isBlank() || json.optString("version").isBlank()) {
                error("manifest.json no contiene nombre y versión.")
            }
        }
    }

    fun updateExtension(
        context: Context,
        extension: WebExtension,
        onDone:
            (Boolean, String) -> Unit
    ) {
        if (extension.isBuiltIn) {
            onDone(true, "Esta extensión integrada se actualiza junto con Nexo.")
            return
        }
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
        if (extension.isBuiltIn) {
            onDone(false, "Las extensiones integradas se pueden desactivar, pero no eliminar del APK.")
            return
        }

        GeckoRuntimeHolder.get(context)
            .webExtensionController
            .uninstall(extension)
            .accept(
                {
                    forget(extension.id)
                    onDone(true, "Extensión desinstalada")
                },
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

    /**
     * Activa o desactiva el aislamiento del video para el mini reproductor.
     * [resumePlayback] pide a la página reanudar el video si el sitio lo
     * pausa durante la transición a PiP (YouTube lo hace al cambiar el
     * tamaño de la ventana).
     */
    /**
     * [isolateCss] = false cuando Nexo recorta el video de forma nativa: la
     * página no se toca (YouTube reescribe clases y pausa al redimensionar).
     */
    fun setPipMode(
        session: GeckoSession?,
        active: Boolean,
        resumePlayback: Boolean = false,
        isolateCss: Boolean = true
    ) {
        if (session == null) return
        if (active) pipStates.putIfAbsent(session, PipState(resumePlayback, isolateCss))
        else pipStates.remove(session)
        val state = pipStates[session]
        postToTopLevel(
            session,
            pipModeMessage(active, state?.resume == true, state?.isolate ?: isolateCss)
        )
    }

    private fun pipModeMessage(active: Boolean, resume: Boolean, isolate: Boolean = true) =
        JSONObject().apply {
            put("type", "pip_mode")
            put("active", active)
            put("resume", active && resume)
            put("isolate", isolate)
        }

    fun sendPipCommand(session: GeckoSession?, command: String): Boolean {
        if (session == null) return false
        if (pipStates.containsKey(session) && command in listOf("play", "pause")) {
            pipStates[session] = PipState(command == "play", pipStates[session]?.isolate ?: true)
        }
        return postToTopLevel(session, JSONObject().apply {
            put("type", "pip_command")
            put("command", command)
        })
    }

    /**
     * Pide al puente que empiece o deje de leer los subtítulos del video.
     * Devuelve false si la página aún no tiene el puente conectado.
     */
    fun setCaptions(session: GeckoSession?, active: Boolean, live: Boolean = false, language: String = "multi"): Boolean =
        postToTopLevel(session, JSONObject().apply {
            put("type", "captions")
            put("active", active)
            // live: hay clave de Deepgram; sin ella se usan la pista del video o el texto del reproductor.
            put("live", live)
            // Idioma preferido al elegir entre las pistas del reproductor (p. ej. YouTube).
            put("lang", language.takeIf { it.matches(Regex("[a-zA-Z]{2,3}(-[A-Za-z]{2,4})?|multi")) } ?: "multi")
        })

    /** Tras un corte de red: grabación nueva (con cabecera) para la conexión nueva. */
    fun restartCaptions(session: GeckoSession?): Boolean =
        postToTopLevel(session, JSONObject().put("type", "captions_restart"))

    /** Avisa al puente de que la app pasa a segundo plano o vuelve (recuperación de video). */
    fun sendLifecycle(session: GeckoSession?, foreground: Boolean) {
        postToTopLevel(session, JSONObject().apply {
            put("type", "lifecycle")
            put("state", if (foreground) "foreground" else "background")
        })
    }

    private fun captionErrorText(code: String): String = when (code) {
        "no_video" -> "No hay un video en esta página."
        "suspended" -> "Toca el video una vez y vuelve a activar los subtítulos."
        "unsupported" -> "Esta página no permite captar el audio."
        "silent" -> "El video no deja captar su audio (puede estar protegido)."
        "need_key" -> "Hace falta una clave de Deepgram para este video."
        else -> "No se pudo captar el audio del video."
    }

    fun hasMediaBridge(session: GeckoSession?): Boolean =
        session != null && ports.any { it.sender.session === session && it.sender.isTopLevel() }

    private fun postToTopLevel(session: GeckoSession?, message: JSONObject): Boolean {
        if (session == null) return false

        var delivered = false
        ports.toList()
            .filter { it.sender.session === session && it.sender.isTopLevel() }
            .forEach { port ->
                if (runCatching { port.postMessage(message) }.isSuccess) delivered = true
            }
        return delivered
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

    private val tabBudgets = mutableMapOf<String, TabCreationBudget>()

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

        if(extension.id == TabRouting.ID) return
        known[extension.id] = extension

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
                    val url = createDetails.url ?: "about:blank"

                    // Misma política que el resto de la app: una extensión
                    // no puede abrir javascript:, data:, file:, about:config…
                    if (!UrlSafety.isExtensionTabUrl(url)) {
                        android.util.Log.w(
                            "NexoExtensions",
                            "Pestaña bloqueada (esquema no permitido) de ${source.id}"
                        )
                        return null
                    }

                    if (!tabBudgets.getOrPut(source.id) { TabCreationBudget() }.allow(android.os.SystemClock.elapsedRealtime())) {
                        return null
                    }
                    val session =
                        TabManager
                            .createSessionForExtension(
                                url,
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
                        TabManager.openOrSelectTab(url)
                        // Let runtime.openOptionsPage complete before closing its caller's session.
                        Handler(Looper.getMainLooper()).post { extensionPopup?.dismiss() }
                        promptActivity.get()?.let { activity ->
                            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            })
                        }
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

    fun forgetSession(session: GeckoSession) { sessionActions.remove(session) }

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

    /**
     * Panel (popup) de una extensión como hoja inferior.
     *
     * La GeckoView normal es un SurfaceView: se dibuja por encima de la
     * ventana y NO respeta esquinas redondeadas, por eso el panel se veía
     * cuadrado y con esquinas de otro color (el fondo oscuro de la página
     * sobre la tarjeta clara). Con el backend TextureView y un contorno
     * recortado, la página queda dentro de las esquinas de la hoja.
     */
    private fun createExtensionPopupSession(extension: WebExtension?): GeckoResult<GeckoSession>? {
        val activity = promptActivity.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        extensionPopup?.dismiss()

        val p = ThemeManager.palette(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()
        val radius = 24f * density

        // Un popup no es una pestaña: tabs.query(active:true) debe seguir
        // devolviendo la página que la extensión va a controlar.
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(TabManager.activeTab()?.isPrivate == true)
                .build()
        )
        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> =
                if (UrlSafety.isExtensionTabUrl(request.uri)) GeckoResult.allow() else GeckoResult.deny()

            // Enlaces del panel con target=_blank: abrir como pestaña normal.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (UrlSafety.isExtensionTabUrl(uri) && !uri.equals("about:blank", true)) {
                    extensionPopup?.dismiss()
                    TabManager.createTab(uri)
                }
                return null
            }
        })
        session.setPromptDelegate(PagePrompts)
        session.open(GeckoRuntimeHolder.get(activity))
        runCatching { session.compositorController.setClearColor(p.surface) }

        val view = GeckoView(activity).apply {
            setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            setSession(session)
        }

        // Se cierra arrastrando hacia abajo desde la parte superior (asa y encabezado);
        // el resto de gestos son de la página del panel.
        val sheet = DragDismissLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            dragZonePx = dp(if (extension?.id == PHANTOMSHIELD_ID) 30f else 58f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.surface)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            // Contorno con solo las esquinas superiores redondeadas: el
            // rectángulo se extiende bajo el borde inferior, así el recorte
            // funciona en todas las versiones de Android.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(target: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, target.width, target.height + radius.toInt(), radius)
                }
            }
            clipToOutline = true
        }

        val dialog = Dialog(activity)
        if (extension?.id != PHANTOMSHIELD_ID) {
            // PhantomShield supplies its own handle, title and close button.
            sheet.addView(android.view.View(activity).apply {
                background = ThemeManager.rounded(activity, p.outline, 3f)
            }, android.widget.LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10f)
            })
            val header = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20f), dp(2f), dp(8f), dp(2f))
                addView(android.widget.TextView(activity).apply {
                    text = extension?.metaData?.name ?: "Extensión"
                    textSize = 15f
                    setTextColor(p.text)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(android.widget.ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_menu_close)
                    contentDescription = "Cerrar"
                    ThemeManager.styleIconButton(activity, this)
                    imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
                    setOnClickListener { dialog.dismiss() }
                }, android.widget.LinearLayout.LayoutParams(dp(44f), dp(44f)))
            }
            sheet.addView(header)
        }
        sheet.addView(view, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        sheet.dimWindow = dialog.window
        sheet.onDismiss = { if (dialog.isShowing) runCatching { dialog.dismiss() } }
        dialog.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setTitle(extension?.metaData?.name ?: "Extensión")
            setCanceledOnTouchOutside(true)
            setContentView(sheet)
            setOnDismissListener {
                view.releaseSession()
                session.close()
                if (extensionPopup === this) extensionPopup = null
            }
        }
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) { dialog.dismiss() }
        })
        extensionPopup = dialog
        dialog.show()
        dialog.window?.apply {
            val metrics = activity.resources.displayMetrics
            val height = minOf((metrics.heightPixels * 0.85f).toInt(), dp(720f))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            @Suppress("DEPRECATION")
            navigationBarColor = p.surface
        }
        return GeckoResult.fromValue(session)
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
                createExtensionPopupSession(extension)

            override fun onTogglePopup(
                extension: WebExtension,
                action: WebExtension.Action
            ): GeckoResult<GeckoSession>? {
                // Tocar otra vez el botón cierra el panel abierto.
                extensionPopup?.let {
                    it.dismiss()
                    return null
                }
                return createExtensionPopupSession(extension)
            }
        }

    /*
     * El delegate solo se registra para el bridge, pero se verifica igualmente
     * la extensión remitente: si otra extensión llegara a enlazarse por error,
     * no obtendría acceso al puente.
     */
    private fun isBridgeSender(sender: WebExtension.MessageSender): Boolean =
        sender.webExtension.id == BRIDGE_ID &&
            sender.isTopLevel() &&
            sender.session != null

    private val bridgeMessageDelegate =
        object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender
            ): GeckoResult<Any>? {
                if (nativeApp != NATIVE_APP || !isBridgeSender(sender)) {
                    return null
                }

                val context = AppContext.get()

                // Superficie mínima: el bridge solo necesita estado de
                // reproducción/PiP. No exponer URL, id de pestaña ni modo privado.
                return GeckoResult.fromValue(
                    JSONObject().apply {
                        put("ok", true)
                        put("backgroundMedia", BrowserPrefs.backgroundMedia(context))
                        put("smartPip", BrowserPrefs.smartPip(context))
                    }
                )
            }

            override fun onConnect(port: WebExtension.Port) {
                if (!isBridgeSender(port.sender)) {
                    runCatching { port.disconnect() }
                    return
                }

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
                                        json.optInt("height", 0),
                                        android.graphics.Rect(
                                            json.optInt("rx", 0),
                                            json.optInt("ry", 0),
                                            json.optInt("rx", 0) + json.optInt("rw", 0),
                                            json.optInt("ry", 0) + json.optInt("rh", 0)
                                        )
                                    )
                                }
                                return
                            }
                            val session = port.sender.session
                            if (json != null && session != null && port.sender.isTopLevel()) {
                                when (json.optString("type")) {
                                    "captions_start" -> LiveCaptions.onRecordingStarted(AppContext.get(), session)
                                    "captions_audio" -> LiveCaptions.onAudio(session, json.optString("data"))
                                    "captions_stop" -> LiveCaptions.onRecordingStopped(session)
                                    "captions_error" -> json.optString("code").let { code ->
                                        LiveCaptions.onPageError(AppContext.get(), session, code, captionErrorText(code))
                                    }
                                    "captions_track" -> LiveCaptions.onTrack(session, json.optString("source", "track"))
                                    "captions_text" -> LiveCaptions.onDomText(AppContext.get(), session, json.optString("text").take(400))
                                    "captions_cues" -> json.optJSONArray("cues")?.let { LiveCaptions.onCues(AppContext.get(), session, it) }
                                    "captions_active" -> json.optJSONArray("ids")?.let { LiveCaptions.onActiveCues(AppContext.get(), session, it) }
                                }
                            }
                            // Los mensajes no reconocidos no se reflejan de vuelta
                            // al contenido. Esto evita convertir el bridge en un eco de datos.
                            return
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            ports -= port
                        }
                    }
                )

                sendBrowserState(AppContext.get())
                port.sender.session?.let { session ->
                    // La orden de subtítulos pudo llegar antes de que la página conectara.
                    LiveCaptions.onBridgeConnected(session)
                    pipStates[session]?.let { state ->
                        runCatching {
                            port.postMessage(pipModeMessage(true, state.resume, state.isolate))
                            if (!state.resume) port.postMessage(JSONObject().apply {
                                put("type", "pip_command")
                                put("command", "pause")
                            })
                        }
                    }
                }
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

            override fun onDisabled(extension: WebExtension) {
                if(extension.id == TabRouting.ID) return
                known[extension.id] = extension
            }

            override fun onUninstalled(extension: WebExtension) {
                forget(extension.id)
            }
        }

    private fun forget(id: String) {
        known.remove(id)
        defaultActions.remove(id)
        sessionActions.values.forEach { it.remove(id) }
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

    /** Nombres legibles de los permisos de datos de las extensiones. */
    private fun dataPermissionLabel(permission: String): String =
        when (permission) {
            "websiteContent" -> "contenido de las páginas"
            "browsingActivity" -> "sitios que visitas"
            "authenticationInfo" -> "credenciales (por ejemplo, tu clave de API)"
            "personallyIdentifyingInfo" -> "datos que te identifican"
            "locationInfo" -> "ubicación"
            "searchTerms" -> "búsquedas"
            "websiteActivity" -> "actividad dentro de las páginas"
            "bookmarksInfo" -> "marcadores"
            "healthInfo" -> "datos de salud"
            "financialAndPaymentInfo" -> "datos financieros y de pago"
            "personalCommunications" -> "comunicaciones personales"
            "technicalAndInteraction" -> "datos técnicos y de uso"
            else -> permission
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
                        "\n\nDatos que podrá enviar: "
                    )

                    append(
                        dataCollectionPermissions
                            .joinToString(
                                ", "
                            ) { dataPermissionLabel(it) }
                    )

                    // PhantomShield integrado: este aviso llega al guardar la
                    // configuración de IA; explicar a quién van esos datos.
                    if (extension.id == PHANTOMSHIELD_ID) {
                        append(
                            "\n\nSolo se envían al proveedor de IA que configuraste, " +
                                "en los sitios y con el nivel de detalle que aceptaste. " +
                                "Si cancelas, la IA no se activará; el bloqueo local sigue igual."
                        )
                    }
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
