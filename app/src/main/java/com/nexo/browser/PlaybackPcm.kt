package com.nexo.browser

object PlaybackPcm {
    const val SAMPLE_RATE = 16000
    fun hasSignal(bytes: ByteArray): Boolean {
        for(i in 0 until bytes.size-1 step 2) {
            val value = ((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt()
            if (kotlin.math.abs(value) > 120) return true
        }
        return false
    }
    fun listenUrl(language: String, model: String): String = CaptionProtocol.listenUrl(language,model) +
        "&encoding=linear16&sample_rate=$SAMPLE_RATE&channels=1"

    /** Pico absoluto de un bloque PCM16 little-endian. */
    fun peak(bytes: ByteArray, size: Int = bytes.size): Int {
        var peak = 0
        var i = 0
        while (i + 1 < size) {
            val value = kotlin.math.abs(((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt())
            if (value > peak) peak = value
            i += 2
        }
        return peak
    }

    /**
     * Ganancia automática suave para voz baja (un video con poco volumen se
     * reconoce peor). Solo amplifica (1×–8×), baja rápido ante picos para no
     * saturar y sube despacio; el ruido de fondo casi mudo no se amplifica.
     * Devuelve la ganancia usada para el siguiente bloque.
     */
    class Gain(private val target: Int = 16000, private val maxGain: Float = 8f, private val floor: Int = 120) {
        var gain = 1f
            private set

        fun apply(bytes: ByteArray, size: Int = bytes.size) {
            val peak = peak(bytes, size)
            if (peak > floor) {
                val wanted = (target.toFloat() / peak).coerceIn(1f, maxGain)
                // Baja al momento (evita recortes); sube un 15 % por bloque como mucho.
                gain = if (wanted < gain) wanted else minOf(wanted, gain * 1.15f)
            }
            if (gain <= 1.001f) return
            var i = 0
            while (i + 1 < size) {
                val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[i] = (scaled and 255).toByte()
                bytes[i + 1] = ((scaled shr 8) and 255).toByte()
                i += 2
            }
        }
    }
}
