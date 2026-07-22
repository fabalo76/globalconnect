package one.globalconnect.paymentapp.uicpos.pos.model

import one.globalconnect.paymentapp.transaction.TotalsMetric
import one.globalconnect.paymentapp.transaction.TotalsRecord
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

typealias ReconciliationMetric = TotalsMetric

/**
 * Enumerates the different totals transported in a reconciliation request.
 */
enum class ReconciliationTotalKind {
    CREDIT_SALES,
    TAX1,
    TAX1DISCOUNT,
    TAX2,
    TIP,
    REFUNDS,
    CASH,
    DEBIT_SALES,
    DEBIT_REFUNDS,
    AUTHORIZATIONS,
    AUTHORIZATION_REFUNDS,
    HYPERCOM_RESERVED_1,
    HYPERCOM_RESERVED_2,
    VOIDED_SALES,
    VOIDED_REFUNDS,
    PAYMENTS,
    VOIDED_PAYMENTS,
    LOYALTY,
    CASHBACK,
}

/**
 * Container for reconciliation totals backed by [TotalsRecord].
 */
data class ReconciliationTotals(
    val totals: TotalsRecord = TotalsRecord.empty(),
) {
    val creditSales: ReconciliationMetric get() = totals.sales
    val tax1: ReconciliationMetric get() = totals.tax1
    val tax1Discount: ReconciliationMetric get() = totals.tax1Discount
    val tax2: ReconciliationMetric get() = totals.tax2
    val tip: ReconciliationMetric get() = totals.tip
    val refunds: ReconciliationMetric get() = totals.refund
    val cash: ReconciliationMetric get() = totals.cash
    val debitSales: ReconciliationMetric get() = totals.debitSales
    val debitRefunds: ReconciliationMetric get() = totals.debitRefunds
    val authorizations: ReconciliationMetric get() = totals.authorizations
    val authorizationRefunds: ReconciliationMetric get() = totals.authorizationRefunds
    val hypercomReserved1: ReconciliationMetric get() = totals.hypercomReserved1
    val hypercomReserved2: ReconciliationMetric get() = totals.hypercomReserved2
    val voidedSales: ReconciliationMetric get() = totals.voidedSales
    val voidedRefunds: ReconciliationMetric get() = totals.voidedRefund
    val payments: ReconciliationMetric get() = totals.payment
    val voidedPayments: ReconciliationMetric get() = totals.voidedPayment
    val loyalty: ReconciliationMetric get() = totals.loyaltySale
    val cashback: ReconciliationMetric get() = totals.cashback

    fun orderedEntries(): List<Pair<ReconciliationTotalKind, ReconciliationMetric>> = listOf(
        ReconciliationTotalKind.CREDIT_SALES to totals.sales,
        ReconciliationTotalKind.TAX1 to totals.tax1,
        ReconciliationTotalKind.TAX1DISCOUNT to totals.tax1Discount,
        ReconciliationTotalKind.TAX2 to totals.tax2,
        ReconciliationTotalKind.TIP to totals.tip,
        ReconciliationTotalKind.REFUNDS to totals.refund,
        ReconciliationTotalKind.CASH to totals.cash,
        ReconciliationTotalKind.DEBIT_SALES to totals.debitSales,
        ReconciliationTotalKind.DEBIT_REFUNDS to totals.debitRefunds,
        ReconciliationTotalKind.AUTHORIZATIONS to totals.authorizations,
        ReconciliationTotalKind.AUTHORIZATION_REFUNDS to totals.authorizationRefunds,
        ReconciliationTotalKind.HYPERCOM_RESERVED_1 to totals.hypercomReserved1,
        ReconciliationTotalKind.HYPERCOM_RESERVED_2 to totals.hypercomReserved2,
        ReconciliationTotalKind.VOIDED_SALES to totals.voidedSales,
        ReconciliationTotalKind.VOIDED_REFUNDS to totals.voidedRefund,
        ReconciliationTotalKind.PAYMENTS to totals.payment,
        ReconciliationTotalKind.VOIDED_PAYMENTS to totals.voidedPayment,
        ReconciliationTotalKind.LOYALTY to totals.loyaltySale,
        ReconciliationTotalKind.CASHBACK to totals.cashback,
    )

    fun isEmpty(): Boolean = orderedEntries().all { (_, metric) -> metric.isZero() }

    fun toAsciiPayload(): String {
        val builder = StringBuilder()
        orderedEntries().forEach { (_, metric) ->
            builder.append(metric.formattedCount())
            builder.append(metric.formattedAmount())
        }
        return builder.toString()
    }
}

private fun ReconciliationMetric.isZero(): Boolean =
    count == 0 && amount.compareTo(ZERO) == 0

private fun ReconciliationMetric.formattedCount(): String {
    val safeCount = count.coerceIn(0, 999)
    return String.format(Locale.US, "%03d", safeCount)
}

private fun ReconciliationMetric.formattedAmount(): String {
    val cents = amount.abs().movePointRight(2).setScale(0, RoundingMode.HALF_UP)
    val safeValue = cents.toBigInteger().abs().toString().takeLast(AMOUNT_FIELD_LENGTH)
    return safeValue.padStart(AMOUNT_FIELD_LENGTH, '0')
}

private val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)

private const val AMOUNT_FIELD_LENGTH = 12
