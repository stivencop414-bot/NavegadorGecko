package com.nexo.browser

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Enlaces que no son páginas web: tel:, mailto:, sms:, geo:, market:,
 * intent:// (Play Store, WhatsApp, Maps…). Antes Gecko mostraba un error
 * porque no sabe abrirlos.
 *
 * El intent se sanea como en Chrome/Firefox: sin componente ni selector
 * explícitos, solo actividades BROWSABLE, sin permisos de URI heredados y
 * nunca dirigido a Nexo ni a datos file:/content:.
 */
object ExternalApps {
    private const val FALLBACK_EXTRA = "browser_fallback_url"
    private val blockedDataSchemes = setOf("file", "content", "javascript", "data", "blob")

    /** Esquemas que abren apps de uso común sin sorpresas. */
    private val directSchemes = setOf("tel", "mailto", "sms", "smsto", "mms", "mmsto", "geo")

    fun buildIntent(context: Context, url: String): Intent? {
        val scheme = UrlSafety.scheme(url) ?: return null
        val intent = if (scheme == "intent") {
            runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }

        intent.component = null
        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        // Reemplazar flags: elimina FLAG_GRANT_* y similares que vinieran en el enlace.
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        if (intent.`package` == context.packageName) return null
        val dataScheme = intent.data?.scheme?.lowercase()
        if (dataScheme != null && dataScheme in blockedDataSchemes) return null
        return intent
    }

    /**
     * Abre [url] en otra app. Si no hay ninguna, usa la página alternativa
     * del enlace ([loadFallback]) o la ficha de Play Store del paquete.
     */
    fun open(
        context: Context,
        url: String,
        hasUserGesture: Boolean,
        isPrivate: Boolean,
        loadFallback: (String) -> Unit
    ) {
        val intent = buildIntent(context, url)
        if (intent == null) {
            TabManager.notifyMessage("Nexo bloqueó un enlace no seguro")
            return
        }

        val fallback = intent.getStringExtra(FALLBACK_EXTRA)?.takeIf { UrlSafety.isWebUrl(it) }
        intent.removeExtra(FALLBACK_EXTRA)
        val targetPackage = intent.`package`

        fun launch() {
            val host = BrowserHosts.current()
            try {
                if (host != null) host.launch(intent) else context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                when {
                    fallback != null -> loadFallback(fallback)
                    targetPackage != null -> openStore(context, targetPackage)
                    else -> TabManager.notifyMessage("Ninguna app instalada puede abrir este enlace")
                }
            } catch (_: SecurityException) {
                TabManager.notifyMessage("Android no permitió abrir este enlace")
            }
        }

        val scheme = UrlSafety.scheme(url)
        val ask = isPrivate || !hasUserGesture || scheme !in directSchemes
        if (!ask) {
            launch()
            return
        }
        // Sin pantalla visible no se abre nada que requiera confirmación.
        val activity = BrowserHosts.current()?.hostActivity ?: return

        // Una redirección sin toque del usuario o una app desconocida: confirmar.
        AlertDialog.Builder(activity)
            .setTitle("¿Abrir en otra aplicación?")
            .setMessage(
                buildString {
                    append("La página quiere abrir ")
                    append(describe(scheme, targetPackage))
                    append(".")
                    if (isPrivate) append("\n\nEstás en una pestaña privada: la otra app podrá ver el enlace.")
                }
            )
            .setNegativeButton("Cancelar") { _, _ -> fallback?.let(loadFallback) }
            .setPositiveButton("Abrir") { _, _ -> launch() }
            .show()
    }

    private fun describe(scheme: String?, targetPackage: String?): String =
        when (scheme) {
            "tel" -> "el marcador telefónico"
            "mailto" -> "tu app de correo"
            "sms", "smsto", "mms", "mmsto" -> "tu app de mensajes"
            "geo" -> "una app de mapas"
            "market" -> "Google Play"
            else -> targetPackage?.let { "la app $it" } ?: "una app instalada ($scheme:)"
        }

    private fun openStore(context: Context, packageName: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Uri.encode(packageName)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            val host = BrowserHosts.current()
            if (host != null) host.launch(market) else context.startActivity(market)
        }.onFailure {
            TabManager.notifyMessage("La app necesaria no está instalada")
        }
    }
}
