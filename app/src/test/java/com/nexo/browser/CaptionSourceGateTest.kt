package com.nexo.browser

import org.junit.Assert.*
import org.junit.Test

class CaptionSourceGateTest {
    @Test fun permissionActivityResumeAndLateIframeMessagesCannotReclaimAndroidCapture() {
        val gate = CaptionSourceGate()
        assertTrue(gate.acceptsPage)
        gate.requestAndroid()
        // The permission Activity has not returned and no service exists yet.
        assertTrue(gate.pending)
        assertFalse(gate.acceptsPage)
        // Native onResume and new iframe connections must obey the same gate.
        assertTrue(gate.startAndroid())
        assertFalse(gate.pending)
        assertFalse(gate.acceptsPage)
        assertFalse(gate.startAndroid())
    }
    @Test fun cancelledConsentCanBeRequestedAgainWithoutReusingTheGrant() {
        val gate = CaptionSourceGate()
        assertFalse(gate.startAndroid())
        gate.requestAndroid(); gate.reset()
        assertFalse(gate.startAndroid())
        gate.requestAndroid()
        assertTrue(gate.startAndroid())
    }
}
