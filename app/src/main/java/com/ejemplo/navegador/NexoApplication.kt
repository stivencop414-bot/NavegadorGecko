package com.ejemplo.navegador

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class NexoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.initialize(this)
        CrashLog.install(this)
        GeckoRuntimeHolder.preload(this)
    }
}

object CrashLog {
    private const val PREFS = "nexo_crash_log"
    private const val KEY_LAST = "last_crash"
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true

        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))

                val report = buildString {
                    appendLine("Hilo: ${thread.name}")
                    appendLine("Tipo: ${error.javaClass.name}")
                    appendLine("Mensaje: ${error.message ?: "(sin mensaje)"}")
                    appendLine()
                    append(writer.toString())
                }

                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST, report.take(24000))
                    .commit()
            }

            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                exitProcess(10)
            }
        }
    }

    fun consume(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_LAST, null)
        if (value != null) prefs.edit().remove(KEY_LAST).apply()
        return value
    }
}
