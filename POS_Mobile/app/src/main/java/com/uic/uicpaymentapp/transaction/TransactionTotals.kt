package com.uic.uicpaymentapp.transaction

import com.uic.tms.payment_app.TMS_Acquirer
import com.uic.tms.payment_app.TMSDATA
import com.uic.tms.payment_app.TMS_Issuer
import java.math.BigDecimal
import java.math.RoundingMode

internal const val UNKNOWN_ISSUER_ID = "UNKNOWN"

private const val TERMINAL_TOTAL_ID = "0"
private const val TERMINAL_TOTAL_NAME = "Terminal"
private const val TERMINAL_CURRENCY_CODE = "000"
private const val TERMINAL_CURRENCY_SYMBOL = ""
private const val UNKNOWN_ACQUIRER_NAME = "Unknown Acquirer"
private const val UNKNOWN_ISSUER_NAME = "Unknown Issuer"

private val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)

private val SALE_TYPES = setOf(
    TransactionType.SALE,
    TransactionType.MANUALSALE,
    TransactionType.TOKENSALE,
    TransactionType.FORCESALE,
    TransactionType.MANUALFORCESALE,
    TransactionType.MOTO,
    TransactionType.CHECKOUT,
)

private val REFUND_TYPES = setOf(
    TransactionType.REFUND,
    TransactionType.MANUALREFUND,
)

/**
 * Represents a metric that stores a count and its aggregated monetary amount.
 */
data class TotalsMetric(
    var count: Int = 0,
    var amount: BigDecimal = ZERO,
) {
    fun add(amountToAdd: BigDecimal, includeZeroAmount: Boolean = false) {
        val normalized = amountToAdd.ensureScale()
        if (!includeZeroAmount && normalized.compareTo(ZERO) == 0) {
            return
        }
        count += 1
        amount = amount.add(normalized).ensureScale()
    }
}

/**
 * Describes the totals for a terminal, acquirer or issuer.
 */
data class TotalsRecord(
    val id: String,
    val name: String,
    val currencyCode: String,
    val currencySymbol: String,
    val sales: TotalsMetric = TotalsMetric(),
    val voidedSales: TotalsMetric = TotalsMetric(),
    val cash: TotalsMetric = TotalsMetric(),
    val voidedCash: TotalsMetric = TotalsMetric(),
    val extrasSale: TotalsMetric = TotalsMetric(),
    val voidedExtrasSale: TotalsMetric = TotalsMetric(),
    val quotasSale: TotalsMetric = TotalsMetric(),
    val voidedQuotasSale: TotalsMetric = TotalsMetric(),
    val loyaltySale: TotalsMetric = TotalsMetric(),
    val voidedLoyaltySale: TotalsMetric = TotalsMetric(),
    val debitSales: TotalsMetric = TotalsMetric(),
    val debitRefunds: TotalsMetric = TotalsMetric(),
    val authorizations: TotalsMetric = TotalsMetric(),
    val authorizationRefunds: TotalsMetric = TotalsMetric(),
    val hypercomReserved1: TotalsMetric = TotalsMetric(),
    val hypercomReserved2: TotalsMetric = TotalsMetric(),
    val cashback: TotalsMetric = TotalsMetric(),
    val tax1: TotalsMetric = TotalsMetric(),
    val tax1Discount: TotalsMetric = TotalsMetric(),
    val tax2: TotalsMetric = TotalsMetric(),
    val tip: TotalsMetric = TotalsMetric(),
    val refund: TotalsMetric = TotalsMetric(),
    val voidedRefund: TotalsMetric = TotalsMetric(),
    val payment: TotalsMetric = TotalsMetric(),
    val voidedPayment: TotalsMetric = TotalsMetric(),
    val credits: TotalsMetric = TotalsMetric(),
    val debits: TotalsMetric = TotalsMetric(),
) {
    companion object {
        fun empty(
            id: String = "",
            name: String = "",
            currencyCode: String = "",
            currencySymbol: String = "",
        ): TotalsRecord = TotalsRecord(
            id = id,
            name = name,
            currencyCode = currencyCode,
            currencySymbol = currencySymbol,
        )
    }
}

/**
 * Groups an acquirer record with its issuer totals.
 */
data class AcquirerTotals(
    val acquirer: TotalsRecord,
    val issuers: List<TotalsRecord>,
)

/**
 * Provides the terminal totals alongside all acquirer totals.
 */
data class TotalsResult(
    val terminal: TotalsRecord,
    val acquirers: List<AcquirerTotals>,
)

/**
 * Calculates the terminal, acquirer and issuer totals for the provided transactions.
 *
 * @param transactions The transactions that will be aggregated. The list should already be
 * filtered by acquirer when a specific acquirer is requested.
 * @param tmsDatabase The TMS configuration used to resolve terminal, acquirer and issuer metadata.
 */
