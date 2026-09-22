package one.globalconnect.paymentapp.ecr

import one.globalconnect.paymentapp.transaction.*
import one.globalconnect.tms.paymentapp.*
import org.junit.Assert.*
import org.junit.Test

class EcrReportDataTest {
    private val database = TMSDATA(terminal = listOf(TMS_Terminal(acquirer = listOf(
        TMS_Acquirer(acquirer_id = "A", acquirerName = "Bank", currencyCode = 840, currencySymbol = "USD", terminalId = "T", merchantId = "M")))))
    private fun sale(id: Int = 1) = Transaction(id = id, invoiceId = id.toString().padStart(6, '0'),
        acquirerId = "A", type = TransactionType.SALE, totalAmount = "12.25", baseAmount = "12.25",
        localDateTime = "2026-09-19 12:30:00", cardEntryMethod = "EMV_CONTACTLESS",
        cardNumber = "1234567890123456", masked_cardNumber = "************3456")
    private fun request(command: String) = EcrMessage(command, fields = mapOf("80" to "report-1", "P1" to "0"))

    @Test fun validatesCommandsAndRejectsExtraOrMalformedFields() {
        listOf("P1", "P2", "P3").forEach { EcrReportData.validate(request(it)) }
        for (request in listOf(request("P4"), request("P1").copy(fields = mapOf("80" to "r", "65" to "bad")),
            request("P2").copy(fields = mapOf("80" to "r", "65" to "123")), request("P3").copy(more = true))) {
            assertThrows(IllegalArgumentException::class.java) { EcrReportData.validate(request) }
        }
    }
    @Test fun auditHasSeventeenFieldsMaskedPanAndVoidIdentity() {
        val rows = EcrReportData.reportFields(listOf(sale().copy(returnStatus = ReturnStatus.Voided)), database, true)
        val data = rows.mapNotNull { it["TD"] }.joinToString("")
        val parts = data.removeSuffix("^").split('|')
        assertEquals(17, parts.size)
        assertEquals("42", parts[1]); assertEquals("20", parts[2]); assertEquals("07", parts[3])
        assertEquals("************3456", parts[4]); assertEquals("1225", parts[10])
        assertFalse(data.contains(sale().cardNumber))
    }
    @Test fun totalsUseExistingBatchCalculationAndExcludeVoidedSales() {
        val fields = EcrReportData.reportFields(listOf(sale(), sale(2).copy(returnStatus = ReturnStatus.Voided)), database, false)
        val total = fields.mapNotNull { it["TT"] }.joinToString("")
        assertTrue(total.contains("SA001000000001225"))
        assertTrue(total.contains("VS001-00000001225"))
        assertFalse(fields.any { "TD" in it })
    }
    @Test fun largeAuditChunksRoundTripWithoutLoss() {
        val fields = EcrReportData.reportFields((1..150).map { sale(it) }, database, true)
        val frames = EcrReportData.frames(request("P2"), fields, false)
        assertTrue(frames.size > 100)
        frames.forEachIndexed { index, frame ->
            assertEquals(frame, EcrMessage.decode(frame.encode()))
            assertEquals(index < frames.lastIndex, frame.more)
            assertEquals(index.toString(), frame.fields["S1"])
            assertTrue(frame.fields.values.all { it.length <= 999 })
        }
        assertEquals(150, frames.mapNotNull { it.fields["TD"] }.joinToString("").count { it == '^' })
    }
    @Test fun reprintIsOneFrameAndIncludesNameAndTaxes() {
        val transaction = sale().copy(cardholderName = "TEST NAME", tax2Amount = "1.25")
        val frames = EcrReportData.frames(request("P1"), listOf(EcrReportData.receiptFields(transaction, database)), true)
        assertEquals(1, frames.size); assertFalse(frames.single().more)
        assertEquals("TEST NAME", frames.single().fields["82"])
        assertEquals("125", frames.single().fields["45"])
        assertEquals("1", frames.single().fields["P2"])
        assertEquals("00", frames.single().fields["00"])
    }
    @Test fun emptyBatchStillReturnsACompleteResponse() {
        val frames = EcrReportData.frames(request("P3"), EcrReportData.reportFields(emptyList(), database, false), false)
        assertFalse(frames.last().more); assertEquals("00", frames.last().response)
        assertTrue(frames.mapNotNull { it.fields["TT"] }.joinToString("").contains("SA000000000000000"))
    }
    @Test fun refundsReduceGrandTotal() {
        val fields = EcrReportData.reportFields(listOf(sale(), sale(2).copy(type = TransactionType.REFUND, totalAmount = "2.25")), database, false)
        assertTrue(fields.mapNotNull { it["TT"] }.joinToString("").contains("GT002000000001000"))
    }
    @Test fun auditUsesOriginalPosIdAndPreservesItAfterVoid() {
        val original = sale().copy(transactionId = "000099", posTransactionId = "POS-ORDER-42")
        for (transaction in listOf(original, original.copy(returnStatus = ReturnStatus.Voided))) {
            val row = EcrReportData.reportFields(listOf(transaction), database, true).mapNotNull { it["TD"] }.joinToString("")
            assertEquals("POS-ORDER-42", row.removeSuffix("^").split('|')[9])
        }
        val manual = EcrReportData.reportFields(listOf(sale().copy(transactionId = "000099")), database, true)
            .mapNotNull { it["TD"] }.joinToString("")
        assertEquals("", manual.removeSuffix("^").split('|')[9])
    }
}
