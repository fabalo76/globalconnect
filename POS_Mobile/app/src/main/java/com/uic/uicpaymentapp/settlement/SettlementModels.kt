package com.uic.uicpaymentapp.settlement

import com.uic.uicpaymentapp.transaction.Transaction
import com.uic.uicpaymentapp.uicpos.pos.model.ReconciliationTotals

object SettlementConstants {
    const val ALL_ACQUIRERS_ID: String = "__ALL_ACQUIRERS__"
    const val DEFAULT_CURRENCY_SYMBOL: String = "$"
}

data class SettlementAcquirerOption(
    val id: String,
    val name: String,
    val merchantId: String?,
    val terminalId: String?,
    val currencySymbol: String,
    val isAll: Boolean = false,
)

data class SettlementTarget(
    val option: SettlementAcquirerOption,
    val totals: ReconciliationTotals,
    val transactions: List<Transaction>,
)

data class SettlementRequest(
    val targets: List<SettlementTarget>,
)
