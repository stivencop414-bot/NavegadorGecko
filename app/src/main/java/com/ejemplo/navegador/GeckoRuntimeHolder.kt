package com.ejemplo.navegador

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoRuntimeHolder {
    @Volatile private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(false)
                    .debugLogging(false)
                    .javaScriptEnabled(true)
                    .largeKeepaliveFactor(2)
                    .build()
            ).also {
                it.warmUp()
                runtime = it
                applyRuntimePrefs(context)
            }
        }

    fun applyRuntimePrefs(context: Context) {
        val settings = runtime?.settings ?: return

        when (BrowserPrefs.dnsProvider(context)) {
            BrowserPrefs.DNS_CLOUDFLARE -> {
                settings.setTrustedRecursiveResolverUri("https://cloudflare-dns.com/dns-query")
                settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
            }
            BrowserPrefs.DNS_GOOGLE -> {
                settings.setTrustedRecursiveResolverUri("https://dns.google/dns-query")
                settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
            }
            BrowserPrefs.DNS_QUAD9 -> {
                settings.setTrustedRecursiveResolverUri("https://dns.quad9.net/dns-query")
                settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
            }
            else -> settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
        }

        settings.setAllowInsecureConnections(
            if (BrowserPrefs.httpsOnly(context)) GeckoRuntimeSettings.HTTPS_ONLY
            else GeckoRuntimeSettings.ALLOW_ALL
        )
        settings.setGlobalPrivacyControl(BrowserPrefs.globalPrivacyControl(context))

        val cookieBehavior = when (BrowserPrefs.cookieMode(context)) {
            BrowserPrefs.COOKIES_ALL -> ContentBlocking.CookieBehavior.ACCEPT_ALL
            BrowserPrefs.COOKIES_FIRST_PARTY -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            BrowserPrefs.COOKIES_NONE -> ContentBlocking.CookieBehavior.ACCEPT_NONE
            else -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
        }
        settings.contentBlocking.setCookieBehavior(cookieBehavior)
        settings.contentBlocking.setCookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
    }
}

object AppContext {
    @Volatile private var app: Context? = null
    fun initialize(context: Context) { app = context.applicationContext }
    fun get(): Context = requireNotNull(app) { "AppContext no inicializado" }
}
