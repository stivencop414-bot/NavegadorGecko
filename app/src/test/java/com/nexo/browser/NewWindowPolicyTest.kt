package com.nexo.browser
import org.junit.Assert.*
import org.junit.Test
class NewWindowPolicyTest {
    @Test fun requiresGestureAndConsumesOnce() {
        val p = NewWindowPolicy()
        assertFalse(p.authorize("https://a.com", false, 1000))
        assertFalse(p.consume("https://a.com", 1000))
        assertTrue(p.authorize("https://a.com", true, 1000))
        assertTrue(p.consume("https://a.com", 1100))
        assertFalse(p.consume("https://a.com", 1101))
        assertFalse(p.authorize("https://ad.com", true, 1200))
    }
    @Test fun rejectsExpiredAndMismatchedRequests() {
        val p = NewWindowPolicy()
        p.authorize("https://a.com", true, 1000)
        assertFalse(p.consume("https://b.com", 1001))
        p.authorize("https://a.com", true, 1000)
        assertFalse(p.consume("https://a.com", 4000))
        assertTrue(p.authorize("https://a.com", true, 5000))
        assertTrue(p.consume("", 5100))
    }
}
