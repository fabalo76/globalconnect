package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Tax1DiscountCalculatorTest {
    @Test
    fun calculate_zeroPercentageKeepsTaxDiscountDisabled() {
        val result = Tax1DiscountCalculator.calculate("15.00", 0.0)

        assertEquals("15.00", result.originalTaxAmountText)
        assertEquals("15.00", result.discountedTaxAmountText)
        assertEquals("0.00", result.discountAmountText)
        assertFalse(result.hasDiscount)
    }

    @Test
    fun calculate_discountIsTakenFromOriginalTaxAndSubtracted() {
        val result = Tax1DiscountCalculator.calculate("15.00", 8.0)

        assertEquals("15.00", result.originalTaxAmountText)
        assertEquals("13.80", result.discountedTaxAmountText)
        assertEquals("1.20", result.discountAmountText)
        assertTrue(result.hasDiscount)
    }

    @Test
    fun calculate_moreThanTwoDecimalsRoundsDown() {
        val result = Tax1DiscountCalculator.calculate("1.00", 8.875)

        assertEquals("1.00", result.originalTaxAmountText)
        assertEquals("0.92", result.discountedTaxAmountText)
        assertEquals("0.08", result.discountAmountText)
    }

    @Test
    fun originalTaxAmountFromDiscounted_rebuildsOriginalTaxForBatchUpload() {
        val result = Tax1DiscountCalculator.originalTaxAmountFromDiscounted(
            discountedTaxAmount = "13.80",
            discountAmount = "1.20",
        )

        assertEquals("15.00", result)
    }
}
