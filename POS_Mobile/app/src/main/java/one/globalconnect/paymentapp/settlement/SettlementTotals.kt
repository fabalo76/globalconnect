package one.globalconnect.paymentapp.settlement

import one.globalconnect.paymentapp.transaction.ReturnStatus
import one.globalconnect.paymentapp.transaction.TotalsMetric
import one.globalconnect.paymentapp.transaction.TotalsRecord
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals
import java.math.BigDecimal
import java.math.RoundingMode

private val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)

private val SALE_TYPES = setOf(
    TransactionType.SALE,
    TransactionType.MANUALSALE,
    TransactionType.TOKENSALE,
    TransactionType.FORCESALE,
    TransactionType.MANUALFORCESALE,
    TransactionType.MOTO,
)

private val REFUND_TYPES = setOf(
    TransactionType.REFUND,
    TransactionType.MANUALREFUND,
)

private fun parseAmount(raw: String): BigDecimal {
    if (raw.isBlank()) return ZERO
    val normalized = raw.replace(",", "").trim()
    val parsed = normalized.toBigDecimalOrNull() ?: return ZERO
    return parsed.setScale(2, RoundingMode.HALF_UP)
}

private fun TotalsMetric.addTransaction(
    transaction: Transaction,
    includeZeroAmount: Boolean = false,
) {
    val amount = parseAmount(transaction.totalAmount)
    add(amount, includeZeroAmount)
}

fun calculateReconciliationTotals(transactions: List<Transaction>): ReconciliationTotals {
    val totalsRecord = TotalsRecord.empty()

    transactions.forEach { transaction ->
        if (transaction.type == TransactionType.SETTLEMENT) {
            return@forEach
        }

        when {
            transaction.type in SALE_TYPES -> {
                if (transaction.returnStatus == ReturnStatus.Voided) {
                    totalsRecord.voidedSales.addTransaction(transaction, includeZeroAmount = true)
                } else {
                    totalsRecord.sales.addTransaction(transaction)
                    totalsRecord.cashback.add(parseAmount(transaction.cashbackAmount))
                }
            }

            transaction.type in REFUND_TYPES -> {
                if (transaction.returnStatus == ReturnStatus.Voided) {
                    totalsRecord.voidedRefund.addTransaction(transaction, includeZeroAmount = true)
                } else {
                    totalsRecord.refund.addTransaction(transaction)
                }
            }

            transaction.type == TransactionType.CASH -> {
                if (transaction.returnStatus != ReturnStatus.Voided) {
                    totalsRecord.cash.addTransaction(transaction)
                }
            }

            transaction.type == TransactionType.PAYMENT -> {
                if (transaction.returnStatus == ReturnStatus.Voided) {
                    totalsRecord.voidedPayment.addTransaction(transaction, includeZeroAmount = true)
                } else {
                    totalsRecord.payment.addTransaction(transaction)
                }
            }

            transaction.type == TransactionType.LOYALTY_SALE -> {
                if (transaction.returnStatus != ReturnStatus.Voided) {
                    totalsRecord.loyaltySale.addTransaction(transaction)
                }
            }
        }
    }

    return ReconciliationTotals(totalsRecord)
}
