package one.globalconnect.paymentapp.settlement

import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals
import java.util.Currency
import java.util.Locale

fun buildAllAcquirersSettlementRequest(
    database: TMSDATA,
    transactions: List<Transaction>,
): SettlementRequest? {
    val acquirerOptions = buildSettlementAcquirerOptions(database).filterNot(SettlementAcquirerOption::isAll)
    if (acquirerOptions.isEmpty()) {
        return null
    }

    val groupedTransactions = transactions
        .filter { it.type != TransactionType.SETTLEMENT && it.acquirerId.isNotBlank() }
        .groupBy { it.acquirerId }

    val targets = acquirerOptions.mapNotNull { option ->
        val acquirerTransactions = groupedTransactions[option.id].orEmpty()
        if (acquirerTransactions.isEmpty()) {
            null
        } else {
            SettlementTarget(
                option = option,
                totals = calculateReconciliationTotals(acquirerTransactions),
                transactions = acquirerTransactions,
            )
        }
    }

    return targets.takeIf { it.isNotEmpty() }?.let(::SettlementRequest)
}

fun buildSettlementAcquirerOptions(database: TMSDATA): List<SettlementAcquirerOption> {
    val options = database.Acquirer.map { acquirer ->
        SettlementAcquirerOption(
            id = acquirer.AcqID,
            name = acquirer.AcquirerName.takeIf { it.isNotBlank() } ?: acquirer.AcqID,
            merchantId = acquirer.MerchID.takeIf { it.isNotBlank() },
            terminalId = acquirer.AcqTermID.takeIf { it.isNotBlank() },
            currencySymbol = resolveCurrencySymbol(acquirer),
        )
    }.sortedBy { it.name.lowercase(Locale.getDefault()) }

    if (options.isEmpty()) {
        return options
    }

    val allOption = SettlementAcquirerOption(
        id = SettlementConstants.ALL_ACQUIRERS_ID,
        name = "",
        merchantId = null,
        terminalId = null,
        currencySymbol = options.first().currencySymbol,
        isAll = true,
    )

    return listOf(allOption) + options
}

private fun resolveCurrencySymbol(acquirer: TMS_Acquirer): String {
    val explicit = acquirer.Currency.trim()
    if (explicit.length == 3 && explicit.all { it.isLetter() }) {
        return runCatching { Currency.getInstance(explicit).symbol }
            .getOrDefault(SettlementConstants.DEFAULT_CURRENCY_SYMBOL)
    }
    if (explicit.isNotEmpty()) {
        return explicit
    }
    return SettlementConstants.DEFAULT_CURRENCY_SYMBOL
}
