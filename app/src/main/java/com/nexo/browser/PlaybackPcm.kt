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
}