fun calcTotals(
    transactions: List<Transaction>,
    tmsDatabase: TMSDATA,
): TotalsResult {
    val acquirerMap = tmsDatabase.Acquirer.associateBy { it.AcqID }
    val issuersByAcquirer = tmsDatabase.Issuer.groupBy { it.AcqID }

    val terminalRecord = TotalsRecord(
        id = TERMINAL_TOTAL_ID,
        name = TERMINAL_TOTAL_NAME,
        currencyCode = TERMINAL_CURRENCY_CODE,
        currencySymbol = TERMINAL_CURRENCY_SYMBOL,
    )

    val acquirerTotalsMap = mutableMapOf<String, TotalsRecord>()
    val issuerTotalsMap = mutableMapOf<String, MutableMap<String, TotalsRecord>>()

    val baseAcquirerIds = transactions.mapNotNull { transaction ->
        transaction.acquirerId.takeIf { it.isNotBlank() }
    }.distinct().ifEmpty {
        tmsDatabase.Acquirer.map { it.AcqID }
    }

    baseAcquirerIds.forEach { id ->
        val acquirer = acquirerMap[id]
        val record = createAcquirerRecord(id, acquirer)
        acquirerTotalsMap[id] = record
        val issuerRecords = mutableMapOf<String, TotalsRecord>()
        issuersByAcquirer[id]?.forEach { issuer ->
            issuerRecords[issuer.IssuID] = createIssuerRecord(record, issuer, null)
        }
        issuerTotalsMap[id] = issuerRecords
    }

    transactions.forEach { transaction ->
        val txAcquirerId = transaction.acquirerId
        if (txAcquirerId.isBlank()) {
            return@forEach
        }

        val acquirer = acquirerMap[txAcquirerId]
        val acquirerRecord = acquirerTotalsMap.getOrPut(txAcquirerId) {
            val record = createAcquirerRecord(txAcquirerId, acquirer)
            issuerTotalsMap[txAcquirerId] = mutableMapOf()
            record
        }

        val issuersForAcquirer = issuersByAcquirer[txAcquirerId]
        val issuer = issuersForAcquirer?.find { it.IssuID == transaction.issuerId }
        val issuerRecords = issuerTotalsMap.getOrPut(txAcquirerId) { mutableMapOf() }
        val issuerKey = (issuer?.IssuID ?: transaction.issuerId).takeUnless { it.isNullOrBlank() }
            ?: UNKNOWN_ISSUER_ID
        val issuerRecord = issuerRecords.getOrPut(issuerKey) {
            createIssuerRecord(acquirerRecord, issuer, transaction)
        }

        updateTotals(terminalRecord, transaction)
        updateTotals(acquirerRecord, transaction)
        updateTotals(issuerRecord, transaction)
    }

    val acquirerTotals = acquirerTotalsMap.map { (id, record) ->
        val issuerRecords = issuerTotalsMap[id].orEmpty()
        issuerRecords.values.forEach { finalizeRecord(it) }
        finalizeRecord(record)
        AcquirerTotals(
            acquirer = record,
            issuers = issuerRecords.values.sortedBy { it.name.lowercase() },
        )
    }.sortedBy { it.acquirer.name.lowercase() }

    finalizeRecord(terminalRecord)

    return TotalsResult(
        terminal = terminalRecord,
        acquirers = acquirerTotals,
    )
}

