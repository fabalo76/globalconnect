package one.globalconnect.paymentapp.settlement.storage

import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals

/**
 * Captures the outcome of a settlement for later reprinting.
 */
data class SettlementSnapshot(
    val acquirerId: String,
    val acquirerName: String,
    val merchantId: String?,
    val terminalId: String?,
    val currencySymbol: String,
    val totals: ReconciliationTotals,
    val transactions: List<Transaction>,
    val completedAt: String,
    val batchNumber: String? = null,
    val responseCode: String = "",
)
