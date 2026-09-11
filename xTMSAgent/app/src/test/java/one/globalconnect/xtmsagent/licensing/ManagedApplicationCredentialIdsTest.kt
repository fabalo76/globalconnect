package one.globalconnect.xtmsagent.licensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedApplicationCredentialIdsTest {
    @Test
    fun `record identity is stable across harmless casing and whitespace changes`() {
        val first = ManagedApplicationCredentialIds.recordId(
            "PINPAD_APP",
            "one.globalconnect.pinpad",
        )
        val second = ManagedApplicationCredentialIds.recordId(
            " pinpad_app ",
            "One.GlobalConnect.Pinpad ",
        )

        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test
    fun `different managed applications receive different aliases`() {
        val pinpad = ManagedApplicationCredentialIds.alias(
            "PINPAD_APP",
            "one.globalconnect.pinpad",
        )
        val payment = ManagedApplicationCredentialIds.alias(
            "PAYMENT_APP",
            "one.globalconnect.paymentapp",
        )

        assertNotEquals(pinpad, payment)
        assertTrue(pinpad.matches(Regex("gc-app-[0-9a-f]{32}")))
    }
}
