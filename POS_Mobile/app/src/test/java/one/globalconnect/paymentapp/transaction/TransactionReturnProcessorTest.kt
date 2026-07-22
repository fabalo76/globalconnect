package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionReturnProcessorTest {

    @Test
    fun `sale void keeps 0200 and changes processing prefix from 00 to 02`() {
        val spec = resolveReturnMessageSpec(TransactionType.SALE, ReturnAction.VOID)

        assertEquals(ReturnAction.VOID, spec.action)
        assertEquals("0200", spec.messageType)
        assertEquals("020000", spec.processingCode)
    }

    @Test
    fun `cash void keeps 0200 and changes processing prefix from 01 to 03`() {
        val spec = resolveReturnMessageSpec(TransactionType.CASH, ReturnAction.VOID)

        assertEquals(ReturnAction.VOID, spec.action)
        assertEquals("0200", spec.messageType)
        assertEquals("030000", spec.processingCode)
    }

    @Test
    fun `refund void preserves remaining processing code digits`() {
        val spec = resolveReturnMessageSpec(TransactionType.REFUND, ReturnAction.VOID)

        assertEquals(ReturnAction.VOID, spec.action)
        assertEquals("0200", spec.messageType)
        assertEquals("220000", spec.processingCode)
    }

    @Test
    fun `authorization void becomes 0400 reversal with original processing code`() {
        val spec = resolveReturnMessageSpec(TransactionType.AUTHONLY, ReturnAction.VOID)

        assertEquals(ReturnAction.REVERSAL, spec.action)
        assertEquals("0400", spec.messageType)
        assertEquals("000000", spec.processingCode)
    }

    @Test
    fun `checkin void becomes 0400 reversal with original processing code`() {
        val spec = resolveReturnMessageSpec(TransactionType.CHECKIN, ReturnAction.VOID)

        assertEquals(ReturnAction.REVERSAL, spec.action)
        assertEquals("0400", spec.messageType)
        assertEquals("000000", spec.processingCode)
    }
}
