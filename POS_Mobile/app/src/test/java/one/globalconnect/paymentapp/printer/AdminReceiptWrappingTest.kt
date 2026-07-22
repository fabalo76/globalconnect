package one.globalconnect.paymentapp.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminReceiptWrappingTest {
    @Test
    fun wrapPrintableFieldValue_rebalancesShortFinalWord() {
        val lines = NexGoPaymentPrinter.wrapPrintableFieldValue(
            text = "POS admin request: paper supply",
            firstLineWidth = 30,
            continuationWidth = 38,
        )

        assertEquals(listOf("POS admin request:", "paper supply"), lines)
    }

    @Test
    fun wrapPrintableFieldValue_rebalancesShortFinalChunk() {
        val lines = NexGoPaymentPrinter.wrapPrintableFieldValue(
            text = "f55c1a00-e7e4-467d-ba8c-30ddbbe7c062",
            firstLineWidth = 30,
            continuationWidth = 38,
        )

        assertTrue(lines.last().length >= 8)
        assertEquals("f55c1a00-e7e4-467d-ba8c-30ddbbe7c062", lines.joinToString(""))
    }
}
