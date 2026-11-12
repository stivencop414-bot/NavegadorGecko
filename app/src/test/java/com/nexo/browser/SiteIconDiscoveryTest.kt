package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteIconDiscoveryTest {
    @Test fun prefersSharpDeclaredIconsAndKeepsFallbacks() {
        val html = """
            <html><head>
            <link rel="icon" href="/favicon-16.png" sizes="16x16">
            <link rel='apple-touch-icon' href='https://cdn.example.net/touch.png'>
            <link rel="icon" type="image/svg+xml" href="/logo.svg">
            <link rel="shortcut icon" href="img/fav.ico">
            <link rel="icon" href="//static.example.com/icon-192.png" sizes="192x192">
            </head><body><link rel="icon" href="/ignored-after-head.png"></body></html>
        """.trimIndent()
        val list = SiteIconDiscovery.candidates(html, "https://www.example.com/start/")
        assertEquals("https://cdn.example.net/touch.png", list[0])
        assertEquals("https://static.example.com/icon-192.png", list[1])
        assertTrue(list.contains("https://www.example.com/start/img/fav.ico"))
        assertFalse(list.any { it.endsWith(".svg") || it.contains("ignored") })
        assertTrue(list.contains("https://www.example.com/favicon.ico"))
    }

    @Test fun ignoresInsecureAndDataIcons() {
        val html = """<link rel="icon" href="http://example.com/a.png"><link rel="icon" href="data:image/png;base64,AA">"""
        val list = SiteIconDiscovery.candidates(html, "https://example.com/")
        assertEquals(listOf("https://example.com/apple-touch-icon.png", "https://example.com/favicon.ico"), list)
    }

    @Test fun redirectsStayOnTheSameSite() {
        assertTrue(SiteIconDiscovery.sameSite("reddit.com", "www.reddit.com"))
        assertFalse(SiteIconDiscovery.sameSite("example.com", "evil.net"))
    }
}
