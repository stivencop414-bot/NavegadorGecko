package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private fun entry(url: String, title: String, ts: Long) = HistoryEntry(url, title, ts)

    @Test fun matchesTitleDomainAndUrlIgnoringCaseAndAccents() {
        val index = SuggestionIndex(listOf(
            entry("https://www.canciones.com/letra", "Letras de Canción", 1),
            entry("https://example.org/musica/cancion-nueva", "Otra página", 2),
            entry("https://news.site/", "Noticias", 3)
        ))
        assertEquals(listOf("https://www.canciones.com/letra", "https://example.org/musica/cancion-nueva"),
            index.search("CANCIÓN").map { it.url })
        assertEquals("https://www.canciones.com/letra", index.search("canciones.com").first().url)
        assertEquals("https://news.site/", index.search("noti").single().url)
    }

    @Test fun ordersByMatchThenByDateAndRemovesDuplicates() {
        val index = SuggestionIndex(listOf(
            entry("https://youtube.com/watch?v=1", "Video uno", 10),
            entry("https://www.youtube.com/watch?v=1", "Video uno (www)", 50),
            entry("https://example.com/youtube", "Sobre youtube", 99),
            entry("https://youtube.com/feed", "Feed", 20)
        ))
        val urls = index.search("you").map { it.url }
        // Dominio que empieza por la consulta antes que una coincidencia en la ruta; más reciente primero.
        assertEquals(listOf("https://www.youtube.com/watch?v=1", "https://youtube.com/feed", "https://example.com/youtube"), urls)
    }

    @Test fun emptyHistoryOrQueryGivesNothing() {
        assertTrue(SuggestionIndex(emptyList()).search("abc").isEmpty())
        assertTrue(SuggestionIndex(listOf(entry("https://a.com", "A", 1))).search("   ").isEmpty())
    }

    @Test fun credentialsNeverReachSuggestions() {
        val index = SuggestionIndex(listOf(entry("https://user:secret@intranet.example.com/panel", "Panel", 1)))
        assertEquals("https://intranet.example.com/panel", index.search("panel").single().url)
    }

    @Test fun localLookupP95IsWellUnder50ms() {
        val words = listOf("noticias", "canción", "receta", "fútbol", "música", "película", "tutorial", "clima")
        val entries = (0 until 500).map { i ->
            entry("https://site$i.example${i % 7}.com/${words[i % words.size]}/$i", "${words[(i * 3) % words.size]} página $i", i.toLong())
        }
        val index = SuggestionIndex(entries)
        val queries = listOf("n", "no", "not", "cancion", "re", "fut", "musi", "pel", "tuto", "site4", "example3", "página 12")
        repeat(20) { queries.forEach { index.search(it) } } // calentar la JVM
        val times = (0 until 200).map { i ->
            val start = System.nanoTime()
            index.search(queries[i % queries.size])
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        val p95 = times[(times.size * 95) / 100]
        println("suggestions p95=%.3f ms p50=%.3f ms".format(p95, times[times.size / 2]))
        assertTrue("p95 = $p95 ms", p95 < 50.0)
    }

    @Test fun parsesOpenSearchSuggestions() {
        val body = """["gato",["gato","Gatos graciosos","gatos graciosos","gato persa","x\u0001"]]"""
        assertEquals(listOf("Gatos graciosos", "gato persa"), RemoteSuggestions.parse(body, "gato"))
        assertTrue(RemoteSuggestions.parse("<html>", "gato").isEmpty())
        assertTrue(RemoteSuggestions.parse("""{"a":1}""", "gato").isEmpty())
    }

    @Test fun suggestionEndpointsAreHttpsOnly() {
        listOf(BrowserPrefs.ENGINE_DDG, BrowserPrefs.ENGINE_BING, BrowserPrefs.ENGINE_BRAVE, "google").forEach {
            assertTrue(RemoteSuggestions.endpoint(it, "a b&c")!!.startsWith("https://"))
        }
        assertTrue(RemoteSuggestions.endpoint("google", "a b&c")!!.contains("q=a+b%26c"))
        assertNull(RemoteSuggestions.endpoint(BrowserPrefs.ENGINE_STARTPAGE, "x"))
    }

    @Test fun addressesLocalNamesAndSecretsAreNotSentToTheSearchEngine() {
        listOf("https://intranet/x", "github.com", "192.168.1.1", "localhost:3000", "router.lan", "user@host",
            "printer.local", "api_key=123", "password: hunter2", "10.0.0.5/admin").forEach {
            assertTrue(it, UrlSafety.isPrivateQuery(it))
        }
        listOf("receta de pan", "clima mañana", "gatos", "c++ tutorial", "correo de juan@x.com hoy").forEach {
            assertFalse(it, UrlSafety.isPrivateQuery(it))
        }
    }

    @Test fun stripsCredentialsFromUrls() {
        assertEquals("https://host.com/a?b=1#c", UrlSafety.stripCredentials("https://u:p@host.com/a?b=1#c"))
        assertEquals("https://host.com", UrlSafety.stripCredentials("https://u@host.com"))
        assertEquals("https://host.com/x@y", UrlSafety.stripCredentials("https://host.com/x@y"))
        assertEquals("https://host.com/?token=", UrlSafety.stripCredentials("https://host.com/?token="))
        assertEquals("https://host.com/p", UrlSafety.forHistory("https://a:b@host.com/p?access_token=zzz"))
    }
}

