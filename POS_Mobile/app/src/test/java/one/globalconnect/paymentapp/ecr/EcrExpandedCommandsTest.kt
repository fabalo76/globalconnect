package one.globalconnect.paymentapp.ecr

import org.junit.Assert.*
import org.junit.Test
import one.globalconnect.paymentapp.transaction.*
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.tms.paymentapp.*

class EcrExpandedCommandsTest {
    private fun request(code: String, extra: Map<String, String> = emptyMap()) = EcrMessage(code,
        fields = mapOf("80" to "pos-id", "RQ" to "req-id", "40" to "000000001000") + extra)

    @Test fun financialCommandsMapToExistingFlows() {
        mapOf("26" to TransactionType.REFUND, "38" to TransactionType.PAYMENT, "E7" to TransactionType.CASH,
            "10" to TransactionType.AUTHONLY, "CI" to TransactionType.CHECKIN, "CO" to TransactionType.CHECKOUT).forEach { (code,type) ->
            assertEquals(type, EcrSale.from(request(code, if (code in setOf("CI","CO")) mapOf("66" to "ROOM-1") else emptyMap())).transactionType)
        }
    }

    @Test fun balanceInquiriesAreZeroAmountAndNeverWriteFinancialRecords() {
        for (code in listOf("E6", "34", "36")) {
            val request = EcrMessage(code, fields = mapOf("80" to "balance"))
            val type = EcrSale.from(request).transactionType
            val config = TransactionConfigRegistry.configFor(type.toTransactionString())!!
            assertFalse(config.hasAttribute(TransactionConfigRegistry.TransactionAttribute.WRITES_RECORD))
            assertFalse(config.hasAttribute(TransactionConfigRegistry.TransactionAttribute.NEEDS_REVERSAL))
            assertThrows(IllegalArgumentException::class.java) { EcrSale.from(request.copy(fields = request.fields + ("41" to "000000000001"))) }
        }
    }

    @Test fun tipAndCashbackAreExactAndBounded() {
        val sale = EcrSale.from(request("20", mapOf("41" to "000000000125", "42" to "000000000500")))
        assertEquals("1.25", sale.tip); assertEquals("5.00", sale.cashback)
        assertThrows(IllegalArgumentException::class.java) { EcrSale.from(request("38", mapOf("42" to "000000000001"))) }
        assertThrows(IllegalArgumentException::class.java) { EcrSale.from(request("20", mapOf("40" to "999999999999", "41" to "000000000001"))) }
        assertThrows(IllegalArgumentException::class.java) { EcrSale.from(request("CO")) }
    }

    @Test fun reportSelectorsAreRestrictedToNewReports() {
        listOf("P4", "P5").forEach { EcrReportData.validate(EcrMessage(it, fields = mapOf("80" to "r", "AI" to "A", "P1" to "0"))) }
        assertThrows(IllegalArgumentException::class.java) { EcrReportData.validate(EcrMessage("P2", fields = mapOf("80" to "r", "AI" to "A"))) }
    }

    @Test fun installmentBreakdownsExcludeVoidsAndKeepCountsAndCurrency() {
        val database = TMSDATA(terminal = listOf(TMS_Terminal(acquirer = listOf(TMS_Acquirer(acquirer_id = "A", acquirerName = "Bank", currencySymbol = "Lps", currencyCode = 340)))))
        val quota = Transaction(type = TransactionType.QUOTA_SALE, acquirerId = "A", paymentPlan = "06", totalAmount = "100.25")
        val extra = quota.copy(type = TransactionType.EXTRAS_SALE, paymentPlan = "03", totalAmount = "20.00")
        val data = EcrReportData.installmentFields(listOf(quota, extra, quota.copy(returnStatus = ReturnStatus.Voided)), database, mapOf("A" to "000007"))
        assertEquals("Bank|Lps|000007|E03001000000002000|Q06001000000010025^", data.mapNotNull { it["AQ"] }.joinToString(""))
        assertEquals("Lps|E03001000000002000|Q06001000000010025^", data.mapNotNull { it["TQ"] }.joinToString(""))
    }
}
