package one.globalconnect.paymentapp.transaction

import one.globalconnect.paymentapp.transaction.installments.ExtrasBalance
import org.junit.Assert.*
import org.junit.Test

class ExtrasBalanceTest {
    @Test fun parsesHostSampleAndZero() {
        assertEquals("5090000.00", ExtrasBalance.parse("000509000000"))
        assertEquals("0.00", ExtrasBalance.parse("000000000000"))
        assertEquals("9999999999.99", ExtrasBalance.parse("999999999999"))
    }
    @Test fun missingOrInvalidAmountsUseFallback() {
        for (value in listOf(null, "", "00000000001", "0000000000000", "000000001.00", "-00000000001", " 00000000000")) {
            assertNull(ExtrasBalance.parse(value))
        }
    }
}
