package one.globalconnect.paymentapp.uicpos.pos.host

import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.transaction.toTransactionString
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckOutTransactionConfigTest {
    @Test
    fun checkOutUsesOnline0200Mapping() {
        val config = requireNotNull(
            TransactionConfigRegistry.configFor(
                TransactionType.CHECKOUT.toTransactionString(),
            ),
        )

        assertEquals("0200", config.messageType)
        assertEquals("000000", config.processingCode)
    }
}
