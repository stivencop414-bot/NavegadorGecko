package com.ejemplo.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class ExtensionStoreActivity :
    Activity() {

    private lateinit var query: EditText
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var items:
        List<StoreAddon> =
        emptyList()

    private var installedIds:
        Set<String> =
        emptySet()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        ThemeManager.applyWindow(this)

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout
                .activity_extension_store
        )

        query =
            findViewById(
                R.id.storeSearch
            )

        listView =
            findViewById(
                R.id.storeList
            )

        progress =
            findViewById(
                R.id.storeProgress
            )

        status =
            findViewById(
                R.id.storeStatus
            )

        findViewById<Button>(
            R.id.installedExtensionsButton
        ).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    ExtensionsActivity::class.java
                )
            )
        }

        findViewById<Button>(
            R.id.storeSearchButton
        ).setOnClickListener {
            search()
        }

        category(
            R.id.recommendedButton,
            ""
        )

        category(
            R.id.privacyButton,
            "privacy"
        )

        category(
            R.id.blockersButton,
            "ad blocker"
        )

        category(
            R.id.productivityButton,
            "productivity"
        )

        category(
            R.id.passwordsButton,
            "password manager"
        )

        category(
            R.id.videoButton,
            "video"
        )

        category(
            R.id.appearanceButton,
            "dark mode"
        )

        query
            .setOnEditorActionListener {
                    _,
                    actionId,
                    event ->

                val enter =
                    event?.keyCode ==
                        KeyEvent
                            .KEYCODE_ENTER

                if (
                    actionId ==
                        EditorInfo
                            .IME_ACTION_SEARCH ||
                    enter
                ) {
                    search()
                    true
                } else {
                    false
                }
            }

        applyTheme()

        refreshInstalled {
            search()
        }
    }

    override fun onResume() {
        super.onResume()

        ExtensionManager
            .attachPromptActivity(
                this
            )

        refreshInstalled {
            render()
        }
    }

    override fun onDestroy() {
        ExtensionManager
            .attachPromptActivity(
                null
            )

        super.onDestroy()
    }

    private fun category(
        buttonId: Int,
        term: String
    ) {
        findViewById<Button>(
            buttonId
        ).setOnClickListener {
            query.setText(term)
            search()
        }
    }

    private fun refreshInstalled(
        after: () -> Unit
    ) {
        ExtensionManager.list(
            onSuccess = { extensions ->
                installedIds =
                    extensions
                        .map {
                            it.id
                        }
                        .toSet()

                runOnUiThread {
                    after()
                }
            },
            onError = {
                runOnUiThread {
                    after()
                }
            }
        )
    }

    private fun search() {
        setLoading(true)

        val term =
            query.text
                .toString()
                .trim()

        (
            getSystemService(
                INPUT_METHOD_SERVICE
            ) as InputMethodManager
        ).hideSoftInputFromWindow(
            query.windowToken,
            0
        )

        query.clearFocus()

        status.text =
            if (term.isBlank()) {
                "Buscando recomendaciones para Android…"
            } else {
                "Buscando “$term”…"
            }

        AmoClient.search(term) {
            result ->
            setLoading(false)

            result.onSuccess {
                values ->
                items = values

                status.text =
                    when {
                        values.isEmpty() ->
                            "No encontramos extensiones compatibles."

                        term.isBlank() ->
                            "${values.size} extensiones recomendadas para Android"

                        else ->
                            "${values.size} resultados para “$term”"
                    }

                render()
            }.onFailure {
                error ->
                status.text =
                    "No se pudo cargar la tienda de Mozilla."

                Toast.makeText(
                    this,
                    "Tienda de extensiones: " +
                        (
                            error.message
                                ?: "error desconocido"
                        ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun render() {
        listView.adapter =
            StoreAddonAdapter(
                this,
                items,
                installedIds,
                onInstall = ::install,
                onDetails = ::showAddon
            )

        listView.divider = null

        listView.dividerHeight =
            (
                resources
                    .displayMetrics
                    .density *
                    9
            ).toInt()
    }

    private fun install(
        addon: StoreAddon
    ) {
        setLoading(true)

        status.text =
            "Preparando ${addon.name}…"

        ExtensionManager.installUrl(
            this,
            addon.xpiUrl
        ) {
                ok,
                message ->

            runOnUiThread {
                setLoading(false)

                status.text =
                    if (ok) {
                        "Extensión instalada correctamente"
                    } else {
                        "No se pudo instalar la extensión"
                    }

                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
                ).show()

                if (ok) {
                    refreshInstalled {
                        render()
                    }
                }
            }
        }
    }

    private fun showAddon(
        addon: StoreAddon
    ) {
        val rating =
            if (addon.rating > 0.0) {
                "%.1f de 5"
                    .format(
                        addon.rating
                    )
            } else {
                "Sin puntuación"
            }

        val installed =
            addon.id.isNotBlank() &&
                installedIds
                    .contains(
                        addon.id
                    )

        AlertDialog.Builder(this)
            .setTitle(addon.name)
            .setMessage(
                buildString {
                    if (
                        addon.author
                            .isNotBlank()
                    ) {
                        append(
                            "Desarrollador: " +
                                addon.author +
                                "\n\n"
                        )
                    }

                    append(
                        addon.summary
                    )

                    append(
                        "\n\nVersión: " +
                            addon.version
                                .ifBlank {
                                    "No disponible"
                                }
                    )

                    append(
                        "\nPuntuación: " +
                            rating
                    )

                    if (
                        addon.users > 0
                    ) {
                        append(
                            "\nUsuarios diarios: " +
                                "%,d".format(
                                    addon.users
                                )
                        )
                    }

                    if (installed) {
                        append(
                            "\n\n✓ Esta extensión ya está instalada."
                        )
                    }

                    append(
                        "\n\nFuente: Mozilla Add-ons. " +
                            "GeckoView comprobará firma, " +
                            "compatibilidad y permisos antes de instalar."
                    )
                }
            )
            .setNegativeButton(
                "Cerrar",
                null
            )
            .setNeutralButton(
                "Ver en Mozilla"
            ) {
                    _,
                    _ ->

                if (
                    addon.detailUrl
                        .isNotBlank()
                ) {
                    openInBrowser(
                        addon.detailUrl
                    )
                }
            }
            .apply {
                if (!installed) {
                    setPositiveButton(
                        "Instalar"
                    ) {
                            _,
                            _ ->

                        install(addon)
                    }
                }
            }
            .show()
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

    private fun applyTheme() {
        val palette =
            ThemeManager.palette(this)

        findViewById<View>(
            R.id.storeRoot
        ).setBackgroundColor(
            palette.background
        )

        ThemeManager.styleText(
            this,
            findViewById(
                R.id.storeTitle
            )
        )

        ThemeManager.styleText(
            this,
            findViewById(
                R.id.storeSubtitle
            ),
            muted = true
        )

        ThemeManager.styleText(
            this,
            findViewById(
                R.id.storeHelp
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
            query
        )

        ThemeManager.styleButton(
            this,
            findViewById(
                R.id
                    .installedExtensionsButton
            )
        )

        ThemeManager.styleButton(
            this,
            findViewById(
                R.id.storeSearchButton
            ),
            true
        )

        listOf(
            R.id.recommendedButton,
            R.id.privacyButton,
            R.id.blockersButton,
            R.id.productivityButton,
            R.id.passwordsButton,
            R.id.videoButton,
            R.id.appearanceButton
        ).forEach {
            ThemeManager.styleButton(
                this,
                findViewById(it)
            )
        }
    }
}
