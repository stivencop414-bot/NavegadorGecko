package com.nexo.browser

import org.junit.Assert.*
import org.junit.Test

class OmniboxIndexTest {
    @Test fun matchesAccentsRanksHostsAndLimitsRows() {
        val index = OmniboxIndex(listOf(
            HistoryEntry("https://other.test/", "Noticias de México", 100),
            HistoryEntry("https://mexico.test/", "Portada", 10),
            HistoryEntry("https://third.test/", "México noticias", 200),
            HistoryEntry("https://fourth.test/", "México noticias", 50),
            HistoryEntry("https://mexico.test/", "Duplicado", 0)))
        val results = index.find("MEXICO")
        assertEquals(3, results.size)
        assertEquals("https://mexico.test/", results[0].url)
        assertEquals("https://third.test/", results[1].url)
        assertEquals(3, index.find("méxico noticias").size)
        assertTrue(index.find("").isEmpty())
    }
    @Test fun neverSendsAddressesOrCredentialsForCompletion() {
        listOf("https://secret.test", "router.local", "127.0.0.1", "localhost", "me@example.com",
            "token abc", "API key abc", "secret=value", "foo/bar", "a", "hello\nworld").forEach {
            assertFalse(it, OmniboxIndex.canQueryRemotely(it))
        }
        assertTrue(OmniboxIndex.canQueryRemotely("recetas fáciles"))
    }
    @Test fun providerOutputIsBoundedDeduplicatedAndPlainText() {
        val result = OmniboxSuggestions.parse("""["hola",["hola","hola mundo","HOLA MUNDO","hola Colombia","otra"]]""", "hola")
        assertEquals(listOf(OmniboxResult.Search("hola mundo"), OmniboxResult.Search("hola Colombia")), result)
    }
}
