package com.nexo.browser

import android.app.Activity
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import org.json.JSONArray
import org.mozilla.geckoview.GeckoSession

/**
 * Mediciones de rendimiento en el propio teléfono, para comparar versiones
 * con las mismas páginas y condiciones (Ajustes › Avanzado). Guarda las
 * últimas 50 muestras de cada tipo; nada sale del teléfono.
 *  - arranque: inicio del proceso → primer fotograma de Nexo (ms)
 *  - carga: inicio → fin de carga de cada página (ms)
 *  - memoria: montón Java + nativo del proceso principal al terminar una carga (MB)
 *  - fluidez: % de fotogramas de más de 17 ms en ventanas de 120 fotogramas
 */
object PerfLog {
    private const val FILE = "nexo_perf"
    private const val MAX = 50
    val KINDS = listOf("arranque" to "ms", "carga" to "ms", "memoria" to "MB", "fluidez" to "% lentos")

    private val pageStarts = java.util.WeakHashMap<GeckoSession, Long>()
    @Volatile private var startupRecorded = false
    private val frameThread by lazy { HandlerThread("NexoFrames").apply { start() } }

    fun record(context: Context, kind: String, value: Double) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val list = runCatching { JSONArray(prefs.getString(kind, "[]")) }.getOrDefault(JSONArray())
        list.put(value)
        while (list.length() > MAX) list.remove(0)
        prefs.edit().putString(kind, list.toString()).apply()
    }

    fun values(context: Context, kind: String): List<Double> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(kind, "[]")
        val list = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until list.length()).map { list.optDouble(it) }.filter { !it.isNaN() }
    }

    fun clear(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()

    /** Mediana y p95 de una lista (puro, probado en la JVM). */
    fun summary(values: List<Double>): Pair<Double, Double>? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
        return at(0.5) to at(0.95)
    }

    fun report(context: Context): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        append("Nexo ").append(version).append(" · ").append(android.os.Build.MODEL)
            .append(" · Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
        for ((kind, unit) in KINDS) {
            val values = values(context, kind)
            val stats = summary(values)
            append(kind).append(": ")
            if (stats == null) append("sin datos\n")
            else append("n=").append(values.size).append(" · mediana ").append("%.1f".format(stats.first))
                .append(" · p95 ").append("%.1f".format(stats.second)).append(' ').append(unit).append('\n')
        }
    }.trimEnd()

    /** Arranque hasta el primer fotograma y fluidez continua de la ventana principal. */
    fun attach(activity: Activity) {
        val decor = activity.window.decorView
        if (!startupRecorded) {
            decor.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (startupRecorded) return
                    startupRecorded = true
                    val ms = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                    decor.post { runCatching { decor.viewTreeObserver.removeOnDrawListener(this) } }
                    record(activity, "arranque", ms.toDouble())
                }
            })
        }
        var frames = 0
        var slow = 0
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
                frames++
                if (total > 17.0) slow++
                if (frames >= 120) {
                    record(activity, "fluidez", slow * 100.0 / frames)
                    frames = 0; slow = 0
                }
            }, Handler(frameThread.looper))
        }
    }

    fun onPageStart(session: GeckoSession) { pageStarts[session] = SystemClock.elapsedRealtime() }

    fun onPageStop(context: Context, session: GeckoSession, success: Boolean) {
        val start = pageStarts.remove(session) ?: return
        if (!success) return
        record(context, "carga", (SystemClock.elapsedRealtime() - start).toDouble())
        val runtime = Runtime.getRuntime()
        val mb = ((runtime.totalMemory() - runtime.freeMemory()) + Debug.getNativeHeapAllocatedSize()) / (1024.0 * 1024.0)
        record(context, "memoria", mb)
    }
}
