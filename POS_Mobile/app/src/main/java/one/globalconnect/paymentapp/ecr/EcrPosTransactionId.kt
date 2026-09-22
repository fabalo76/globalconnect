package one.globalconnect.paymentapp.ecr

import one.globalconnect.paymentapp.transaction.ReturnStatus
import one.globalconnect.paymentapp.transaction.Transaction
import java.time.format.DateTimeFormatter

/** Recover only an unambiguous original approved ECR response, never a void/report request ID. */
internal object EcrPosTransactionId {
    fun recover(transaction: Transaction, replies: List<EcrMessage>): String? {
        if (transaction.posTransactionId.isNotEmpty()) return transaction.posTransactionId
        val date = runCatching { EcrReportData.timestamp(transaction) }.getOrNull() ?: return null
        val type = EcrReportData.type(transaction.copy(returnStatus = ReturnStatus.None))
        if (transaction.invoiceId.isBlank() || transaction.stan.isBlank() ||
            transaction.retrievalReferenceNumber.isBlank() || transaction.authCode.isBlank() ||
            transaction.masked_cardNumber.isBlank()) return null
        return replies.filter { reply ->
            reply.command in setOf("20", "31") && reply.command == type &&
                reply.indicator == 1 && !reply.more && reply.response in setOf("00", "10") &&
                reply.fields["65"] == transaction.invoiceId && reply.fields["78"] == transaction.stan &&
                reply.fields["79"] == transaction.retrievalReferenceNumber && reply.fields["01"] == transaction.authCode &&
                reply.fields["30"] == transaction.masked_cardNumber &&
                reply.fields["03"] == date.format(DateTimeFormatter.ofPattern("yyMMdd")) &&
                reply.fields["04"] == date.format(DateTimeFormatter.ofPattern("HHmmss")) &&
                reply.fields["40"]?.toBigDecimalOrNull() == EcrReportData.cents(transaction.totalAmount).toBigDecimal()
        }.mapNotNull { it.fields["80"]?.takeIf(String::isNotBlank) }.distinct().singleOrNull()
    }
}
