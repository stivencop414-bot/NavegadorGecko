package com.ejemplo.navegador
import org.junit.Assert.*
import org.junit.Test
class TabIsolationTest {
    @Test fun groupNamesAreBoundedAndDoNotChangeIdentity() {
        assertNull(TabGroupRules.name(" \n "))
        assertEquals("Trabajo",TabGroupRules.name("  Tra\nbajo  "))
        assertEquals(40,TabGroupRules.name("a".repeat(100))!!.length)
        val tab=BrowserTab(url="about:blank",isPrivate=true,burnerId="burner-test",groupName="Uno")
        tab.groupName=TabGroupRules.name("Dos")
        assertEquals("burner-test",tab.burnerId);assertTrue(tab.isPrivate)
    }
    @Test(expected=IllegalArgumentException::class) fun burnerCannotBecomeAPersistableNormalTab() {
        BrowserTab(url="https://example.com",burnerId="identity",isPrivate=false)
    }
    @Test fun validatesNetworkAndDnsScopeData() {
        assertEquals(9050,TabNetworkProfile("socks","127.0.0.1",9050).validated().port)
        for(profile in listOf(TabNetworkProfile("socks","host",0),TabNetworkProfile(latency=2001),TabNetworkProfile(latitude=91.0,longitude=0.0))) {
            try { profile.validated();fail("must reject invalid profile") } catch(_:IllegalArgumentException) {}
        }
        assertTrue(DnsProfile.valid("https://dns.example.com/dns-query"))
        assertFalse(DnsProfile.valid("http://pi.hole/dns-query"))
        assertFalse(DnsProfile.valid("https://user:pass@dns.example.com/dns-query"))
        assertFalse(DnsProfile.valid("https://dns.example.com/dns-query?token=secret"))
    }
}

class TabGroupColorTest {
    @Test fun groupColorIsStableAndCaseInsensitive() {
        assertEquals(TabGroupRules.color("Trabajo"),TabGroupRules.color("trabajo"))
        assertEquals(TabGroupRules.color("Trabajo"),TabGroupRules.color(" Trabajo "))
        assertEquals(0xFF.toLong(),(TabGroupRules.color(null).toLong() ushr 24) and 0xFF)
    }
}
