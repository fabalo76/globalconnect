package one.globalconnect.paymentapp.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.settlement.SettlementAcquirerOption
import one.globalconnect.paymentapp.settlement.SettlementConstants
import one.globalconnect.paymentapp.settlement.SettlementRequest
import one.globalconnect.paymentapp.settlement.SettlementTarget
import one.globalconnect.paymentapp.settlement.calculateReconciliationTotals
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Currency
import java.util.Locale

class EndOfDayViewModel(
    private val transactionRepository: TransactionRepository,
    private val tmsDatabase: TMSDATA,
) : ViewModel() {

    private val acquirerOptions: List<SettlementAcquirerOption> = buildAcquirerOptions(tmsDatabase)
    private val defaultCurrencySymbol: String =
        acquirerOptions.firstOrNull { !it.isAll }?.currencySymbol
            ?: SettlementConstants.DEFAULT_CURRENCY_SYMBOL

    private val _uiState = MutableStateFlow(
        EndOfDayUiState(
            acquirerOptions = acquirerOptions,
            selectedAcquirerId = acquirerOptions.firstOrNull()?.id ?: SettlementConstants.ALL_ACQUIRERS_ID,
            selectedCurrencySymbol = defaultCurrencySymbol,
        ),
    )
    val uiState: StateFlow<EndOfDayUiState> = _uiState.asStateFlow()

    private var transactionsByAcquirer: Map<String, List<Transaction>> = emptyMap()

    init {
        viewModelScope.launch {
            transactionRepository.getAllTransactionsStream().collectLatest { transactions ->
                val settlementCandidates = transactions.filter { it.type != TransactionType.SETTLEMENT }
                transactionsByAcquirer = groupTransactionsByAcquirer(settlementCandidates)
                val totalsByAcquirer = calculateTotalsByAcquirer(settlementCandidates)
                val currentId = _uiState.value.selectedAcquirerId
                val totals = totalsByAcquirer[currentId]
                    ?: totalsByAcquirer[SettlementConstants.ALL_ACQUIRERS_ID]
                    ?: ReconciliationTotals()
                _uiState.value = _uiState.value.copy(
                    totalsByAcquirer = totalsByAcquirer,
                    totals = totals,
                    selectedCurrencySymbol = resolveCurrencySymbol(currentId),
                    hasTransactions = !totals.isEmpty(),
                )
            }
        }
    }

    fun selectAcquirer(acquirerId: String) {
        val totals = _uiState.value.totalsByAcquirer[acquirerId]
            ?: _uiState.value.totalsByAcquirer[SettlementConstants.ALL_ACQUIRERS_ID]
            ?: ReconciliationTotals()
        _uiState.value = _uiState.value.copy(
            selectedAcquirerId = acquirerId,
            totals = totals,
            selectedCurrencySymbol = resolveCurrencySymbol(acquirerId),
            hasTransactions = !totals.isEmpty(),
        )
    }

    fun buildSettlementRequest(): SettlementRequest? {
        val options = _uiState.value.acquirerOptions
        val totalsByAcquirer = _uiState.value.totalsByAcquirer
        val selectedId = _uiState.value.selectedAcquirerId
        val targets = if (selectedId == SettlementConstants.ALL_ACQUIRERS_ID) {
            options.filterNot { it.isAll }.mapNotNull { option ->
                val totals = totalsByAcquirer[option.id] ?: ReconciliationTotals()
                val transactions = transactionsByAcquirer[option.id].orEmpty()
                if (transactions.isEmpty()) {
                    null
                } else {
                    SettlementTarget(option, totals, transactions)
                }
            }
        } else {
            val option = options.find { it.id == selectedId } ?: return null
            val totals = totalsByAcquirer[selectedId] ?: ReconciliationTotals()
            val transactions = transactionsByAcquirer[selectedId].orEmpty()
            if (transactions.isEmpty()) {
                emptyList()
            } else {
                listOf(SettlementTarget(option, totals, transactions))
            }
        }

        if (targets.isEmpty()) {
            return null
        }
        return SettlementRequest(targets)
    }

    private fun calculateTotalsByAcquirer(transactions: List<Transaction>): Map<String, ReconciliationTotals> {
        val totalsMap = mutableMapOf<String, ReconciliationTotals>()
        acquirerOptions.filterNot(SettlementAcquirerOption::isAll).forEach { option ->
            totalsMap[option.id] = ReconciliationTotals()
        }
        val grouped = transactions
            .filter { it.acquirerId.isNotBlank() }
            .groupBy { it.acquirerId }
        grouped.forEach { (acquirerId, items) ->
            totalsMap[acquirerId] = calculateReconciliationTotals(items)
        }
        totalsMap[SettlementConstants.ALL_ACQUIRERS_ID] = calculateReconciliationTotals(transactions)
        return totalsMap
    }

    private fun groupTransactionsByAcquirer(transactions: List<Transaction>): Map<String, List<Transaction>> {
        val grouped = transactions
            .filter { it.acquirerId.isNotBlank() }
            .groupBy { it.acquirerId }
        val result = grouped.toMutableMap()
        result[SettlementConstants.ALL_ACQUIRERS_ID] = transactions.filter { it.acquirerId.isNotBlank() }
        return result
    }

    private fun resolveCurrencySymbol(acquirerId: String): String {
        if (acquirerId == SettlementConstants.ALL_ACQUIRERS_ID) {
            return defaultCurrencySymbol
        }
        return acquirerOptions.find { it.id == acquirerId }?.currencySymbol ?: defaultCurrencySymbol
    }

    private fun buildAcquirerOptions(database: TMSDATA): List<SettlementAcquirerOption> {
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
}

data class EndOfDayUiState(
    val acquirerOptions: List<SettlementAcquirerOption> = emptyList(),
    val selectedAcquirerId: String = SettlementConstants.ALL_ACQUIRERS_ID,
    val totalsByAcquirer: Map<String, ReconciliationTotals> = emptyMap(),
    val totals: ReconciliationTotals = ReconciliationTotals(),
    val selectedCurrencySymbol: String = SettlementConstants.DEFAULT_CURRENCY_SYMBOL,
    val hasTransactions: Boolean = false,
)
