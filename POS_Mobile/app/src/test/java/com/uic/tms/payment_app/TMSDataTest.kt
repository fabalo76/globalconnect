package com.uic.tms.payment_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TMSDataTest {
    @Test
    fun terminal_enablesTaxDiscountWhenPercentageIsGreaterThanZero() {
        val terminal = TMS_Terminal(tax1DiscountPercentage = 8.0)

        assertEquals(8.0, terminal.tax1DiscountPercentage, 0.0)
        assertEquals(8.0, terminal.TaxDiscount, 0.0)
        assertTrue(terminal.ApplyTaxDisc)
    }

    @Test
    fun terminal_disablesTaxDiscountWhenPercentageIsZero() {
        val terminal = TMS_Terminal(tax1DiscountPercentage = 0.0)

        assertEquals(0.0, terminal.tax1DiscountPercentage, 0.0)
        assertEquals(0.0, terminal.TaxDiscount, 0.0)
        assertFalse(terminal.ApplyTaxDisc)
    }
}
