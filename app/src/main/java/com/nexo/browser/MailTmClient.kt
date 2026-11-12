package com.nexo.browser

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** HTTPS only, no redirects/cookies, bounded responses, no credentials or bodies in logs. */
class MailTmClient {
    data class Account(val id: String, val address: String, val token: String) { override fun toString()="TemporaryMailbox(redacted)" }
    data class Message(val id: String, val subject: String, val from: String, val intro: String, val seen: Boolean = false)

    /** Error de la API con su código HTTP (0 = sin respuesta). */
    class ApiException(val status: Int, message: String) : IllegalStateException(message)

    private val random = SecureRandom()
    private var lastRequest=0L
    private var cooldownUntil=0L

    private fun request(method: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        require(path.startsWith('/') && !path.contains(".."))
        if(System.currentTimeMillis() < cooldownUntil) throw ApiException(429, "mail.tm pidió esperar antes de reintentar.")
        // mail.tm admite unas 8 peticiones por segundo.
        synchronized(this) {
            val gap=150L-(System.nanoTime()-lastRequest)/1000000L
            if(gap>0)Thread.sleep(gap.coerceAtMost(150L))
            lastRequest=System.nanoTime()
        }
        val connection = URL("https://api.mail.tm$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control","no-store")
            connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/ld+json, application/json;q=0.9")
            connection.setRequestProperty("Cookie", "")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            if (status == 404 && method == "DELETE") return JSONObject()
            if(status==429) {
                val seconds=connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1,3600) ?: 60
                cooldownUntil=System.currentTimeMillis()+seconds*1000L
            }
            if (status !in 200..299) throw ApiException(status, when (status) {
                429 -> "mail.tm limitó las solicitudes. Espera un momento antes de actualizar."
                401 -> "La sesión del buzón caducó."
                else -> "mail.tm no pudo completar la operación (HTTP $status)."
            })
            if (status == 204) return JSONObject()
            require(connection.contentLengthLong <= 524288) { "Respuesta del buzón demasiado grande." }
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 524288) { "Mensaje demasiado grande." }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            return parse(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    fun create(): Account {
        val domain = members(request("GET", "/domains")).let { domains ->
            (0 until domains.length()).mapNotNull { domains.optJSONObject(it) }
                .firstOrNull { it.optBoolean("isActive", true) && !it.optBoolean("isPrivate", false) }
                ?.optString("domain")?.takeIf { it.isNotBlank() }
        } ?: error("mail.tm no tiene dominios disponibles.")
        var lastError: Exception? = null
        // Una dirección ya usada devuelve 422: se prueba otra al azar.
        repeat(3) {
            val entropy = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val address = "nexo${entropy.take(20)}@$domain"
            val credentials = JSONObject().put("address", address).put("password", entropy)
            try {
                val account = request("POST", "/accounts", body = credentials)
                val token = request("POST", "/token", body = credentials).getString("token")
                return Account(account.getString("id"), address, token)
            } catch (error: ApiException) {
                lastError = error
                if (error.status != 422) throw error
            }
        }
        throw lastError ?: IllegalStateException("No se pudo crear el buzón.")
    }

    fun list(account: Account): List<Message> {
        val items = members(request("GET", "/messages", account.token))
        return (0 until minOf(items.length(), 30)).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Message(id, item.optString("subject").take(300),
                item.optJSONObject("from")?.optString("address").orEmpty().take(300),
                item.optString("intro").take(500), item.optBoolean("seen", false))
        }
    }
    fun text(account: Account, id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        return request("GET", "/messages/$id", account.token).optString("text").take(100000)
    }
    fun delete(account: Account) {
        require(account.id.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        request("DELETE", "/accounts/${account.id}", account.token)
    }

    companion object {
        /**
         * Colecciones de la API: "hydra:member" (JSON-LD clásico), "member"
         * (API Platform reciente) o una lista sin envoltorio (application/json).
         * Si mail.tm cambia de formato, el buzón sigue funcionando.
         */
        fun members(json: JSONObject): JSONArray =
            json.optJSONArray("hydra:member") ?: json.optJSONArray("member") ?: json.optJSONArray(LIST_KEY) ?: JSONArray()

        private const val LIST_KEY = "\u0000list"

        fun parse(text: String): JSONObject {
            val trimmed = text.trimStart()
            return if (trimmed.startsWith("[")) JSONObject().put(LIST_KEY, JSONArray(trimmed)) else JSONObject(trimmed)
        }

        private val CODE = Regex("""(?<![\w-])(?:[A-Z0-9]{3}-[A-Z0-9]{3}|\d{4,8})(?![\w-])""")

        /** Código de verificación (4–8 cifras o ABC-123) en un asunto o resumen. */
        fun verificationCode(vararg texts: String): String? =
            texts.asSequence().mapNotNull { text ->
                CODE.findAll(text).map { it.value }.firstOrNull { value ->
                    // Años y horas no son códigos.
                    !(value.length == 4 && value.toIntOrNull()?.let { it in 1900..2099 } == true)
                }
            }.firstOrNull()
    }
}
