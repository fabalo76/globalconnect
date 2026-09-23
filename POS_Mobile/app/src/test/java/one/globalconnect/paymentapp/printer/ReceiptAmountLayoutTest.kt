package one.globalconnect.paymentapp.printer

import org.junit.Assert.*
import org.junit.Test

class ReceiptAmountLayoutTest {
    @Test fun simpleSaleOmitsTotalButEachAdditionalLineRequiresIt() {
        assertFalse(ReceiptAmountLayout.needsTotal(false, false, false, false))
        // tax1 includes the mandatory-zero tax line.
        assertTrue(ReceiptAmountLayout.needsTotal(true, false, false, false))
        assertTrue(ReceiptAmountLayout.needsTotal(false, true, false, false))
        assertTrue(ReceiptAmountLayout.needsTotal(false, false, true, false))
        assertTrue(ReceiptAmountLayout.needsTotal(false, false, false, true))
    }
    @Test fun preservesOneSpaceAndSplitsOnlyBeyondLineCapacity() {
        assertFalse(ReceiptAmountLayout.splitAmountLine("Venta", "Lps101.84", 25))
        assertFalse(ReceiptAmountLayout.splitAmountLine("1234567890", "12345678901234", 25))
        assertTrue(ReceiptAmountLayout.splitAmountLine("12345678901", "12345678901234", 25))
        assertTrue(ReceiptAmountLayout.splitAmountLine("Venta en cuotas", "Lps5,090,000.00", 25))
    }
}
