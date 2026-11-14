package com.nexo.browser

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ServiceSecurityTest {
    @Test fun historyRemovesAuthorityCredentialsAndEncodedTokens() {
        assertEquals("https://example.com/path?lang=es", UrlSafety.redactSensitiveQuery(
            "https://user:p%40ss@example.com/path?%74oken=abc&lang=es#access_token=xyz"))
        assertEquals("https://example.com/", UrlSafety.redactSensitiveQuery("https://a@b@example.com/"))
    }
    @Test fun serviceUrlsRejectCredentialsCleartextAndFragments() {
        listOf("http://api.test", "https://user:key@api.test/", "https://api.test/#x", "file:///x", "https:opaque")
            .forEach { assertFalse(it, UrlSafety.isSecureServiceUrl(it)) }
        assertTrue(UrlSafety.isSecureServiceUrl("https://api.test:8443/v1"))
    }
    @Test(expected = IllegalArgumentException::class) fun aiRejectsUnsafeEndpointAtExecution() {
        CaptionProtocol.aiRequest("custom", "http://api.test", "model", "secret", "Spanish", listOf("hi"), emptyList())
    }
    @Test fun limitsResponseBodies() {
        assertEquals("hello", "hello".toResponseBody("text/plain".toMediaType()).boundedText(5))
        assertThrows(java.io.IOException::class.java) {
            "too large".toResponseBody("text/plain".toMediaType()).boundedText(3)
        }
    }
}
