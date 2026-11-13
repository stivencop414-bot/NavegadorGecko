package com.nexo.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Portable native data, never opaque Gecko session state or private containers. */
object TransferSchema {
    val secretNames = setOf("deepgram_key", "deepl_key", "ai_translate_key")
    val settingsKeys = setOf(
"accent", "ai_translate_endpoint", "ai_translate_model", "ai_translate_provider", "autoplay", "background_media", "caption_daily_limit", "caption_audio_auto", "caption_language", "caption_mode", "caption_size", "caption_translate", "caption_usage_day", "caption_usage_seconds", "captions_enabled", "clear_on_exit", "cookie_mode", "desktop_default", "dns_custom", "dns_provider", "dns_strict", "fingerprinting", "force_zoom", "free_search", "gpc", "https_only", "javascript", "max_live", "nexo_home", "pull_to_refresh", "dock_gestures", "parallel_downloads", "query_stripping", "remote_debugging", "restore_tabs", "safe_browsing", "search", "smart_pip", "strict_tracking", "text_scale", "theme", "tracking", "translation_provider", "translator_target"    )
    val settingsTypes = mapOf("accent" to "string", "ai_translate_endpoint" to "string", "ai_translate_model" to "string", "ai_translate_provider" to "string", "autoplay" to "string", "background_media" to "boolean", "caption_daily_limit" to "int", "caption_audio_auto" to "boolean", "caption_language" to "string", "caption_mode" to "string", "caption_size" to "int", "caption_translate" to "string", "caption_usage_day" to "string", "caption_usage_seconds" to "int", "captions_enabled" to "boolean", "clear_on_exit" to "boolean", "cookie_mode" to "string", "desktop_default" to "boolean", "dns_custom" to "string", "dns_provider" to "string", "dns_strict" to "boolean", "fingerprinting" to "boolean", "force_zoom" to "boolean", "free_search" to "boolean", "gpc" to "boolean", "https_only" to "boolean", "javascript" to "boolean", "max_live" to "int", "nexo_home" to "boolean", "pull_to_refresh" to "boolean", "dock_gestures" to "boolean", "parallel_downloads" to "boolean", "query_stripping" to "boolean", "remote_debugging" to "boolean", "restore_tabs" to "boolean", "safe_browsing" to "boolean", "search" to "string", "smart_pip" to "boolean", "strict_tracking" to "boolean", "text_scale" to "int", "theme" to "string", "tracking" to "boolean", "translation_provider" to "string", "translator_target" to "string")
    fun tabs(raw: String): String {
        val input = JSONArray(raw); require(input.length() <= 500)
        val result = JSONArray()
        for (i in 0 until input.length()) {
            val t = input.getJSONObject(i)
            if (t.optBoolean("isPrivate") || t.optBoolean("private") || t.optString("burnerId").let { it.isNotEmpty() && it != "null" }) continue
            val url = t.getString("url").take(8192)
            if (!UrlSafety.isWebUrl(url) && url != "about:blank") continue
            result.put(JSONObject().put("id", UUID.randomUUID().toString()).put("url", url)
                .put("title", t.optString("title").take(512)).put("groupName", t.optString("groupName").take(80))
                .put("desktopMode", t.optBoolean("desktopMode")).put("lastUsed", t.optLong("lastUsed")))
        }
        return result.toString()
    }
    fun validId(id: String): Boolean = id.length in 1..180 && id.matches(Regex("[A-Za-z0-9@._{}+-]+")) && id != "." && id != ".."
    fun uuid(uuid: String): Boolean = uuid.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
    fun origin(name: String, uuids: Set<String>): Boolean = uuids.any {
        name == "moz-extension+++$it" || name == "moz-extension+++$it^userContextId=4294967295"
    }
    fun payloadPath(path: String, uuids: Set<String>, ids: Set<String>): Boolean {
        if (!MigrationArchive.safePath(path)) return false
        if (path in setOf("native.json", "addons.json", "manifest.json")) return true
        val p = path.split('/')
        return when {
            p.size >= 4 && p[0] == "gecko" && p[1] == "default" -> origin(p[2], uuids) && p.drop(3).none { it.startsWith(".") && it != ".metadata-v2" }
            p.size == 4 && p[0] == "gecko" && p[1] == "legacy" -> p[2] in ids && p[3] == "storage.js"
            p.size == 2 && p[0] == "addons" -> p[1].matches(Regex("[a-f0-9]{64}\\.xpi"))
            else -> false
        }
    }
    fun profile(filesDir: File): File {
        val home = File(filesDir, "mozilla")
        require(!java.nio.file.Files.isSymbolicLink(home.toPath()))
        val ini = File(home, "profiles.ini")
        require(!java.nio.file.Files.isSymbolicLink(ini.toPath()) && ini.isFile && ini.length() < 65536) { "No se encuentra profiles.ini. Abre el navegador una vez antes de entrar en mantenimiento." }
        val sections = ini.readText().split(Regex("(?m)^\\["))
            .filter { it.startsWith("Profile") }.map { section ->
                section.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0,it) to l.substring(it+1).trim() } }.toMap()
            }
        val item = sections.singleOrNull { it["Default"] == "1" } ?: sections.singleOrNull()
        require(item != null && item["IsRelative"] == "1") { "Perfil Gecko ambiguo o no compatible; no se copiará parcialmente." }
        val name = requireNotNull(item["Path"])
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..")
        val profile = File(home, name)
        require(profile.isDirectory && !java.nio.file.Files.isSymbolicLink(profile.toPath()))
        require(profile.canonicalFile.parentFile == home.canonicalFile)
        return profile
    }
}
