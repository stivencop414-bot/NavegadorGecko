package com.nexo.browser

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Carrera entre varias peticiones equivalentes (p. ej. dos modelos de IA que
 * traducen lo mismo): gana la primera respuesta válida y las demás se
 * cancelan. Una que falla (null) no termina la carrera mientras quede otra.
 */
object FirstAnswer {
    /** Una petición en curso que se puede cancelar. */
    fun interface Attempt { fun cancel() }

    /**
     * Cada [starters] arranca una petición asíncrona y llama a su `done` una
     * vez, con el resultado o null si falló; devuelve null si no pudo
     * arrancar. Devuelve (índice del ganador, resultado) o null si todas
     * fallaron o se agotó [timeoutMs].
     */
    fun <T : Any> race(starters: List<((T?) -> Unit) -> Attempt?>, timeoutMs: Long): Pair<Int, T>? {
        if (starters.isEmpty()) return null
        val winner = CompletableFuture<Pair<Int, T>?>()
        val pending = AtomicInteger(starters.size)
        val attempts = ArrayList<Attempt>()
        starters.forEachIndexed { index, start ->
            val reported = java.util.concurrent.atomic.AtomicBoolean(false)
            val done: (T?) -> Unit = { value ->
                if (reported.compareAndSet(false, true)) {
                    if (value != null) winner.complete(index to value)
                    else if (pending.decrementAndGet() == 0) winner.complete(null)
                }
            }
            val attempt = runCatching { start(done) }.getOrNull()
            if (attempt == null) done(null) else synchronized(attempts) { attempts += attempt }
        }
        val result = try { winner.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
        synchronized(attempts) { attempts.forEach { runCatching { it.cancel() } } }
        return result
    }
}
