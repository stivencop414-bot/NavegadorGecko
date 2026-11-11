package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {
    @Test
    fun schemeIgnoresWhitespaceAndCase() {
        assertEquals("javascript", UrlSafety.scheme("  JavaScript:alert(1)"))
        assertEquals("javascript", UrlSafety.scheme("java\tscript:alert(1)"))
        assertEquals("javascript", UrlSafety.scheme("\u0001javascript:alert(1)"))
        assertEquals("https", UrlSafety.scheme("https://example.com"))
        assertNull(UrlSafety.scheme("example.com/path"))
    }

    @Test
    fun appNavigationBlocksDangerousSchemes() {
        listOf(
            "javascript:alert(1)",
            " javascript:alert(1)",
            "jav\nascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///sdcard/secret.txt",
            "content://com.example/x",
            "intent://scan#Intent;scheme=zxing;end"
        ).forEach { assertFalse(it, UrlSafety.isAppNavigable(it)) }

        listOf(
            "https://example.com",
            "http://example.com",
            "about:blank",
            "moz-extension://abc/options.html"
        ).forEach { assertTrue(it, UrlSafety.isAppNavigable(it)) }
    }

    @Test
    fun appLinksAreHandedToOtherAppsButCodeAndFilesNever() {
        listOf(
            "tel:+573001234567",
            "mailto:hola@example.com",
            "sms:123",
            "geo:4.6,-74.08",
            "market://details?id=org.example",
            "intent://scan#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "whatsapp://send?text=hola",
            " TEL:123"
        ).forEach { assertTrue(it, UrlSafety.isExternalAppUrl(it)) }

        listOf(
            "https://example.com",
            "http://example.com",
            "about:blank",
            "moz-extension://abc/popup.html",
            "javascript:alert(1)",
            "java\tscript:alert(1)",
            "data:text/html,x",
            "blob:https://example.com/123",
            "file:///sdcard/x",
            "content://media/1",
            "view-source:https://example.com",
            "resource://android/assets/x",
            "example.com/no-scheme",
            ""
        ).forEach { assertFalse(it, UrlSafety.isExternalAppUrl(it)) }
    }

    @Test
    fun externalIntentsOnlyOpenWebUrls() {
        assertTrue(UrlSafety.isExternallyOpenable("https://example.com"))
        assertTrue(UrlSafety.isExternallyOpenable("HTTP://example.com"))
        assertFalse(UrlSafety.isExternallyOpenable("about:config"))
        assertFalse(UrlSafety.isExternallyOpenable("resource://gre/modules/x.js"))
        assertFalse(UrlSafety.isExternallyOpenable("moz-extension://abc/page.html"))
        assertFalse(UrlSafety.isExternallyOpenable("javascript:alert(1)"))
    }

    @Test
    fun extensionTabsAllowOnlyWebAndExtensionPages() {
        assertTrue(UrlSafety.isExtensionTabUrl("about:blank"))
        assertTrue(UrlSafety.isExtensionTabUrl("moz-extension://abc/page.html"))
        assertTrue(UrlSafety.isExtensionTabUrl("https://example.com"))
        assertFalse(UrlSafety.isExtensionTabUrl("about:config"))
        assertFalse(UrlSafety.isExtensionTabUrl("javascript:alert(1)"))
        assertFalse(UrlSafety.isExtensionTabUrl("data:text/html,x"))
    }

    @Test
    fun redactsSensitiveQueryParameters() {
        assertEquals(
            "https://example.com/cb?lang=es",
            UrlSafety.redactSensitiveQuery(
                "https://example.com/cb?code=abc&lang=es&state=xyz&access_token=t"
            )
        )
        assertEquals(
            "https://example.com/",
            UrlSafety.redactSensitiveQuery("https://example.com/#access_token=abc&x=1")
        )
        assertEquals(
            "https://www.google.com/search?q=gecko#top",
            UrlSafety.redactSensitiveQuery("https://www.google.com/search?q=gecko#top")
        )
        assertEquals(
            "https://example.com/a",
            UrlSafety.redactSensitiveQuery("https://example.com/a?API_KEY=1&Session_Id=2")
        )
    }
}

class FileNamesTest {
    @Test
    fun sanitizeRemovesTraversalAndSeparators() {
        assertEquals("__etc_passwd", FileNames.sanitize("../etc/passwd"))
        assertEquals("a_b_c.txt", FileNames.sanitize("a/b\\c.txt"))
        assertEquals("hidden", FileNames.sanitize(".hidden"))
        assertEquals("descarga.bin", FileNames.sanitize("..."))
        assertEquals("x", FileNames.sanitize("x", fallback = "f"))
    }

    @Test
    fun sanitizeKeepsExtensionWhenTruncating() {
        val long = "a".repeat(300) + ".pdf"
        val result = FileNames.sanitize(long)
        assertEquals(120, result.length)
        assertTrue(result.endsWith(".pdf"))
    }

    @Test
    fun contentDispositionPrefersExtendedFilename() {
        assertEquals(
            "informe ñ.pdf",
            FileNames.fromContentDisposition(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''informe%20%C3%B1.pdf"
            )
        )
        assertEquals(
            "simple.zip",
            FileNames.fromContentDisposition("attachment; filename=simple.zip")
        )
        assertEquals(
            "with ; semicolon.txt",
            FileNames.fromContentDisposition("attachment; filename=\"with ; semicolon.txt\"")
        )
        assertNull(FileNames.fromContentDisposition("inline"))
        assertNull(FileNames.fromContentDisposition(null))
    }

    @Test
    fun fileNameFromUrl() {
        assertEquals("doc 1.pdf", FileNames.fromUrl("https://x.com/files/doc%201.pdf?sig=abc#p"))
        assertEquals("a+b.txt", FileNames.fromUrl("https://x.com/a+b.txt"))
        assertNull(FileNames.fromUrl("https://x.com/download"))
        assertNull(FileNames.fromUrl("https://x.com/"))
    }

    @Test
    fun percentDecodeHandlesEmojiAndBadEscapes() {
        assertEquals("😀%zz", FileNames.percentDecode("😀%zz", plusAsSpace = false))
        assertEquals("a b", FileNames.percentDecode("a+b", plusAsSpace = true))
    }
}