class SecurityLimitsTest {
    @org.junit.Test fun keyedEndpointsMustBeHttpsOrLoopback() {
        listOf("https://api.openai.com/v1/chat/completions", "http://127.0.0.1:8080/v1/chat", "http://localhost/v1", "http://[::1]:1234/x")
            .forEach { org.junit.Assert.assertTrue(it, CaptionProtocol.isSafeKeyedEndpoint(it)) }
        listOf("http://api.example.com/v1", "https://user:pw@api.example.com/v1", "ftp://x/y", "javascript:alert(1)", "https:///nohost", "")
            .forEach { org.junit.Assert.assertFalse(it, CaptionProtocol.isSafeKeyedEndpoint(it)) }
    }

    @org.junit.Test fun bridgeMessagesAreBounded() {
        fun msg(vararg pairs: Pair<String, Any>) = org.json.JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
        org.junit.Assert.assertTrue(BridgeLimits.accept(msg("type" to "captions_text", "text" to "hola")))
        org.junit.Assert.assertTrue(BridgeLimits.accept(msg("type" to "captions_audio", "data" to "A".repeat(300_000))))
        org.junit.Assert.assertFalse(BridgeLimits.accept(msg("type" to "captions_audio", "data" to "A".repeat(400_000))))
        org.junit.Assert.assertFalse(BridgeLimits.accept(msg("type" to "captions_text", "text" to "x".repeat(5_000))))
        org.junit.Assert.assertFalse(BridgeLimits.accept(msg("type" to "captions_cues", "cues" to org.json.JSONArray((0..300).toList()))))
        org.junit.Assert.assertFalse(BridgeLimits.accept(msg("type" to "x", "nested" to org.json.JSONObject())))
        org.junit.Assert.assertFalse(BridgeLimits.accept(msg("nada" to 1)))
        org.junit.Assert.assertFalse(BridgeLimits.accept(org.json.JSONObject((0..20).associate { "k$it" to it }).put("type", "x")))
    }

    @org.junit.Test fun permissionsAreScopedToTheTopOrigin() {
        org.junit.Assert.assertEquals("https://example.com", PermissionScope.origin("https://Example.com:443/a?b#c"))
        org.junit.Assert.assertEquals("https://example.com:8443", PermissionScope.origin("https://example.com:8443/"))
        org.junit.Assert.assertEquals("https://example.com", PermissionScope.origin("https://u:p@example.com/"))
        org.junit.Assert.assertEquals(null, PermissionScope.origin("about:blank"))
        org.junit.Assert.assertNotEquals(PermissionScope.origin("https://a.example.com/"), PermissionScope.origin("https://b.example.com/"))
    }
}
