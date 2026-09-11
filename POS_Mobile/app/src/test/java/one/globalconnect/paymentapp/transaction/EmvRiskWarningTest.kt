package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmvRiskWarningTest {

    @Test
    fun `MCD02 TVR reports expired application`() {
        assertTrue(hasExpiredApplicationTvr("00C0008001"))
    }

    @Test
    fun `TVR without byte 2 bit 7 is not expired`() {
        assertFalse(hasExpiredApplicationTvr("0080008001"))
    }

    @Test
    fun `malformed TVR does not show an expired warning`() {
        assertFalse(hasExpiredApplicationTvr("00C0"))
        assertFalse(hasExpiredApplicationTvr("not-a-tvr"))
    }
}
