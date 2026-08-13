package com.ejemplo.navegador

import android.content.Context
import android.os.Build
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoWebExecutor

object GeckoRuntimeHolder {
    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile private var executor: GeckoWebExecutor? = null

    fun preload(context: Context) {
        get(context)
    }

    fun get(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: run {
                val builder = GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(false)
                    .debugLogging(false)
                    .consoleOutput(false)
                    .javaScriptEnabled(true)
                    .largeKeepaliveFactor(3)

                if (Build.VERSION.SDK_INT >= 29) {
                    builder.appZygoteProcessEnabled(true)
                }

                GeckoRuntime.create(
                    context.applicationContext,
                    builder.build()
                ).also {
                    runtime = it
                    it.warmUp()
                    executor = GeckoWebExecutor(it)
                    applyRuntimePrefs(context)
                }
            }
        }

    fun speculativeConnect(context: Context, url: String) {
        if (!url.startsWith("https://", true) &&
            !url.startsWith("http://", true)
        ) return

        runCatching {
            val web = executor ?: synchronized(this) {
                executor ?: GeckoWebExecutor(get(context)).also { executor = it }
            }
            web.speculativeConnect(url)
        }
    }

    fun applyRuntimePrefs(context: Context) {
        val settings = runtime?.settings ?: return

        when (BrowserPrefs.dnsProvider(context)) {
            BrowserPrefs.DNS_CLOUDFLARE -> {
                settings.setTrustedRecursiveResolverUri(
                    "https://cloudflare-dns.com/dns-query"
                )
                settings.setTrustedRecursiveResolverMode(
                    GeckoRuntimeSettings.TRR_MODE_FIRST
                )
            }
            BrowserPrefs.DNS_GOOGLE -> {
                settings.setTrustedRecursiveResolverUri(
                    "https://dns.google/dns-query"
                )
                settings.setTrustedRecursiveResolverMode(
                    GeckoRuntimeSettings.TRR_MODE_FIRST
                )
            }
            BrowserPrefs.DNS_QUAD9 -> {
                settings.setTrustedRecursiveResolverUri(
                    "https://dns.quad9.net/dns-query"
                )
                settings.setTrustedRecursiveResolverMode(
                    GeckoRuntimeSettings.TRR_MODE_FIRST
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
