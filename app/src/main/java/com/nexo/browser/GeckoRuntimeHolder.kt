package com.nexo.browser

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoWebExecutor

object GeckoRuntimeHolder {
    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile private var executor: GeckoWebExecutor? = null


    fun shutdownForTransfer(done: () -> Unit) {
        val current = runtime
        if (current == null) { done(); return }
        current.setDelegate(object : GeckoRuntime.Delegate {
            override fun onShutdown() { runtime = null; executor = null; done() }
        })
        current.shutdown()
    }

    fun started(): Boolean = runtime != null

    fun get(context: Context): GeckoRuntime {
        check(!MigrationMode.enabled(context)) { "Gecko no puede arrancar durante la transferencia" }
        return runtime ?: synchronized(this) {
            runtime ?: run {
                val builder = GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(false)
                    .debugLogging(false)
                    .consoleOutput(false)
                    .javaScriptEnabled(true)
                    .largeKeepaliveFactor(2)
                    // Los sitios con modo oscuro siguen el tema de Nexo.
                    .preferredColorScheme(colorScheme(context))
                    // Sin procesos aislados: en GeckoView el proceso de extensiones también
                    // es de contenido y, aislado, las extensiones no arrancan (0.28.0 dejó sin
                    // PhantomShield, sin lista de instaladas y sin tienda).
                    .isolatedProcessEnabled(false)


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
                    applyAudioPrefs(context)
                }
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

    /**
     * Audio de Gecko por la vía normal del mezclador de Android, la que la
     * captura de reproducción (subtítulos) puede oír. cubeb abre los flujos
     * AAudio en modo de baja latencia (MMAP, invisible para la captura) si
     * piden ≤ 4000 frames: Web Audio y los videos capturados por la página
     * pedían 512–1024. Con 4800 frames (~100 ms) van por el mezclador.
     * Tienen que ser preferencias de usuario: Gecko ignora la de Web Audio
     * si solo es un valor por defecto (las de arranque lo son).
     */
    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    fun applyAudioPrefs(context: Context) {
        if (runtime == null) return
        runCatching {
            if (BrowserPrefs.captureFriendlyAudio(context)) {
                GeckoPreferenceController.setGeckoPref("media.cubeb_latency_mtg_frames", 4800, GeckoPreferenceController.PREF_BRANCH_USER)
                GeckoPreferenceController.setGeckoPref("media.cubeb_latency_playback_ms", 150, GeckoPreferenceController.PREF_BRANCH_USER)
            } else {
                // Comparación controlada: vuelve a los valores de Gecko.
                GeckoPreferenceController.clearGeckoUserPref("media.cubeb_latency_mtg_frames")
                GeckoPreferenceController.clearGeckoUserPref("media.cubeb_latency_playback_ms")
            }
        }
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

        val tracking = BrowserPrefs.trackingProtection(context)
        val strict = tracking && BrowserPrefs.strictTracking(context)
        runCatching {
            settings.contentBlocking.setEnhancedTrackingProtectionLevel(
                when {
                    strict -> ContentBlocking.EtpLevel.STRICT
                    tracking -> ContentBlocking.EtpLevel.DEFAULT
                    else -> ContentBlocking.EtpLevel.NONE
                }
            )
            settings.contentBlocking.setStrictSocialTrackingProtection(strict)
        }
        runCatching {
            settings.contentBlocking.setSafeBrowsing(
                if (BrowserPrefs.safeBrowsing(context)) ContentBlocking.SafeBrowsing.DEFAULT
                else ContentBlocking.SafeBrowsing.NONE
            )
        }
        runCatching { settings.contentBlocking.setQueryParameterStrippingEnabled(BrowserPrefs.queryStripping(context)) }
        runCatching {
            settings.setFingerprintingProtection(BrowserPrefs.fingerprinting(context))
            settings.setFingerprintingProtectionPrivateBrowsing(true)
        }
        runCatching {
            settings.setAutomaticFontSizeAdjustment(false)
            settings.setFontSizeFactor(BrowserPrefs.textScale(context) / 100f)
        }
        runCatching { settings.setForceUserScalableEnabled(BrowserPrefs.forceZoom(context)) }
        runCatching { settings.setJavaScriptEnabled(BrowserPrefs.javascript(context)) }
        runCatching { settings.setRemoteDebuggingEnabled(BrowserPrefs.remoteDebugging(context)) }
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
