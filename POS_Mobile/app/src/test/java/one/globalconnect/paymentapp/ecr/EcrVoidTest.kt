package one.globalconnect.paymentapp.ecr

import one.globalconnect.paymentapp.transaction.*
import org.junit.Assert.*
import org.junit.Test

class EcrVoidTest {
    private val request = EcrMessage("42", fields = mapOf("80" to "VOID-1", "65" to "10", "P1" to "0"))
    private fun sale() = Transaction(id = 7, invoiceId = "000010", type = TransactionType.SALE, ARC = "00")
    @Test fun selectsExactInvoiceIgnoringLeadingZeros() {
        assertEquals(7, EcrVoid.select(request, listOf(sale())).id)
    }
    @Test fun neverFallsBackToLastTransaction() {
        assertThrows(IllegalArgumentException::class.java) { EcrVoid.select(request, listOf(sale().copy(invoiceId = "11"))) }
        assertThrows(IllegalArgumentException::class.java) { EcrVoid.validate(request.copy(fields = request.fields - "65")) }
    }
    @Test fun rejectsAmbiguousAndAlreadyVoidedRecords() {
        assertThrows(IllegalArgumentException::class.java) { EcrVoid.select(request, listOf(sale(), sale().copy(id = 8))) }
        assertThrows(IllegalArgumentException::class.java) { EcrVoid.select(request, listOf(sale().copy(returnStatus = ReturnStatus.Voided))) }
    }
    @Test fun rejectsDeclinesAndNonFinancialBalance() {
        for (transaction in listOf(sale().copy(ARC = "05"), sale().copy(type = TransactionType.LOYALTY_BALANCE))) {
            assertThrows(IllegalArgumentException::class.java) { EcrVoid.select(request, listOf(transaction)) }
        }
    }
    @Test fun supportsApprovedLoyaltyAndPartialSale() {
        assertEquals(TransactionType.LOYALTY_SALE, EcrVoid.select(request, listOf(sale().copy(type = TransactionType.LOYALTY_SALE))).type)
        assertEquals("10", EcrVoid.select(request, listOf(sale().copy(ARC = "10"))).ARC)
    }
    @Test fun rejectsMalformedRequestsAndUnexpectedAmounts() {
        for (bad in listOf(request.copy(indicator = 1), request.copy(more = true),
            request.copy(fields = request.fields + ("65" to "000")),
            request.copy(fields = request.fields + ("40" to "000000001000")))) {
            assertThrows(IllegalArgumentException::class.java) { EcrVoid.validate(bad) }
        }
    }
}
