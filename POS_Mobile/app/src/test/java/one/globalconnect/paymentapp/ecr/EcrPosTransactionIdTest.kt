package one.globalconnect.paymentapp.ecr

import one.globalconnect.paymentapp.transaction.*
import org.junit.Assert.*
import org.junit.Test

class EcrPosTransactionIdTest {
    private val transaction = Transaction(invoiceId = "000005", stan = "000009",
        retrievalReferenceNumber = "626400000009", authCode = "ABC123", type = TransactionType.LOYALTY_SALE,
        masked_cardNumber = "************4593", localDateTime = "2026-09-21 11:14:46", totalAmount = "10.00")
    private val response = EcrMessage("31", indicator = 1, fields = mapOf(
        "80" to "POS-ORIGINAL-123", "65" to "000005", "78" to "000009", "79" to "626400000009",
        "01" to "ABC123", "30" to "************4593", "03" to "260921", "04" to "111446", "40" to "000000001000"))
    @Test fun recoversOriginalIdEvenAfterVoid() {
        assertEquals("POS-ORIGINAL-123", EcrPosTransactionId.recover(transaction.copy(returnStatus = ReturnStatus.Voided), listOf(response)))
    }
    @Test fun refusesAmbiguousIdsAndVoidRequestIds() {
        assertNull(EcrPosTransactionId.recover(transaction, listOf(response, response.copy(fields = response.fields + ("80" to "OTHER")))))
        assertNull(EcrPosTransactionId.recover(transaction, listOf(response.copy(command = "42"))))
    }
    @Test fun refusesMismatchOrDeclinedResponse() {
        for (tag in listOf("65", "78", "79", "01", "30", "03", "04", "40")) {
            assertNull(EcrPosTransactionId.recover(transaction, listOf(response.copy(fields = response.fields + (tag to "DIFFERENT")))))
        }
        assertNull(EcrPosTransactionId.recover(transaction, listOf(response.copy(response = "05"))))
    }
    @Test fun preservesStoredIdWithoutGuessingFromHostReference() {
        assertEquals("STORED", EcrPosTransactionId.recover(transaction.copy(posTransactionId = "STORED"), emptyList()))
        assertNull(EcrPosTransactionId.recover(transaction.copy(externalReferenceNumber = "HOST-ID"), emptyList()))
    }
}
