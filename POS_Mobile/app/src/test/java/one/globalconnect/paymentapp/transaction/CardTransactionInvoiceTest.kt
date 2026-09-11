package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Test

class CardTransactionInvoiceTest {
    @Test
    fun cardRetriesAndHostUseTheSameInvoice() {
        var counter = 7
        val invoice = CardTransactionInvoice { (++counter).toString().padStart(6, '0') }
        assertEquals("000008", invoice.forCardRead())
        assertEquals("000008", invoice.forCardRead())
        assertEquals("000008", invoice.forHostRequest())
        assertEquals(8, counter)
    }

    @Test
    fun nextAuthorizationGetsANewInvoice() {
        var counter = 7
        val invoice = CardTransactionInvoice { (++counter).toString().padStart(6, '0') }
        invoice.forCardRead()
        invoice.forHostRequest()
        assertEquals("000009", invoice.forCardRead())
        assertEquals("000009", invoice.forHostRequest())
    }

    @Test(expected = IllegalStateException::class)
    fun hostCannotAllocateADifferentInvoiceAfterEmv() {
        CardTransactionInvoice { "000008" }.forHostRequest()
    }
}
