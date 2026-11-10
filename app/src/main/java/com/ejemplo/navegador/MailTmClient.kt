package com.ejemplo.navegador

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** HTTPS only, no redirects/cookies, bounded responses, no credentials or bodies in logs. */
class MailTmClient {
    data class Account(val id: String, val address: String, val token: String) { override fun toString()="TemporaryMailbox(redacted)" }
    data class Message(val id: String, val subject: String, val from: String, val intro: String)
    private val random = SecureRandom()
    private var lastRequest=0L
    private var cooldownUntil=0L

    private fun request(method: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        require(path.startsWith('/') && !path.contains(".."))
        if(System.currentTimeMillis() < cooldownUntil) error("mail.tm pidió esperar antes de reintentar.")
        val gap=150L-(System.nanoTime()-lastRequest)/1000000L
        if(gap>0)Thread.sleep(gap.coerceAtMost(150L))
        lastRequest=System.nanoTime()
        val connection = URL("https://api.mail.tm$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control","no-store")
            connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/ld+json")
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
            if (status !in 200..299) throw IllegalStateException(if (status == 429) "mail.tm limitó las solicitudes. Espera antes de actualizar." else "mail.tm no pudo completar la operación (HTTP $status).")
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
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    fun create(): Account {
        val domains = request("GET", "/domains").getJSONArray("hydra:member")
        val domain = (0 until domains.length()).map { domains.getJSONObject(it) }
            .firstOrNull { it.optBoolean("isActive") && !it.optBoolean("isPrivate") }
            ?.getString("domain") ?: error("mail.tm no tiene dominios disponibles.")
        val entropy = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val address = "nexo${entropy.take(20)}@$domain"
        val credentials = JSONObject().put("address", address).put("password", entropy)
        val account = request("POST", "/accounts", body = credentials)
        val token = request("POST", "/token", body = credentials).getString("token")
        return Account(account.getString("id"), address, token)
    }

    fun list(account: Account): List<Message> {
        val items = request("GET", "/messages", account.token).getJSONArray("hydra:member")
        return (0 until minOf(items.length(), 30)).map { i ->
            val item = items.getJSONObject(i)
            Message(item.getString("id"), item.optString("subject").take(300),
                item.optJSONObject("from")?.optString("address").orEmpty().take(300), item.optString("intro").take(500))
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
}
