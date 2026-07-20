package one.globalconnect.pinpad.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinpadTransactionDisplayTest {
    @Test
    fun parsesZaTransactionDisplayWithCustomSymbol() {
        val fs = '\u001C'
        val display = PinpadTransactionDisplay.parseZaPayload("00${fs}100${fs}0840${fs}USD")

        assertEquals("00", display?.transactionTypeCode)
        assertEquals("0840", display?.currencyCode)
        assertEquals(100L, display?.amountMinor)
        assertEquals("USD", display?.currencySymbol)
        assertEquals("USD", display?.displayCurrency)
        assertEquals("1.00", display?.amountText)
        assertEquals("000000000100", display?.emvAmount)
    }

    @Test
    fun parsesCompactTransactionDisplay() {
        val display = PinpadTransactionDisplay.parseCompact("000840100")

        assertEquals("00", display?.transactionTypeCode)
        assertEquals("0840", display?.currencyCode)
        assertEquals(100L, display?.amountMinor)
        assertEquals("1.00", display?.amountText)
        assertEquals("000000000100", display?.emvAmount)
    }

    @Test
    fun rejectsPlainEmvAmountAsCompactDisplay() {
        assertNull(PinpadTransactionDisplay.parseCompact("000000004000"))
    }
}