private fun updateTotals(record: TotalsRecord, transaction: Transaction) {
    val totalAmount = transaction.totalAmount.toAmount()
    val tax1Amount = transaction.tax1Amount.toAmount()
    val tax1DiscountAmount = transaction.tax1DiscountAmount.toAmount()
    val tax2Amount = transaction.tax2Amount.toAmount()
    val tipAmount = transaction.tipAmount.toAmount()

    when (transaction.type) {
        in SALE_TYPES -> {
            if (transaction.returnStatus == ReturnStatus.Voided) {
                record.voidedSales.add(totalAmount.negate(), includeZeroAmount = true)
            } else {
                record.sales.add(totalAmount, includeZeroAmount = true)
                record.tax1.add(tax1Amount)
                record.tax1Discount.add(tax1DiscountAmount)
                record.tax2.add(tax2Amount)
                record.tip.add(tipAmount)
            }
        }

        TransactionType.CASH -> {
            if (transaction.returnStatus == ReturnStatus.Voided) {
                record.voidedCash.add(totalAmount.negate(), includeZeroAmount = true)
            } else {
                record.cash.add(totalAmount, includeZeroAmount = true)
            }
        }

        TransactionType.EXTRAS_SALE -> {
            if (transaction.returnStatus == ReturnStatus.Voided) {
                record.voidedExtrasSale.add(totalAmount.negate(), includeZeroAmount = true)
            } else {
                record.extrasSale.add(totalAmount, includeZeroAmount = true)
                record.tax1.add(tax1Amount)
                record.tax1Discount.add(tax1DiscountAmount)
                record.tax2.add(tax2Amount)
                record.tip.add(tipAmount)
            }
        }

        TransactionType.QUOTA_SALE -> {
            if (transaction.returnStatus == ReturnStatus.Voided) {
                record.voidedQuotasSale.add(totalAmount.negate(), includeZeroAmount = true)
            } else {
                record.quotasSale.add(totalAmount, includeZeroAmount = true)
                record.tax1.add(tax1Amount)
                record.tax1Discount.add(tax1DiscountAmount)
                record.tax2.add(tax2Amount)
                record.tip.add(tipAmount)
            }
        }

        TransactionType.LOYALTY_SALE -> {
            if (transaction.returnStatus == ReturnStatus.Voided) {
                record.voidedLoyaltySale.add(totalAmount.negate(), includeZeroAmount = true)
            } else {
                record.loyaltySale.add(totalAmount, includeZeroAmount = true)
                record.tax1.add(tax1Amount)
                record.tax1Discount.add(tax1DiscountAmount)
                record.tax2.add(tax2Amount)
                record.tip.add(tipAmount)
            }
        }

        in REFUND_TYPES -> {
            if (transaction.returnStatus == ReturnStatus.Voided) {
                record.voidedRefund.add(totalAmount, includeZeroAmount = true)
            } else {
                record.refund.add(totalAmount.negate(), includeZeroAmount = true)
            }
        }

        TransactionType.PAYMENT -> {
            if (transaction.returnStatus == ReturnStatus.Voided) {
                record.voidedPayment.add(totalAmount, includeZeroAmount = true)
            } else {
                record.payment.add(totalAmount.negate(), includeZeroAmount = true)
            }
        }

        else -> {
            // Intentionally ignored: the totals report only accounts for retail, loyalty,
            // cash, refund and payment transactions.
        }
    }
}

private fun finalizeRecord(record: TotalsRecord) {
    record.credits.count = record.payment.count + record.refund.count
    record.credits.amount = record.payment.amount.add(record.refund.amount).ensureScale()

    record.debits.count =
        record.sales.count +
            record.cash.count +
            record.extrasSale.count +
            record.quotasSale.count +
            record.loyaltySale.count

    record.debits.amount = listOf(
        record.sales.amount,
        record.cash.amount,
        record.extrasSale.amount,
        record.quotasSale.amount,
        record.loyaltySale.amount,
    ).fold(ZERO) { acc, value -> acc.add(value) }.ensureScale()
}

private fun createAcquirerRecord(acquirerId: String, acquirer: TMS_Acquirer?): TotalsRecord {
    val name = acquirer?.AcquirerName?.takeIf { it.isNotBlank() }
        ?: acquirerId.takeIf { it.isNotBlank() }
        ?: UNKNOWN_ACQUIRER_NAME
    val currencyCode = acquirer?.CurrencyCode?.toCurrencyCode() ?: TERMINAL_CURRENCY_CODE
    val currencySymbol = acquirer?.Currency ?: TERMINAL_CURRENCY_SYMBOL
    val id = acquirer?.AcqID?.takeIf { it.isNotBlank() } ?: acquirerId.ifBlank { UNKNOWN_ACQUIRER_NAME }
    return TotalsRecord(
        id = id,
        name = name,
        currencyCode = currencyCode,
        currencySymbol = currencySymbol,
    )
}

private fun createIssuerRecord(
    acquirerRecord: TotalsRecord,
    issuer: TMS_Issuer?,
    transaction: Transaction?,
): TotalsRecord {
    val issuerId = issuer?.IssuID?.takeIf { it.isNotBlank() }
        ?: transaction?.issuerId?.takeIf { it.isNotBlank() }
        ?: UNKNOWN_ISSUER_ID
    val issuerName = issuer?.IssuerName?.takeIf { it.isNotBlank() }
        ?: transaction?.cardRangeName?.takeIf { it.isNotBlank() }
        ?: transaction?.issuerId?.takeIf { it.isNotBlank() }
        ?: UNKNOWN_ISSUER_NAME
    return TotalsRecord(
        id = issuerId,
        name = issuerName,
        currencyCode = acquirerRecord.currencyCode,
        currencySymbol = acquirerRecord.currencySymbol,
    )
}

private fun String?.toAmount(): BigDecimal {
    if (this.isNullOrBlank()) return ZERO
    val sanitized = this.replace(",", "").trim()
    val parsed = sanitized.toBigDecimalOrNull() ?: return ZERO
    return parsed.ensureScale()
}

private fun BigDecimal.ensureScale(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

private fun Long.toCurrencyCode(): String = toString().padStart(3, '0')
