package com.ejemplo.navegador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchResolverTest {
    @Test
    fun domainsUseHttps() {
        assertEquals("https://example.com", SearchResolver.addressFor("example.com"))
        assertEquals("https://es.wikipedia.org/wiki/Gecko", SearchResolver.addressFor("es.wikipedia.org/wiki/Gecko"))
        assertEquals("https://example.com:8443/a?b#c", SearchResolver.addressFor("example.com:8443/a?b#c"))
        assertEquals("https://mi-sitio.co", SearchResolver.addressFor("mi-sitio.co"))
    }

    @Test
    fun localNetworkUsesHttp() {
        assertEquals("http://192.168.1.1", SearchResolver.addressFor("192.168.1.1"))
        assertEquals("http://10.0.0.2:8080/admin", SearchResolver.addressFor("10.0.0.2:8080/admin"))
        assertEquals("http://localhost:3000", SearchResolver.addressFor("localhost:3000"))
        assertEquals("http://LOCALHOST", SearchResolver.addressFor("LOCALHOST"))
    }

    @Test
    fun wordsAndNumbersAreSearches() {
        assertNull(SearchResolver.addressFor("3.14"))
        assertNull(SearchResolver.addressFor("como hacer pan"))
        assertNull(SearchResolver.addressFor("navegador"))
        assertNull(SearchResolver.addressFor("1.2.3"))
    }
}
