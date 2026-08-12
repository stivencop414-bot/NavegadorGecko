package com.ejemplo.navegador

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoRuntimeHolder {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(false)
                    .debugLogging(true)
                    .javaScriptEnabled(true)
                    .build()
            ).also {
                it.warmUp()
                runtime = it
            }
        }
}

object AppContext {
    @Volatile
    private var app: Context? = null

    fun initialize(context: Context) {
        app = context.applicationContext
    }

    fun get(): Context =
        requireNotNull(app) { "AppContext no inicializado" }
}
