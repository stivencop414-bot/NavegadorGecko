package com.nexo.browser

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Bound decoded HTTP bodies as well as Content-Length (which may be missing or compressed). */
fun ResponseBody.boundedText(limit: Int = 2 * 1024 * 1024): String {
    if (contentLength() > limit) throw IOException("Respuesta demasiado grande")
    return byteStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("Respuesta demasiado grande")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
}
