package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoyaltyContractTest {
    @Test
    fun `balance response amount is interpreted as integer points`() {
        assertEquals("99990000", LoyaltyContract.parseBalancePoints("000099990000"))
        assertEquals("99,990,000", LoyaltyContract.formatPoints("99990000"))
    }

    @Test
    fun `missing or malformed balance is rejected`() {
        assertNull(LoyaltyContract.parseBalancePoints(null))
        assertNull(LoyaltyContract.parseBalancePoints("12.34"))
    }
}
