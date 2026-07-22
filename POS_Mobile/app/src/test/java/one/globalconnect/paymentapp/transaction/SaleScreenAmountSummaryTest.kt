package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaleScreenAmountSummaryTest {
    @Test
    fun summarySubtractsTax1DiscountFromConfirmationTotal() {
        val summary = buildAmountConfirmationSummary(
            baseAmount = "20.00",
            tax1Amount = "4.00",
            tax2Amount = "0.00",
            tipAmount = "0.00",
            promptConfig = AmountPromptConfig(
                askAmount = true,
                askTax1 = true,
                askTax2 = false,
                askTip = false,
                tax1ZeroAmountAllowed = false,
                tax1DiscountPercentage = 8.0,
            ),
        )

        assertEquals("20.00", summary.baseAmountText)
        assertEquals("4.00", summary.tax1AmountText)
        assertEquals("0.32", summary.tax1DiscountAmountText)
        assertEquals("23.68", summary.totalAmountText)
    }

    @Test
    fun summaryHidesOptionalZeroTaxAndTipLines() {
        val summary = buildAmountConfirmationSummary(
            baseAmount = "25.00",
            tax1Amount = "0.00",
            tax2Amount = "0.00",
            tipAmount = "0.00",
            promptConfig = AmountPromptConfig(
                askAmount = true,
                askTax1 = true,
                askTax2 = true,
                askTip = true,
                tax1ZeroAmountAllowed = true,
            ),
        )

        assertNull(summary.tax1AmountText)
        assertNull(summary.tax1DiscountAmountText)
        assertNull(summary.tax2AmountText)
        assertNull(summary.tipAmountText)
        assertEquals("25.00", summary.totalAmountText)
    }
}
