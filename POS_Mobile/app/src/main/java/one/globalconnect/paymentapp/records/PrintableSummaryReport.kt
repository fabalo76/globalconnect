package one.globalconnect.paymentapp.records

import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.transaction.ReturnStatus
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
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
    TransactionType.EXTRAS_SALE,
    TransactionType.QUOTA_SALE,
    TransactionType.LOYALTY_SALE,
)

private val REFUND_TYPES = setOf(
    TransactionType.REFUND,
    TransactionType.MANUALREFUND,
)

internal data class SummaryMetric(
    var count: Int = 0,
    var amount: BigDecimal = ZERO,
) {
    fun add(amountToAdd: BigDecimal, includeZeroAmount: Boolean = false) {
        val normalized = amountToAdd.setScale(2, RoundingMode.HALF_UP)
        if (!includeZeroAmount && normalized.compareTo(ZERO) == 0) {
            return
        }
        count += 1
        amount = amount.add(normalized).setScale(2, RoundingMode.HALF_UP)
    }

    val hasActivity: Boolean
        get() = count != 0 || amount.compareTo(ZERO) != 0
}

internal data class SummaryTotals(
    val payments: SummaryMetric = SummaryMetric(),
    val refunds: SummaryMetric = SummaryMetric(),
    val cash: SummaryMetric = SummaryMetric(),
    val sales: SummaryMetric = SummaryMetric(),
    val tax1: SummaryMetric = SummaryMetric(),
    val tax1Discount: SummaryMetric = SummaryMetric(),
    val tax2: SummaryMetric = SummaryMetric(),
    val netSales: SummaryMetric = SummaryMetric(),
    val netTotal: SummaryMetric = SummaryMetric(),
)

internal data class SummaryVoids(
    val sales: SummaryMetric = SummaryMetric(),
    val cash: SummaryMetric = SummaryMetric(),
    val refunds: SummaryMetric = SummaryMetric(),
    val payments: SummaryMetric = SummaryMetric(),
)

internal data class SummaryReportData(
    val currencySymbol: String,
    val acquirerNames: Set<String>,
    val acquirerIds: Set<String>,
    val totals: SummaryTotals,
    val voids: SummaryVoids,
)

internal fun buildSummaryReport(
    transactions: List<Transaction>,
    tmsDatabase: TMSDATA,
): SummaryReportData {
    val totals = SummaryTotals()
    val voids = SummaryVoids()
    val acquirerNames = linkedSetOf<String>()
    val acquirerIds = linkedSetOf<String>()
    val acquirerMap = tmsDatabase.Acquirer.associateBy(TMS_Acquirer::AcqID)

    var currencySymbol: String? = null

    transactions.forEach { transaction ->
        val acquirer = acquirerMap[transaction.acquirerId]
        if (transaction.acquirerId.isNotBlank()) {
            acquirerIds.add(transaction.acquirerId)
        }
        if (currencySymbol.isNullOrBlank()) {
            currencySymbol = acquirer?.Currency?.takeIf { it.isNotBlank() }
        }
        acquirer?.AcquirerName?.takeIf { it.isNotBlank() }?.let(acquirerNames::add)

        val totalAmount = transaction.totalAmount.toAmount()
        val tax1Amount = transaction.tax1Amount.toAmount()
        val tax1DiscountAmount = transaction.tax1DiscountAmount
            .toAmount()
            .let { if (it.signum() > 0) it.negate() else it }
        val tax2Amount = transaction.tax2Amount.toAmount()

        if (transaction.returnStatus == ReturnStatus.Voided) {
            when (transaction.type) {
                in SALE_TYPES -> voids.sales.add(totalAmount.negate(), includeZeroAmount = true)
                TransactionType.CASH -> voids.cash.add(totalAmount.negate(), includeZeroAmount = true)
                in REFUND_TYPES -> voids.refunds.add(totalAmount, includeZeroAmount = true)
                TransactionType.PAYMENT -> voids.payments.add(totalAmount, includeZeroAmount = true)
                else -> Unit
            }
            return@forEach
        }

        when (transaction.type) {
            in SALE_TYPES -> {
                totals.sales.add(totalAmount, includeZeroAmount = true)
                totals.tax1.add(tax1Amount, includeZeroAmount = true)
                totals.tax1Discount.add(tax1DiscountAmount, includeZeroAmount = true)
                totals.tax2.add(tax2Amount, includeZeroAmount = true)
            }

            TransactionType.CASH -> {
                totals.cash.add(totalAmount, includeZeroAmount = true)
            }

            in REFUND_TYPES -> {
                totals.refunds.add(totalAmount.negate(), includeZeroAmount = true)
            }

            TransactionType.PAYMENT -> {
                totals.payments.add(totalAmount.negate(), includeZeroAmount = true)
            }

            else -> Unit
        }
    }

    totals.tax1.count = totals.sales.count
    totals.tax1Discount.count = totals.sales.count
    totals.tax2.count = totals.sales.count

    totals.netSales.count = totals.sales.count
    totals.netSales.amount = totals.sales.amount
        .add(totals.tax1.amount)
        .add(totals.tax1Discount.amount)
        .add(totals.tax2.amount)
        .setScale(2, RoundingMode.HALF_UP)

    totals.netTotal.count = totals.sales.count + totals.cash.count + totals.payments.count + totals.refunds.count
    totals.netTotal.amount = totals.sales.amount
        .add(totals.cash.amount)
        .add(totals.payments.amount)
        .add(totals.refunds.amount)
        .setScale(2, RoundingMode.HALF_UP)

    val resolvedCurrency = currencySymbol
        ?: tmsDatabase.Acquirer.firstOrNull()?.Currency?.takeIf { it.isNotBlank() }
        ?: ""

    return SummaryReportData(
        currencySymbol = resolvedCurrency,
        acquirerNames = acquirerNames,
        acquirerIds = acquirerIds,
        totals = totals,
        voids = voids,
    )
}

internal fun Transaction.toSignedAmount(): BigDecimal {
    val baseAmount = totalAmount.toAmount()
    val signed = when (type) {
        in SALE_TYPES -> baseAmount
        TransactionType.CASH -> baseAmount
        in REFUND_TYPES -> baseAmount.negate()
        TransactionType.PAYMENT -> baseAmount.negate()
        else -> baseAmount
    }
    return if (returnStatus == ReturnStatus.Voided) signed.negate() else signed
}

private fun String?.toAmount(): BigDecimal {
    if (this.isNullOrBlank()) return ZERO
    val sanitized = replace(",", "").trim()
    val parsed = sanitized.toBigDecimalOrNull() ?: return ZERO
    return parsed.setScale(2, RoundingMode.HALF_UP)
}
