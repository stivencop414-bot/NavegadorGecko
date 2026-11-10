package com.ejemplo.navegador

/** Extract a bounded PNG embedded in an ICO; other raster formats go to Android's decoder. */
object IconPayload {
    fun pngOrImage(bytes: ByteArray): ByteArray? {
        if (bytes.size > 262144 || bytes.size < 8) return null
        if (bytes[0] != 0.toByte() || bytes[1] != 0.toByte() || bytes[2] != 1.toByte() || bytes[3] != 0.toByte()) return bytes
        fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8)
        fun u32(i: Int): Long = (0..3).sumOf { (bytes[i+it].toLong() and 255) shl (8*it) }
        val count = u16(4)
        if (count !in 1..256 || 6 + count * 16 > bytes.size) return null
        val entries = (0 until count).map { 6 + it * 16 }.sortedByDescending { if (bytes[it] == 0.toByte()) 256 else bytes[it].toInt() and 255 }
        for (entry in entries) {
            val size = u32(entry+8); val offset = u32(entry+12)
            if (size < 8 || offset < 6 + count*16 || offset + size > bytes.size) continue
            val start = offset.toInt()
            if (bytes.sliceArray(start until start+8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) {
                return bytes.copyOfRange(start, (offset+size).toInt())
            }
        }
        return null
    }
}
