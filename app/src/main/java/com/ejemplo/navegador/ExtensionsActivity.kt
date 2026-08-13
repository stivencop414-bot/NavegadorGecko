package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.WebExtension

class ExtensionsActivity :
    Activity() {

    private lateinit var listView:
        ListView

    private lateinit var progress:
        ProgressBar

    private lateinit var status:
        TextView

    private lateinit var search:
        EditText

    private var extensions:
        List<WebExtension> =
        emptyList()

    private var visibleExtensions:
        List<WebExtension> =
        emptyList()

    private val fileRequestCode =
        701

    private val folderRequestCode =
        702

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        ThemeManager.applyWindow(this)

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_extensions
        )

        listView =
            findViewById(
                R.id.extensionsList
            )

        progress =
            findViewById(
                R.id.extensionsProgress
            )

        status =
            findViewById(
                R.id.extensionsStatus
            )

        search =
            findViewById(
                R.id.extensionsSearch
            )

        findViewById<Button>(
            R.id.storeButton
        ).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    ExtensionStoreActivity::class.java
                )
            )
        }

        findViewById<Button>(
            R.id.importButton
        ).setOnClickListener {
            openFilePicker()
        }

        findViewById<Button>(
            R.id.folderButton
        ).setOnClickListener {
            openFolderPicker()
        }

        findViewById<Button>(
            R.id.urlInstallButton
        ).setOnClickListener {
            showUrlInstaller()
        }

        findViewById<Button>(
            R.id.updateAllButton
        ).setOnClickListener {
            updateAll()
        }

        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    filter()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        listView
            .setOnItemClickListener {
                    _,
                    view,
                    position,
                    _ ->

                showExtensionMenu(
                    view,
                    visibleExtensions[
                        position
                    ]
                )
            }

        applyTheme()
        load()
    }

    override fun onResume() {
        super.onResume()

        ExtensionManager
            .attachPromptActivity(
                this
            )

        load()
    }

    override fun onDestroy() {
        ExtensionManager
            .attachPromptActivity(
                null
            )

        super.onDestroy()
    }

    @Deprecated(
        "Deprecated in Java"
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            resultCode != RESULT_OK
        ) {
            return
        }

        when (requestCode) {
            fileRequestCode -> {
                val uri =
                    data?.data
                        ?: return

                importFile(uri)
            }

            folderRequestCode -> {
                val uri =
                    data?.data
                        ?: return

                runCatching {
                    contentResolver
                        .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                }

                scanFolder(uri)
            }
        }
    }

    private fun openFilePicker() {
        val intent =
            Intent(
                Intent.ACTION_OPEN_DOCUMENT
            ).apply {
                addCategory(
                    Intent.CATEGORY_OPENABLE
                )

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
            }

        startActivityForResult(
            intent,
            fileRequestCode
        )
    }

    private fun openFolderPicker() {
        val intent =
            Intent(
                Intent
                    .ACTION_OPEN_DOCUMENT_TREE
            ).apply {
                addFlags(
                    Intent
                        .FLAG_GRANT_READ_URI_PERMISSION or
                        Intent
                            .FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }

        startActivityForResult(
            intent,
            folderRequestCode
        )
    }

    private fun importFile(
        uri: Uri
    ) {
        setLoading(true)

        status.text =
            "Analizando XPI o ZIP…"

        ExtensionManager.importXpi(
            this,
            uri
        ) {
                ok,
                message ->

            runOnUiThread {
                setLoading(false)

                status.text =
                    if (ok) {
                        "Importación completada"
                    } else {
                        "No se pudo importar"
                    }

                toast(message)

                if (ok) {
                    load()
                }
            }
        }
    }

    private fun scanFolder(
        uri: Uri
    ) {
        setLoading(true)

        status.text =
            "Buscando extensiones dentro de la carpeta…"

        ExtensionManager
            .scanExtensionFolder(
                this,
                uri
            ) {
                    result ->

                runOnUiThread {
                    setLoading(false)

                    result.onSuccess {
                        candidates ->

                        if (
                            candidates
                                .isEmpty()
                        ) {
                            status.text =
                                "No encontré XPI, ZIP ni una carpeta con manifest.json."

                            toast(
                                "Prueba seleccionando la carpeta que contiene la extensión o su archivo XPI/ZIP."
                            )
                        } else {
                            showCandidates(
                                candidates
                            )
                        }
                    }.onFailure {
                        error ->

                        status.text =
                            "No se pudo revisar la carpeta"

                        toast(
                            error.message
                                ?: "Error leyendo la carpeta"
                        )
                    }
                }
            }
    }

    private fun showCandidates(
        candidates:
            List<
                ExtensionManager
                    .LocalExtensionCandidate
                >
    ) {
        if (
            candidates.size == 1
        ) {
            installCandidate(
                candidates.first()
            )

            return
        }

        val labels =
            candidates
                .map {
                    it.label
                }
                .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(
                "Extensiones encontradas"
            )
            .setMessage(
                "Selecciona qué extensión quieres instalar."
            )
            .setItems(
                labels
            ) {
                    _,
                    which ->

                installCandidate(
                    candidates[which]
                )
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun installCandidate(
        candidate:
            ExtensionManager
                .LocalExtensionCandidate
    ) {
        setLoading(true)

        status.text =
            "Preparando ${candidate.label}…"

        ExtensionManager
            .importCandidate(
                this,
                candidate
            ) {
                    ok,
                    message ->

                runOnUiThread {
                    setLoading(false)

                    status.text =
                        if (ok) {
                            "Extensión instalada"
                        } else {
                            "Instalación rechazada"
                        }

                    toast(message)

                    if (ok) {
                        load()
                    }
                }
            }
    }

    private fun load() {
        setLoading(true)

        ExtensionManager.list(
            onSuccess = {
                items ->

                extensions =
                    items.sortedBy {
                        (
                            it.metaData
                                .name
                                ?: it.id
                            ).lowercase()
                    }

                runOnUiThread {
                    filter()
                    setLoading(false)
                }
            },
            onError = {
                error ->

                runOnUiThread {
                    setLoading(false)

                    status.text =
                        "No se pudieron cargar las extensiones"

                    toast(
                        "No se pudieron listar: " +
                            (
                                error.message
                                    ?: "error desconocido"
                                )
                    )
                }
            }
        )
    }

    private fun filter() {
        val term =
            search.text
                .toString()
                .trim()
                .lowercase()

        visibleExtensions =
            if (term.isBlank()) {
                extensions
            } else {
                extensions.filter {
                    extension ->

                    val meta =
                        extension.metaData

                    listOf(
                        meta.name,
                        meta.description,
                        extension.id
                    )
                        .filterNotNull()
                        .any {
                            it.lowercase()
                                .contains(
                                    term
                                )
                        }
                }
            }

        status.text =
            if (term.isBlank()) {
                "${extensions.size} extensiones instaladas"
            } else {
                "${visibleExtensions.size} coincidencias de ${extensions.size}"
            }

        listView.adapter =
            InstalledExtensionAdapter(
                this,
                visibleExtensions
            )

        listView.divider = null

        listView.dividerHeight =
            (
                resources
                    .displayMetrics
                    .density *
                    8
            ).toInt()
    }

    private fun showExtensionMenu(
        anchor: View,
        extension: WebExtension
    ) {
        val meta =
            extension.metaData

        val popup =
            PopupMenu(
                this,
                anchor
            )

        popup.menu.add(
            if (meta.enabled) {
                "Desactivar"
            } else {
                "Activar"
            }
        )

        popup.menu.add(
            if (
                meta
                    .allowedInPrivateBrowsing
            ) {
                "Bloquear en navegación privada"
            } else {
                "Permitir en navegación privada"
            }
        )

        popup.menu.add(
            "Buscar actualización"
        )

        if (
            !meta
                .optionsPageUrl
                .isNullOrBlank()
        ) {
            popup.menu.add(
                "Opciones de la extensión"
            )
        }

        popup.menu.add(
            "Información"
        )

        popup.menu.add(
            "Desinstalar"
        )

        popup
            .setOnMenuItemClickListener {
                item ->

                when (
                    item.title.toString()
                ) {
                    "Activar" -> {
                        setEnabled(
                            extension,
                            true
                        )
                        true
                    }

                    "Desactivar" -> {
                        setEnabled(
                            extension,
                            false
                        )
                        true
                    }

                    "Permitir en navegación privada" -> {
                        setPrivate(
                            extension,
                            true
                        )
                        true
                    }

                    "Bloquear en navegación privada" -> {
                        setPrivate(
                            extension,
                            false
                        )
                        true
                    }

                    "Buscar actualización" -> {
                        updateOne(
                            extension
                        )
                        true
                    }

                    "Opciones de la extensión" -> {
                        meta
                            .optionsPageUrl
                            ?.let {
                                openInBrowser(it)
                            }
                        true
                    }

                    "Información" -> {
                        showInfo(
                            extension
                        )
                        true
                    }

                    "Desinstalar" -> {
                        confirmUninstall(
                            extension
                        )
                        true
                    }

                    else -> true
                }
            }

        popup.show()
    }

    private fun setEnabled(
        extension: WebExtension,
        enabled: Boolean
    ) {
        ExtensionManager.setEnabled(
            this,
            extension,
            enabled
        ) {
                ok,
                message ->

            toast(message)

            if (ok) {
                load()
            }
        }
    }

    private fun setPrivate(
        extension: WebExtension,
        allowed: Boolean
    ) {
        ExtensionManager
            .setPrivateAllowed(
                this,
                extension,
                allowed
            ) {
                    ok,
                    message ->

                toast(message)

                if (ok) {
                    load()
                }
            }
    }

    private fun updateOne(
        extension: WebExtension
    ) {
        setLoading(true)

        status.text =
            "Buscando actualización…"

        ExtensionManager
            .updateExtension(
                this,
                extension
            ) {
                    ok,
                    message ->

                runOnUiThread {
                    setLoading(false)
                    toast(message)
                    load()
                }
            }
    }

    private fun updateAll() {
        if (
            extensions.isEmpty()
        ) {
            toast(
                "No hay extensiones para actualizar."
            )
            return
        }

        setLoading(true)

        status.text =
            "Revisando actualizaciones…"

        fun next(index: Int) {
            if (
                index >=
                extensions.size
            ) {
                runOnUiThread {
                    setLoading(false)

                    toast(
                        "Revisión de actualizaciones completada."
                    )

                    load()
                }

                return
            }

            runOnUiThread {
                status.text =
                    "Revisando ${index + 1} de ${extensions.size}…"
            }

            ExtensionManager
                .updateExtension(
                    this,
                    extensions[index]
                ) {
                        _,
                        _ ->

                    next(index + 1)
                }
        }

        next(0)
    }

    private fun showInfo(
        extension: WebExtension
    ) {
        val meta =
            extension.metaData

        AlertDialog.Builder(this)
            .setTitle(
                meta.name
                    ?: "Extensión"
            )
            .setMessage(
                buildString {
                    append(
                        "ID:\n${extension.id}"
                    )

                    append(
                        "\n\nVersión: " +
                            (
                                meta.version
                                    ?: "?"
                                )
                    )

                    append(
                        "\nEstado: " +
                            if (
                                meta.enabled
                            ) {
                                "Activa"
                            } else {
                                "Desactivada"
                            }
                    )

                    append(
                        "\nPrivado: " +
                            if (
                                meta
                                    .allowedInPrivateBrowsing
                            ) {
                                "Permitida"
                            } else {
                                "No permitida"
                            }
                    )

                    if (
                        !meta.description
                            .isNullOrBlank()
                    ) {
                        append(
                            "\n\n" +
                                meta.description
                        )
                    }
                }
            )
            .setPositiveButton(
                "Cerrar",
                null
            )
            .show()
    }

    private fun confirmUninstall(
        extension: WebExtension
    ) {
        AlertDialog.Builder(this)
            .setTitle(
                "Desinstalar extensión"
            )
            .setMessage(
                extension.metaData.name
                    ?: extension.id
            )
            .setNegativeButton(
                "Cancelar",
                null
            )
            .setPositiveButton(
                "Desinstalar"
            ) {
                    _,
                    _ ->

                ExtensionManager
                    .uninstall(
                        this,
                        extension
                    ) {
                            ok,
                            message ->

                        toast(message)

                        if (ok) {
                            load()
                        }
                    }
            }
            .show()
    }

    private fun showUrlInstaller() {
        val input =
            EditText(this).apply {
                hint =
                    "https://.../extension.xpi"

                setSingleLine(true)

                ThemeManager.styleEdit(
                    this@ExtensionsActivity,
                    this
                )
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Instalar desde una URL"
            )
            .setMessage(
                "Introduce una URL HTTPS directa a un XPI firmado y compatible."
            )
            .setView(input)
            .setNegativeButton(
                "Cancelar",
                null
            )
            .setPositiveButton(
                "Instalar"
            ) {
                    _,
                    _ ->

                val url =
                    input.text
                        .toString()
                        .trim()

                if (
                    url.startsWith(
                        "https://"
                    )
                ) {
                    setLoading(true)

                    ExtensionManager
                        .installUrl(
                            this,
                            url
                        ) {
                                ok,
                                message ->

                            runOnUiThread {
                                setLoading(
                                    false
                                )

                                toast(
                                    message
                                )

                                if (ok) {
                                    load()
                                }
                            }
                        }
                } else {
                    toast(
                        "Usa una dirección HTTPS válida."
                    )
                }
            }
            .show()
    }

    private fun openInBrowser(
        url: String
    ) {
        startActivity(
            Intent(
                this,
                MainActivity::class.java
            ).apply {
                addFlags(
                    Intent
                        .FLAG_ACTIVITY_CLEAR_TOP or
                        Intent
                            .FLAG_ACTIVITY_SINGLE_TOP
                )

                putExtra(
                    MainActivity.EXTRA_OPEN_URL,
                    url
                )
            }
        )
    }

    private fun setLoading(
        value: Boolean
    ) {
        progress.visibility =
            if (value) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun toast(
        message: String
    ) {
        runOnUiThread {
            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyTheme() {
        val palette =
            ThemeManager.palette(this)

        findViewById<View>(
            R.id.extensionsRoot
        ).setBackgroundColor(
            palette.background
        )

        ThemeManager.styleText(
            this,
            findViewById(
                R.id.extensionsTitle
            )
        )

        ThemeManager.styleText(
            this,
            findViewById(
                R.id.extensionsInfo
            ),
            muted = true
        )

        ThemeManager.styleText(
            this,
            status,
            muted = true
        )

        ThemeManager.styleEdit(
            this,
            search
        )

        ThemeManager.styleButton(
            this,
            findViewById(
                R.id.storeButton
            ),
            true
        )

        listOf(
            R.id.importButton,
            R.id.folderButton,
            R.id.urlInstallButton,
            R.id.updateAllButton
        ).forEach {
            ThemeManager.styleButton(
                this,
                findViewById(it)
            )
        }
    }
}
