package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PartialApprovalContractTest {
    @Test
    fun `parses ISO field 4 minor units`() {
        assertEquals(BigDecimal("125.00"), PartialApprovalContract.parseApprovedAmount("000000012500"))
        assertEquals(BigDecimal("125.00"), PartialApprovalContract.parseApprovedAmount("125.00"))
        assertNull(PartialApprovalContract.parseApprovedAmount(null))
    }

    @Test
    fun `splits simple transaction into approved and remaining amounts`() {
        val allocation = PartialApprovalContract.allocate(
            original = amounts(base = "250.00"),
            approvedTotal = BigDecimal("125.00"),
        )

        assertNotNull(allocation)
        assertEquals(BigDecimal("125.00"), allocation!!.approved.base)
        assertEquals(BigDecimal("125.00"), allocation.approved.total)
        assertEquals(BigDecimal("125.00"), allocation.remaining.total)
    }

    @Test
    fun `proportionally splits taxes discount and tip with exact totals`() {
        val original = amounts(
            base = "83.27",
            tax1 = "10.83",
            tax1Discount = "2.17",
            tax2 = "1.90",
            tip = "7.00",
        )
        val allocation = PartialApprovalContract.allocate(original, BigDecimal("51.37"))!!

        assertEquals(BigDecimal("51.37"), allocation.approved.total)
        assertEquals(BigDecimal("51.63"), allocation.remaining.total)
        assertEquals(original.base, allocation.approved.base.add(allocation.remaining.base))
        assertEquals(original.tax1, allocation.approved.tax1.add(allocation.remaining.tax1))
        assertEquals(original.tax1Discount, allocation.approved.tax1Discount.add(allocation.remaining.tax1Discount))
        assertEquals(original.tax2, allocation.approved.tax2.add(allocation.remaining.tax2))
        assertEquals(original.tip, allocation.approved.tip.add(allocation.remaining.tip))
        assertTrue(allocation.approved.tip > BigDecimal.ZERO)
    }

    @Test
    fun `rejects invalid partial amounts`() {
        val original = amounts(base = "100.00")
        assertNull(PartialApprovalContract.allocate(original, BigDecimal.ZERO))
        assertNull(PartialApprovalContract.allocate(original, BigDecimal("100.00")))
        assertNull(PartialApprovalContract.allocate(original, BigDecimal("101.00")))
    }

    private fun amounts(
        base: String,
        tax1: String = "0.00",
        tax1Discount: String = "0.00",
        tax2: String = "0.00",
        tip: String = "0.00",
    ) = PartialApprovalAmounts.fromStrings(base, tax1, tax1Discount, tax2, tip)
}
