package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailTmClientTest {
    @Test fun readsCollectionsInEveryApiFormat() {
        val classic = MailTmClient.parse("""{"hydra:member":[{"domain":"a.com"}],"hydra:totalItems":1}""")
        val modern = MailTmClient.parse("""{"member":[{"domain":"b.com"}],"totalItems":1}""")
        val plain = MailTmClient.parse("""  [{"domain":"c.com"}]""")
        assertEquals("a.com", MailTmClient.members(classic).getJSONObject(0).getString("domain"))
        assertEquals("b.com", MailTmClient.members(modern).getJSONObject(0).getString("domain"))
        assertEquals("c.com", MailTmClient.members(plain).getJSONObject(0).getString("domain"))
        assertEquals(0, MailTmClient.members(MailTmClient.parse("{}")).length())
    }

    @Test fun findsVerificationCodesButNotYears() {
        assertEquals("482913", MailTmClient.verificationCode("Tu código es 482913"))
        assertEquals("AB1-C2D", MailTmClient.verificationCode("Code: AB1-C2D"))
        assertEquals("7731", MailTmClient.verificationCode("© 2026 Ejemplo", "Use 7731 to sign in"))
        assertNull(MailTmClient.verificationCode("Bienvenido a 2026", "sin código"))
        assertNull(MailTmClient.verificationCode("pedido-123456789"))
    }
}
