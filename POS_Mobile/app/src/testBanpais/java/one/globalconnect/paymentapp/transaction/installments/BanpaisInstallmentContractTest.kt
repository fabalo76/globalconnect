package one.globalconnect.paymentapp.transaction.installments

import org.junit.Assert.*
import org.junit.Test
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry.TransactionAttribute

class BanpaisInstallmentContractTest {
    private val contract = BanpaisInstallmentContract
    private val response = "EXT1" + "EXTRAFINANCIAMIENTO".padEnd(25) + "03" + "030612"

    @Test fun parsesHostPlanAndAllowedInstallments() {
        val plan = contract.parsePlans(response).single()
        assertEquals("EXT1", plan.code)
        assertEquals("EXTRAFINANCIAMIENTO", plan.name)
        assertEquals(listOf(3, 6, 12), plan.installments)
        assertEquals("06EXT1" + "0".repeat(16) + "C", contract.saleField45(TransactionType.EXTRAS_SALE, InstallmentSelection(plan, 6)))
        assertThrows(IllegalArgumentException::class.java) { contract.saleField45(TransactionType.EXTRAS_SALE, InstallmentSelection(plan, 9)) }
    }

    @Test fun parsesDocumentedMultiplePlansAndEncodesQuotaSale() {
        val plans = contract.parsePlans("EXT1EXTRA FINANCING          03030612INT1INTRA FINANCING          03030609")
        assertEquals(listOf("EXT1", "INT1"), plans.map { it.code })
        assertEquals(listOf(3, 6, 12), plans[0].installments)
        assertEquals(listOf(3, 6, 9), plans[1].installments)
        assertEquals("06INT10000000000000000S", contract.saleField45(TransactionType.QUOTA_SALE, InstallmentSelection(plans[1], 6)))
        assertEquals("03EXT10000000000000000C", contract.saleField45(TransactionType.EXTRAS_SALE, InstallmentSelection(plans[0], 3)))
        assertThrows(IllegalArgumentException::class.java) { contract.parsePlans(response + "INT1") }
    }

    @Test fun parsesPdfExampleWithDifferentRecordLengths() {
        val plans = contract.parsePlans("INT1INTRAFINANCING           020609EXT1EXTRA FINANCING          03061224")
        assertEquals(listOf("INT1", "EXT1"), plans.map { it.code })
        assertEquals(listOf(6, 9), plans[0].installments)
        assertEquals(listOf(6, 12, 24), plans[1].installments)
        assertThrows(IllegalArgumentException::class.java) { contract.parsePlans("X".repeat(256)) }
    }

    @Test fun reconstructsReceiptDetailsFromSavedRecordAfterVoid() {
        val transaction = one.globalconnect.paymentapp.transaction.Transaction(
            type = TransactionType.QUOTA_SALE,
            paymentPlan = "06INT10000000000000000S",
            paymentPlanQueryResponse = "EXT1EXTRA FINANCING          03030612INT1INTRA FINANCING          03030609")
        val gson = com.google.gson.Gson()
        val restored = gson.fromJson(gson.toJson(transaction), one.globalconnect.paymentapp.transaction.Transaction::class.java)
        assertEquals(InstallmentDetails("INT1", "INTRA FINANCING", 6), restored.installmentDetails())
        assertEquals(restored.installmentDetails(), restored.copy(
            returnStatus = one.globalconnect.paymentapp.transaction.ReturnStatus.Voided).installmentDetails())
        assertEquals(InstallmentDetails("INT1", "INT1", 6), restored.copy(paymentPlanQueryResponse = "").installmentDetails())
        assertNull(restored.copy(paymentPlan = "").installmentDetails())
    }

    @Test fun rejectsMalformedEmptyAndDuplicatePlansOrCounts() {
        listOf("", response.dropLast(1), response + response,
            "EXT1" + "PLAN".padEnd(25) + "00",
            "EXT1" + "PLAN".padEnd(25) + "02" + "0303",
            "EXT1" + "PLAN".padEnd(25) + "01" + "00",
            "EXT1" + "PLAN".padEnd(25) + "01" + "AA").forEach {
            assertThrows(IllegalArgumentException::class.java) { contract.parsePlans(it) }
        }
    }

    @Test fun queriesAreNonFinancialAndDistinguishExtraAndQuota() {
        for ((type, suffix) in listOf(TransactionType.EXTRAS_SALE to "C", TransactionType.QUOTA_SALE to "S")) {
            assertEquals("0".repeat(22) + suffix, contract.queryField45(type))
            val config = requireNotNull(TransactionConfigRegistry.configFor(contract.queryCode(type)))
            assertFalse(config.hasAttribute(TransactionAttribute.WRITES_RECORD))
            assertFalse(config.hasAttribute(TransactionAttribute.NEEDS_REVERSAL))
            assertSame(contract, InstallmentContracts.forTransaction(type))
        }
        assertNull(InstallmentContracts.forTransaction(TransactionType.SALE))
    }
}
