package com.ejemplo.navegador

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.StrictMode
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class NexoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.initialize(this)
        CrashLog.install(this)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }
}

object CrashLog {
    private const val PREFS = "nexo_crash_log"
    private const val KEY_LAST = "last_crash"
    private const val MAX_REPORT_CHARS = 12000
    private const val MAX_STACK_LINES = 90
    private var installed = false

    private val secretQuery = Regex(
        """(?i)(token|access[_-]?token|refresh[_-]?token|auth|authorization|session|secret|api[_-]?key|password)=([^&\s]+)"""
    )
    private val webUrl = Regex("""https?://[^\s<>"')\]]+""")
    private val appPath = Regex(
        """/(?:data/(?:data|user/\d+)|storage/emulated/\d+/Android/data)/[^\s:]+"""
    )

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

                val raw = buildString {
                    appendLine("Hilo: ${thread.name.take(80)}")
                    appendLine("Tipo: ${error.javaClass.name}")
                    appendLine("Mensaje: ${error.message ?: "(sin mensaje)"}")
                    appendLine()
                    append(
                        writer.toString()
                            .lineSequence()
                            .take(MAX_STACK_LINES)
                            .joinToString("\n")
                    )
                }

                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(
                        KEY_LAST,
                        redactCrashReport(raw).take(MAX_REPORT_CHARS)
                    )
                    .commit()
            }

            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                exitProcess(10)
            }
        }
    }

    private fun redactCrashReport(raw: String): String {
        var clean = secretQuery.replace(raw) { match ->
            "${match.groupValues[1]}=<redacted>"
        }

        clean = webUrl.replace(clean) { match ->
            runCatching {
                val uri = Uri.parse(match.value)
                uri.buildUpon()
                    .clearQuery()
                    .fragment(null)
                    .build()
                    .toString()
            }.getOrDefault("<url-redacted>")
        }

        clean = appPath.replace(clean, "<app-path-redacted>")
        return clean
    }

    fun consume(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_LAST, null)
        if (value != null) prefs.edit().remove(KEY_LAST).apply()
        return value
    }
}
