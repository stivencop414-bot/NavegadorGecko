package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.WebExtension

/**
 * Extensiones instaladas y todas las formas de agregar nuevas: tienda,
 * archivo XPI, carpeta, enlace (directo o ficha de addons.mozilla.org) y
 * paquetes descargados con Nexo. Los paquetes locales se analizan antes de
 * instalarlos para mostrar permisos y avisar si no están firmados.
 */
class ExtensionsActivity : Activity() {
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var downloadsSection: LinearLayout
    private lateinit var downloadsRow: LinearLayout

    private var extensions: List<WebExtension> = emptyList()
    private var visible: List<WebExtension> = emptyList()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extensions)

        listView = findViewById(R.id.extensionsList)
        progress = findViewById(R.id.extensionsProgress)

        listView.addHeaderView(buildHeader(), null, false)
        listView.setOnItemClickListener { _, _, position, _ ->
            visible.getOrNull(position - listView.headerViewsCount)?.let { showDetails(it) }
        }

        findViewById<Button>(R.id.updateAllButton).setOnClickListener { updateAll() }
        applyTheme()
        render()
    }

    override fun onResume() {
        super.onResume()
        ExtensionManager.attachPromptActivity(this)
        load()
        refreshDownloads()
    }

    override fun onDestroy() {
        ExtensionManager.detachPromptActivity(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Encabezado: formas de agregar, descargas y búsqueda
    // ---------------------------------------------------------------

    private fun buildHeader(): View {
        val p = ThemeManager.palette(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(10f))
        }

        header.addView(sectionLabel("Agregar extensiones"))
        val tiles = listOf(
            Triple(R.drawable.ic_menu_store, "Tienda") {
                startActivity(Intent(this, ExtensionStoreActivity::class.java))
            },
            Triple(R.drawable.ic_menu_file, "Archivo XPI") { openFilePicker() },
            Triple(R.drawable.ic_menu_folder, "Carpeta") { openFolderPicker() },
            Triple(R.drawable.ic_menu_link, "Enlace") { showUrlInstaller() }
        )
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = tiles.size.toFloat()
        }
        tiles.forEachIndexed { index, (icon, label, action) ->
            grid.addView(
                tile(icon, label, primary = index == 0, onClick = action),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8f)
                }
            )
        }
        header.addView(grid)

        // Paquetes .xpi descargados (se muestran solo si hay alguno).
        downloadsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downloadsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(sectionLabel("Desde tus descargas"))
            addView(HorizontalScrollView(this@ExtensionsActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(downloadsRow)
            })
        }
        header.addView(downloadsSection)

        search = EditText(this).apply {
            hint = "Buscar entre mis extensiones"
            setSingleLine(true)
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(18f), 0, dp(18f), 0)
            ThemeManager.styleEdit(this@ExtensionsActivity, this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = render()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        header.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)).apply {
            topMargin = dp(18f)
        })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(p.muted)
            setPadding(dp(4f), dp(12f), dp(4f), 0)
        }
        header.addView(status)
        return header
    }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            ThemeManager.styleSectionLabel(this@ExtensionsActivity, this)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4f), dp(14f), dp(4f), dp(10f))
        }

    private fun tile(icon: Int, label: String, primary: Boolean, onClick: () -> Unit): View {
        val p = ThemeManager.palette(this)
        val bg = if (primary) p.accent else p.surface
        val fg = if (primary) p.onAccent else p.text
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4f), dp(14f), dp(4f), dp(12f))
            background = ThemeManager.ripple(
                this@ExtensionsActivity,
                ThemeManager.rounded(this@ExtensionsActivity, bg, 20f, if (!primary && p.isDark) p.outline else null),
                20f
            )
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(ImageView(this@ExtensionsActivity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(if (primary) p.onAccent else p.accent)
                layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f))
            })
            addView(TextView(this@ExtensionsActivity).apply {
                text = label
                textSize = 12.5f
                setTextColor(fg)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6f), 0, 0)
            })
        }
    }

    private fun refreshDownloads() {
        val packages = ExtensionManager.downloadedPackages(this)
        downloadsRow.removeAllViews()
        downloadsSection.visibility = if (packages.isEmpty()) View.GONE else View.VISIBLE
        val p = ThemeManager.palette(this)
        packages.take(10).forEach { entry ->
            downloadsRow.addView(TextView(this).apply {
                text = entry.fileName
                textSize = 13f
                setTextColor(p.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                maxWidth = dp(220f)
                compoundDrawablePadding = dp(8f)
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    getDrawable(R.drawable.ic_extension_placeholder)?.mutate()?.apply { setTint(p.accent) },
                    null, null, null
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), 0, dp(16f), 0)
                background = ThemeManager.ripple(
                    this@ExtensionsActivity,
                    ThemeManager.rounded(this@ExtensionsActivity, p.surface, 20f, p.outline),
                    20f
                )
                isClickable = true
                setOnClickListener {
                    DownloadStore.fileFor(this@ExtensionsActivity, entry)?.let { inspect(Uri.fromFile(it)) }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)).apply {
                marginEnd = dp(8f)
            })
        }
    }

    // ---------------------------------------------------------------
    // Lista
    // ---------------------------------------------------------------

    private fun load() {
        setLoading(true)
        ExtensionManager.list(
            onSuccess = { items ->
                extensions = items.sortedBy { (it.metaData.name ?: it.id).lowercase() }
                runOnUiThread {
                    setLoading(false)
                    render()
                }
            },
            onError = { error ->
                runOnUiThread {
                    setLoading(false)
                    status.text = "No se pudieron cargar las extensiones"
                    toast("No se pudieron listar: ${error.message ?: "error desconocido"}")
                }
            }
        )
    }

    private fun render() {
        val term = search.text.toString().trim().lowercase()
        visible = if (term.isBlank()) extensions else extensions.filter { ext ->
            listOfNotNull(ext.metaData.name, ext.metaData.description, ext.metaData.creatorName, ext.id)
                .any { it.lowercase().contains(term) }
        }

        val active = extensions.count { it.metaData.enabled }
        status.text = when {
            term.isNotBlank() -> "${visible.size} de ${extensions.size} extensiones"
            extensions.isEmpty() -> getString(R.string.empty_extensions)
            extensions.size == 1 -> "1 extensión instalada · ${if (active == 1) "activa" else "desactivada"}"
            else -> "${extensions.size} extensiones instaladas · $active activas"
        }

        listView.adapter = InstalledExtensionAdapter(this, visible) { ext, enabled ->
            if (ext.metaData.enabled != enabled) setEnabled(ext, enabled)
        }
    }

    // ---------------------------------------------------------------
    // Detalle de una extensión
    // ---------------------------------------------------------------

    private fun showDetails(extension: WebExtension) {
        val meta = extension.metaData
        val p = ThemeManager.palette(this)
        var sheet: Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), 0, dp(24f), dp(8f))
        }
        meta.description?.takeIf { it.isNotBlank() }?.let {
            info.addView(TextView(this).apply {
                text = it
                textSize = 14f
                setTextColor(p.text)
                setLineSpacing(0f, 1.15f)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        val permissions = describePermissions(
            meta.requiredPermissions.toList() + meta.grantedOptionalPermissions.toList(),
            meta.requiredOrigins.toList() + meta.grantedOptionalOrigins.toList()
        )
        if (permissions.isNotEmpty()) {
            info.addView(TextView(this).apply {
                text = "Puede: " + permissions.joinToString(" · ")
                textSize = 13f
                setTextColor(p.muted)
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(10f), 0, 0)
            })
        }

        val actions = mutableListOf<SheetAction>()
        if (meta.enabled) {
            actions += SheetAction(R.drawable.ic_menu_play, "Abrir sobre la página actual") {
                // El panel se abre sobre la pestaña, no sobre esta pantalla.
                ExtensionManager.requestActionInBrowser(extension)
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
        meta.optionsPageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            actions += SheetAction(R.drawable.ic_menu_settings, "Opciones de la extensión") { openInBrowser(url) }
        }
        actions += SheetAction(
            R.drawable.ic_menu_private,
            if (meta.allowedInPrivateBrowsing) "Bloquear en pestañas privadas" else "Permitir en pestañas privadas"
        ) { setPrivate(extension, !meta.allowedInPrivateBrowsing) }
        if (!extension.isBuiltIn) {
            actions += SheetAction(R.drawable.ic_menu_update, "Buscar actualización") { updateOne(extension) }
        }
        (meta.amoListingUrl ?: meta.homepageUrl)?.takeIf { it.isNotBlank() }?.let { url ->
            actions += SheetAction(R.drawable.ic_menu_open, "Ver página de la extensión") { openInBrowser(url) }
        }
        if (!extension.isBuiltIn) {
            actions += SheetAction(R.drawable.ic_menu_close, "Desinstalar", danger = true) { confirmUninstall(extension) }
        } else {
            info.addView(TextView(this).apply {
                setText(R.string.bundled_extension_updates)
                setTextColor(p.muted)
                textSize = 13f
                setPadding(0, dp(10f), 0, 0)
            })
        }

        sheet = NexoSheets.show(
            this,
            NexoSheets.column(
                this,
                NexoSheets.header(
                    this,
                    meta.name ?: extension.id,
                    listOfNotNull(
                        meta.version?.let { "Versión $it" },
                        meta.creatorName,
                        if (meta.enabled) "Activa" else "Desactivada"
                    ).joinToString(" · ")
                ),
                info,
                NexoSheets.list(this, actions, dismiss)
            )
        )
    }

    // ---------------------------------------------------------------
    // Instalación local con vista previa
    // ---------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_FILE -> inspect(uri)
            REQUEST_FOLDER -> {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                scanFolder(uri)
            }
        }
    }

    private fun openFilePicker() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/x-xpinstall",
                        "application/zip",
                        "application/octet-stream",
                        "application/x-zip-compressed"
                    )
                )
            },
            REQUEST_FILE
        )
    }

    private fun openFolderPicker() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            REQUEST_FOLDER
        )
    }

    private fun scanFolder(uri: Uri) {
        setLoading(true)
        status.text = "Buscando paquetes de extensión en la carpeta…"
        ExtensionManager.scanExtensionFolder(this, uri) { result ->
            runOnUiThread {
                setLoading(false)
                render()
                result.onSuccess { candidates ->
                    when (candidates.size) {
                        0 -> toast("No hay archivos XPI/ZIP en esta carpeta.")
                        1 -> inspect(candidates.first().document.uri)
                        else -> {
                            var sheet: Dialog? = null
                            sheet = NexoSheets.show(
                                this,
                                NexoSheets.column(
                                    this,
                                    NexoSheets.header(this, "Paquetes encontrados", "${candidates.size} archivos"),
                                    NexoSheets.list(
                                        this,
                                        candidates.map { candidate ->
                                            SheetAction(R.drawable.ic_extension_placeholder, candidate.label) {
                                                inspect(candidate.document.uri)
                                            }
                                        }
                                    ) { sheet?.dismiss() }
                                )
                            )
                        }
                    }
                }.onFailure { toast(it.message ?: "No se pudo leer la carpeta") }
            }
        }
    }

    /** Analiza el paquete y muestra la vista previa antes de instalar. */
    private fun inspect(uri: Uri) {
        setLoading(true)
        status.text = "Analizando paquete…"
        ExtensionManager.inspectPackage(this, uri) { result ->
            setLoading(false)
            render()
            result
                .onSuccess { showPreview(it) }
                .onFailure { toast("No se pudo leer el paquete: ${it.message ?: "formato no válido"}") }
        }
    }

    private fun showPreview(preview: ExtensionManager.PackagePreview) {
        val p = ThemeManager.palette(this)
        var installed = false
        var sheet: Dialog? = null

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), 0, dp(24f), dp(8f))
        }

        // Estado de firma: GeckoView solo instala paquetes firmados por Mozilla.
        body.addView(TextView(this).apply {
            text = if (preview.signed) "✓  Firmado por Mozilla" else "✕  Sin firma de Mozilla"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val color = if (preview.signed) p.accent else p.danger
            setTextColor(color)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = ThemeManager.rounded(
                this@ExtensionsActivity,
                if (preview.signed) p.accentSoft else (p.danger and 0x00FFFFFF or 0x22000000),
                12f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        if (preview.description.isNotBlank()) {
            body.addView(TextView(this).apply {
                text = preview.description
                textSize = 14f
                setTextColor(p.text)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(12f), 0, 0)
            })
        }

        val permissions = describePermissions(preview.permissions, preview.hosts)
        body.addView(TextView(this).apply {
            text = buildString {
                append(if (permissions.isEmpty()) "No pide permisos especiales." else "Podrá: " + permissions.joinToString(" · "))
                append("\n")
                append(Formatter.formatShortFileSize(this@ExtensionsActivity, preview.sizeBytes))
                if (preview.geckoId.isNotBlank()) append(" · ").append(preview.geckoId)
            }
            textSize = 13f
            setTextColor(p.muted)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(12f), 0, 0)
        })

        if (!preview.signed) {
            body.addView(TextView(this).apply {
                text = "GeckoView solo instala extensiones firmadas por Mozilla. Descarga la versión firmada " +
                    "desde addons.mozilla.org (botón “Tienda” o “Enlace”), o intégrala en la app como " +
                    "extensión interna si es tuya."
                textSize = 13f
                setTextColor(p.text)
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(12f), 0, 0)
            })
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16f), dp(16f), dp(16f), 0)
        }
        buttons.addView(Button(this).apply {
            text = "Cancelar"
            ThemeManager.styleButton(this@ExtensionsActivity, this)
            setOnClickListener { sheet?.dismiss() }
        }, LinearLayout.LayoutParams(0, dp(50f), 1f))
        buttons.addView(Button(this).apply {
            text = if (preview.signed) "Instalar" else "No se puede instalar"
            isEnabled = preview.signed
            ThemeManager.styleButton(this@ExtensionsActivity, this, primary = preview.signed)
            setOnClickListener {
                installed = true
                sheet?.dismiss()
                setLoading(true)
                status.text = "Instalando ${preview.name}…"
                ExtensionManager.installPreview(this@ExtensionsActivity, preview) { ok, message ->
                    runOnUiThread {
                        setLoading(false)
                        toast(message)
                        if (ok) load() else render()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, dp(50f), 1f).apply { marginStart = dp(10f) })

        sheet = NexoSheets.show(
            this,
            NexoSheets.column(
                this,
                NexoSheets.header(
                    this,
                    preview.name,
                    listOf(
                        preview.version.takeIf { it.isNotBlank() }?.let { "Versión $it" },
                        preview.author.takeIf { it.isNotBlank() }
                    ).filterNotNull().joinToString(" · ")
                ),
                body,
                buttons
            )
        )
        sheet.setOnDismissListener { if (!installed) ExtensionManager.discardPreview(preview) }
    }

    /** Traduce permisos del manifest a frases comprensibles. */
    private fun describePermissions(permissions: List<String>, hosts: List<String>): List<String> {
        val names = mapOf(
            "tabs" to "ver tus pestañas",
            "activeTab" to "acceder a la pestaña actual",
            "storage" to "guardar datos",
            "unlimitedStorage" to "guardar datos sin límite",
            "cookies" to "leer y cambiar cookies",
            "webRequest" to "ver el tráfico de red",
            "webRequestBlocking" to "bloquear peticiones",
            "declarativeNetRequest" to "bloquear contenido",
            "downloads" to "gestionar descargas",
            "notifications" to "mostrar notificaciones",
            "clipboardRead" to "leer el portapapeles",
            "clipboardWrite" to "escribir en el portapapeles",
            "history" to "acceder al historial",
            "bookmarks" to "acceder a marcadores",
            "scripting" to "ejecutar scripts en páginas",
            "privacy" to "cambiar ajustes de privacidad",
            "proxy" to "controlar el proxy",
            "nativeMessaging" to "comunicarse con otras apps",
            "menus" to "añadir menús",
            "contextMenus" to "añadir menús",
            "alarms" to "programar tareas"
        )
        val result = permissions.mapNotNull { names[it] }.toMutableList()
        val allSites = hosts.any { it == "<all_urls>" || it.startsWith("*://*/") || it == "http://*/*" || it == "https://*/*" }
        when {
            allSites -> result.add(0, "leer y cambiar datos en todos los sitios")
            hosts.isNotEmpty() -> result.add(0, "acceder a ${hosts.size} sitio${if (hosts.size == 1) "" else "s"}")
        }
        return result.distinct()
    }

    // ---------------------------------------------------------------
    // Instalación por enlace
    // ---------------------------------------------------------------

    private fun showUrlInstaller() {
        val p = ThemeManager.palette(this)
        var sheet: Dialog? = null
        val input = EditText(this).apply {
            hint = "https://addons.mozilla.org/…/addon/…"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(18f), 0, dp(18f), 0)
            ThemeManager.styleEdit(this@ExtensionsActivity, this)
        }
        val install = Button(this).apply {
            text = "Instalar"
            ThemeManager.styleButton(this@ExtensionsActivity, this, primary = true)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), 0, dp(24f), 0)
            addView(TextView(this@ExtensionsActivity).apply {
                text = "Pega el enlace de la ficha en addons.mozilla.org o un enlace HTTPS directo a un XPI firmado."
                textSize = 13.5f
                setTextColor(p.muted)
                setPadding(0, 0, 0, dp(12f))
            })
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50f)))
            addView(install, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50f)).apply {
                topMargin = dp(12f)
            })
        }
        install.setOnClickListener {
            val url = input.text.toString().trim()
            if (!url.startsWith("https://")) {
                toast("Usa una dirección HTTPS.")
                return@setOnClickListener
            }
            sheet?.dismiss()
            setLoading(true)
            status.text = "Buscando la extensión…"
            ExtensionManager.resolveAmoListing(url) { resolved ->
                val target = resolved ?: url
                ExtensionManager.installUrl(this, target) { ok, message ->
                    runOnUiThread {
                        setLoading(false)
                        toast(message)
                        if (ok) load() else render()
                    }
                }
            }
        }
        sheet = NexoSheets.show(
            this,
            NexoSheets.column(this, NexoSheets.header(this, "Instalar desde un enlace"), body)
        )
        input.requestFocus()
    }

    // ---------------------------------------------------------------
    // Acciones
    // ---------------------------------------------------------------

    private fun setEnabled(extension: WebExtension, enabled: Boolean) {
        ExtensionManager.setEnabled(this, extension, enabled) { ok, message ->
            toast(message)
            if (ok) load() else render()
        }
    }

    private fun setPrivate(extension: WebExtension, allowed: Boolean) {
        ExtensionManager.setPrivateAllowed(this, extension, allowed) { ok, message ->
            toast(message)
            if (ok) load()
        }
    }

    private fun updateOne(extension: WebExtension) {
        setLoading(true)
        ExtensionManager.updateExtension(this, extension) { _, message ->
            runOnUiThread {
                setLoading(false)
                toast(message)
                load()
            }
        }
    }

    private fun updateAll() {
        if (extensions.isEmpty()) {
            toast("No hay extensiones para actualizar.")
            return
        }
        setLoading(true)
        val pending = extensions.filterNot { it.isBuiltIn }
        if (pending.isEmpty()) {
            setLoading(false)
            toast(getString(R.string.bundled_extension_updates))
            return
        }
        fun next(index: Int) {
            if (index >= pending.size) {
                runOnUiThread {
                    setLoading(false)
                    toast("Revisión de actualizaciones completada.")
                    load()
                }
                return
            }
            runOnUiThread { status.text = "Revisando ${index + 1} de ${pending.size}…" }
            ExtensionManager.updateExtension(this, pending[index]) { _, _ -> next(index + 1) }
        }
        next(0)
    }

    private fun confirmUninstall(extension: WebExtension) {
        AlertDialog.Builder(this)
            .setTitle("¿Desinstalar ${extension.metaData.name ?: "la extensión"}?")
            .setMessage("Se eliminarán también sus datos guardados.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Desinstalar") { _, _ ->
                ExtensionManager.uninstall(this, extension) { ok, message ->
                    toast(message)
                    if (ok) load()
                }
            }
            .show()
    }

    private fun openInBrowser(url: String) {
        startActivity(MainActivity.openUrlIntent(this, url))
    }

    private fun setLoading(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.extensionsRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.extensionsTitle))
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
        ThemeManager.styleButton(this, findViewById(R.id.updateAllButton))
        progress.indeterminateTintList = ColorStateList.valueOf(p.accent)
        ThemeManager.takeInitialFocus(findViewById(R.id.extensionsRoot))
    }

    companion object {
        private const val REQUEST_FILE = 41
        private const val REQUEST_FOLDER = 42
    }
}
