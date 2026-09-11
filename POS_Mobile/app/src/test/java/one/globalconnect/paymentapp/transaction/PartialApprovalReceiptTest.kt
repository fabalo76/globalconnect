package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class PartialApprovalReceiptTest {
    @Test
    fun `approved transaction retains requested total for receipt and reprint`() {
        val transaction = Transaction(
            type = TransactionType.SALE,
            totalAmount = "125.00",
            baseAmount = "125.00",
            partialApprovalOriginalAmount = "250.00",
            ARC = "10",
        )

        assertEquals("250.00", transaction.partialApprovalOriginalAmount)
        assertEquals("125.00", transaction.totalAmount)
        val receipt = transaction.copy(id = 16).partialApprovalReceipt()!!
        assertEquals(BigDecimal("250.00"), receipt.originalAmount)
        assertEquals(BigDecimal("125.00"), receipt.approvedAmount)
    }

    @Test
    fun `full approvals and voids do not print partial approval banner`() {
        assertNull(Transaction(type = TransactionType.SALE, totalAmount = "250.00", ARC = "00").partialApprovalReceipt())
        assertNull(Transaction(type = TransactionType.SALE, totalAmount = "125.00",
            partialApprovalOriginalAmount = "250.00", ARC = "10", returnStatus = ReturnStatus.Voided).partialApprovalReceipt())
        assertNull(Transaction(type = TransactionType.REFUND, totalAmount = "125.00", ARC = "10").partialApprovalReceipt())
    }

    @Test
    fun `older partial approvals show approved total without inventing requested total`() {
        val receipt = Transaction(type = TransactionType.SALE, totalAmount = "125.00", ARC = "10").partialApprovalReceipt()
        assertNotNull(receipt)
        assertNull(receipt!!.originalAmount)
        assertEquals(BigDecimal("125.00"), receipt.approvedAmount)
    }

    @Test
    fun `subsequent partial receipt uses its own request rather than the first sale total`() {
        val receipt = Transaction(type = TransactionType.SALE, totalAmount = "60.00",
            partialApprovalOriginalAmount = "125.00").partialApprovalReceipt()!!
        assertEquals(BigDecimal("125.00"), receipt.originalAmount)
        assertEquals(BigDecimal("60.00"), receipt.approvedAmount)
    }
}
