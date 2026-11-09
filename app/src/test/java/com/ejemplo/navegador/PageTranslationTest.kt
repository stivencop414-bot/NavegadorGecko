package com.ejemplo.navegador

import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class PageTranslationTest {
    @Test fun encodesOnePublicUrlWithoutLosingQueryOrFragment() {
        val original = "https://example.com/article?q=hello%20world&lang=en#section"
        val result = PageTranslation.urlFor(original, "fr")!!
        assertTrue(result.startsWith("https://translate.google.com/translate?sl=auto&tl=fr"))
        assertEquals(original, URLDecoder.decode(result.substringAfter("&u="), "UTF-8"))
    }

    @Test fun targetCannotInjectExtraQueryParameters() {
        val result = PageTranslation.urlFor("https://example.com/", "es&u=https://evil.com")!!
        assertTrue(result.contains("&tl=es&"))
        assertFalse(result.contains("evil.com"))
    }

    @Test fun rejectsInternalUrlsCredentialsAndLocalNetworkBeforeSharing() {
        listOf("about:blank", "moz-extension://id/options.html", "file:///data/secret", "javascript:alert(1)",
            "https://user:password@example.com/", "http://localhost/", "http://192.168.1.1/", "http://[::1]/",
            "http://printer.local/", "http://router.home.arpa/", "http://printer.lan/",
            "http://router.localdomain/", "http://intranet/", "https://example.com/?access_token=secret",
            "https://example.com/#token=secret", "https://example.com/?%73ession_id=secret",
            "https://example.com/path\\nmore").forEach {
            assertFalse(it, UrlSafety.isPublicTranslationCandidate(it))
            assertNull(it, PageTranslation.urlFor(it, "es"))
        }
    }

    @Test fun recognizesOnlyRealTranslationOrigins() {
        assertTrue(PageTranslation.isTranslationPage("https://example-com.translate.goog/?x=1"))
        assertTrue(PageTranslation.isTranslationPage("https://translate.google.com/translate"))
        assertFalse(PageTranslation.isTranslationPage("https://translate.google.com.evil.example/"))
    }
}
