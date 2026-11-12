package com.nexo.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMergeTest {
    private fun sample(): JSONObject = LegacyMerge.build(
        settings = mapOf("theme" to "dark", "max_live" to 4, "smart_pip" to false, "unknown_key" to "x", "accent" to 7),
        historyJson = """[{"url":"https://a.com/","title":"A","timestamp":10},{"url":"javascript:alert(1)","title":"x","timestamp":1}]""",
        customJson = """[{"url":"https://b.com/"}]""",
        hidden = setOf("https://m.youtube.com/"),
        tabsJson = """[{"url":"https://c.com/","title":"C","groupName":"Trabajo"},{"url":"https://p.com/","isPrivate":true},
            {"url":"https://burner.com/","burnerId":"burner-1"},{"url":"file:///sdcard/x","title":"F"}]""",
        secrets = mapOf("deepgram_key" to "dg-secret", "deepl_key" to "")
    )

    @Test fun roundTripKeepsOnlyKnownSafeData() {
        val payload = LegacyMerge.parse(sample().toString())
        assertEquals(mapOf("theme" to "dark", "max_live" to 4, "smart_pip" to false), payload.settings)
        assertEquals(1, payload.history.length())
        assertEquals("https://a.com/", payload.history.getJSONObject(0).getString("url"))
        assertEquals(listOf(LegacyMerge.Tab("https://c.com/", "C", "Trabajo", false)), payload.tabs)
        assertEquals(mapOf("deepgram_key" to "dg-secret"), payload.secrets)
        assertEquals(setOf("https://m.youtube.com/"), payload.hiddenLinks)
    }

    @Test fun rejectsForeignOrTamperedPayloads() {
        assertThrows(Exception::class.java) { LegacyMerge.parse(sample().put("source", "com.evil").toString()) }
        assertThrows(Exception::class.java) { LegacyMerge.parse(sample().put("version", 2).toString()) }
        val wrongType = sample().apply { getJSONObject("settings").put("theme", JSONObject().put("type", "int").put("value", 3)) }
        assertThrows(Exception::class.java) { LegacyMerge.parse(wrongType.toString()) }
        val unknownSecret = sample().apply { getJSONObject("secrets").put("password", "x") }
        assertThrows(Exception::class.java) { LegacyMerge.parse(unknownSecret.toString()) }
    }

    @Test fun historyKeepsNewestVisitPerUrl() {
        val existing = JSONArray("""[{"url":"https://a.com/","title":"A nuevo","timestamp":50},{"url":"https://z.com/","title":"Z","timestamp":5}]""")
        val incoming = JSONArray("""[{"url":"https://a.com/","title":"A viejo","timestamp":10},{"url":"https://y.com/","title":"Y","timestamp":30}]""")
        val merged = LegacyMerge.mergeHistory(existing, incoming)
        assertEquals(listOf("https://a.com/", "https://y.com/", "https://z.com/"), (0 until merged.length()).map { merged.getJSONObject(it).getString("url") })
        assertEquals("A nuevo", merged.getJSONObject(0).getString("title"))
    }

    @Test fun linksAndTabsAreNotDuplicated() {
        val links = LegacyMerge.mergeLinks(JSONArray("""[{"url":"https://b.com/"}]"""), JSONArray("""[{"url":"https://B.com"},{"url":"https://d.com/"}]"""))
        assertEquals(2, links.length())
        val tabs = LegacyMerge.newTabs(listOf("https://c.com/"), listOf(
            LegacyMerge.Tab("https://c.com/", "C", null, false), LegacyMerge.Tab("https://e.com/", "E", null, false),
            LegacyMerge.Tab("https://e.com/", "E2", null, false)))
        assertEquals(listOf("https://e.com/"), tabs.map { it.url })
    }

    @Test fun keysAlreadySetInTheNewAppAreKept() {
        val apply = LegacyMerge.secretsToApply(mapOf("deepgram_key" to true, "deepl_key" to false), mapOf("deepgram_key" to "old", "deepl_key" to "dl"))
        assertEquals(mapOf("deepl_key" to "dl"), apply)
        assertTrue(apply.isNotEmpty()); assertFalse(apply.containsKey("deepgram_key"))
    }
}
