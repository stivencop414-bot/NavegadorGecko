package com.ejemplo.navegador

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoWebExecutor

object GeckoRuntimeHolder {
    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile private var executor: GeckoWebExecutor? = null


    fun get(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: run {
                val builder = GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(false)
                    .debugLogging(false)
                    .consoleOutput(false)
                    .javaScriptEnabled(true)
                    .largeKeepaliveFactor(2)
                    // Los sitios con modo oscuro siguen el tema de Nexo.
                    .preferredColorScheme(colorScheme(context))


                GeckoRuntime.create(
                    context.applicationContext,
                    builder.build()
                ).also {
                    runtime = it

                    // warmUp es una optimización; si el dispositivo no la
                    // tolera, Nexo debe continuar abriendo normalmente.
                    runCatching { it.warmUp() }

                    executor = GeckoWebExecutor(it)
                    applyRuntimePrefs(context)
                }
            }
        }

    /**
     * Executor de red de Gecko: comparte cookies, caché y política de
     * cookies con las pestañas, a diferencia de DownloadManager.
     */
    fun webExecutor(context: Context): GeckoWebExecutor =
        executor ?: synchronized(this) {
            executor ?: GeckoWebExecutor(get(context)).also { executor = it }
        }

    fun speculativeConnect(context: Context, url: String) {
        if (!UrlSafety.isWebUrl(url)) return
        runCatching { webExecutor(context).speculativeConnect(url) }
    }

    private fun colorScheme(context: Context): Int =
        when (BrowserPrefs.theme(context)) {
            BrowserPrefs.THEME_LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            BrowserPrefs.THEME_SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            else -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        }

    fun applyRuntimePrefs(context: Context) {
        val settings = runtime?.settings ?: return

        settings.setPreferredColorScheme(colorScheme(context))

        val dnsMode = if(BrowserPrefs.dnsStrict(context)) GeckoRuntimeSettings.TRR_MODE_ONLY else GeckoRuntimeSettings.TRR_MODE_FIRST
        when (BrowserPrefs.dnsProvider(context)) {
            BrowserPrefs.DNS_CUSTOM -> {
                val uri = BrowserPrefs.dnsCustom(context)
                // Corrupt stored settings must not silently switch to the system resolver.
                val valid = DnsProfile.valid(uri)
                settings.setTrustedRecursiveResolverUri(if(valid) uri else "https://127.0.0.1:9/dns-query")
                settings.setTrustedRecursiveResolverMode(if(valid) dnsMode else GeckoRuntimeSettings.TRR_MODE_ONLY)
            }
            BrowserPrefs.DNS_CLOUDFLARE -> {
                settings.setTrustedRecursiveResolverUri(
                    "https://cloudflare-dns.com/dns-query"
                )
                settings.setTrustedRecursiveResolverMode(
                    dnsMode
                )
            }
            BrowserPrefs.DNS_GOOGLE -> {
                settings.setTrustedRecursiveResolverUri(
                    "https://dns.google/dns-query"
                )
                settings.setTrustedRecursiveResolverMode(
                    dnsMode
                )
            }
            BrowserPrefs.DNS_QUAD9 -> {
                settings.setTrustedRecursiveResolverUri(
                    "https://dns.quad9.net/dns-query"
                )
                settings.setTrustedRecursiveResolverMode(
                    dnsMode
                )
            }
            else -> settings.setTrustedRecursiveResolverMode(
                GeckoRuntimeSettings.TRR_MODE_OFF
            )
        }

        settings.setAllowInsecureConnections(
            if (BrowserPrefs.httpsOnly(context)) {
                GeckoRuntimeSettings.HTTPS_ONLY
            } else {
                GeckoRuntimeSettings.ALLOW_ALL
            }
        )

        settings.setGlobalPrivacyControl(
            BrowserPrefs.globalPrivacyControl(context)
        )

        val cookieBehavior = when (BrowserPrefs.cookieMode(context)) {
            BrowserPrefs.COOKIES_ALL ->
                ContentBlocking.CookieBehavior.ACCEPT_ALL
            BrowserPrefs.COOKIES_FIRST_PARTY ->
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            BrowserPrefs.COOKIES_NONE ->
                ContentBlocking.CookieBehavior.ACCEPT_NONE
            else ->
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
        }

        settings.contentBlocking.setCookieBehavior(cookieBehavior)
        settings.contentBlocking.setCookieBehaviorPrivateMode(
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
        )
    }
}

object AppContext {
    @Volatile private var app: Context? = null

    fun initialize(context: Context) {
        app = context.applicationContext
    }

    fun get(): Context =
        requireNotNull(app) { "AppContext no inicializado" }
}
