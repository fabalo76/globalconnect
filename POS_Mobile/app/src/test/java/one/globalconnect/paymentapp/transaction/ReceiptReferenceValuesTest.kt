package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptReferenceValuesTest {

    @Test
    fun blankValues_areOmittedFromReceipt() {
        val values = resolveReceiptReferenceValues(
            folioNumber = "  ",
            externalReferenceNumber = "\t",
        )

        assertNull(values.folioNumber)
        assertNull(values.externalReferenceNumber)
    }

    @Test
    fun presentValues_areTrimmedAndReturned() {
        val values = resolveReceiptReferenceValues(
            folioNumber = " 000123 ",
            externalReferenceNumber = " EXT-456 ",
        )

        assertEquals("000123", values.folioNumber)
        assertEquals("EXT-456", values.externalReferenceNumber)
    }
}
