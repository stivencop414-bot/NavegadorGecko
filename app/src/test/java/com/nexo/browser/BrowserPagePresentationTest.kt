package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserPagePresentationTest {
    @Test fun keepsMultiLabelHostsDistinctInsteadOfGuessingTheirPublicSuffix() {
        assertEquals("google.co.uk", BrowserPagePresentation.shortcut("https://www.google.co.uk/")?.title)
        assertEquals("bbc.co.uk", BrowserPagePresentation.shortcut("https://www.bbc.co.uk/")?.title)
        assertEquals("news.example.com", BrowserPagePresentation.shortcut("https://news.example.com/")?.title)
    }

    @Test fun internalPagesNeverBecomeQuickLinks() {
        listOf("moz-extension://1234/options/options.html", "about:blank", "resource://android/assets/page.html", "file:///tmp/page.html")
            .forEach { assertNull(it, BrowserPagePresentation.shortcut(it)) }
    }

    @Test fun shortcutsKeepTheActualOriginAndNeverExposeCredentials() {
        val link = BrowserPagePresentation.shortcut("http://user:password@m.example.com:8080/path?q=secret#fragment")!!
        assertEquals("http://m.example.com:8080/", link.origin)
        assertEquals("Example", link.title)
    }

    @Test fun separatePortsRemainSeparateServices() {
        val first = BrowserPagePresentation.shortcut("http://localhost:8080/a")!!
        val second = BrowserPagePresentation.shortcut("http://localhost:9090/a")!!
        assertNotEquals(first.identity, second.identity)
    }

    @Test fun mobileAndDesktopHostsShareOneShortcutWithoutRewritingTheDestination() {
        val mobile = BrowserPagePresentation.shortcut("https://m.youtube.com/watch?v=1")!!
        val desktop = BrowserPagePresentation.shortcut("https://www.youtube.com/watch?v=2")!!
        assertEquals(mobile.identity, desktop.identity)
        assertEquals("https://m.youtube.com/", mobile.origin)
    }
    @Test fun extensionLabelsUseInstalledIdentityRatherThanTechnicalIds() {
        val known = listOf("moz-extension://trusted-id/options/options.html" to "PhantomShield")
        assertEquals("PhantomShield · Extensión", BrowserPagePresentation.extensionLabel("moz-extension://trusted-id/options/options.html", known))
        assertEquals("Página de extensión", BrowserPagePresentation.extensionLabel("moz-extension://unknown-id/options/options.html", known))
    }

    @Test fun aLookalikeAuthorityCannotBorrowAnotherExtensionsName() {
        val known = listOf("moz-extension://trusted-id/options/options.html" to "PhantomShield")
        assertEquals("Página de extensión", BrowserPagePresentation.extensionLabel("moz-extension://trusted-id.evil/options/options.html", known))
        assertEquals("Página de extensión", BrowserPagePresentation.extensionLabel("moz-extension://user@trusted-id/options/options.html", known))
    }

    @Test fun ordinaryWebAddressesNeverGetAnExtensionIdentityLabel() {
        val known = listOf("moz-extension://trusted-id/options/options.html" to "PhantomShield")
        assertNull(BrowserPagePresentation.extensionLabel("https://trusted-id/options/options.html", known))
    }
}
