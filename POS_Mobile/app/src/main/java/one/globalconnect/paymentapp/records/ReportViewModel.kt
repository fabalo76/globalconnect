package one.globalconnect.paymentapp.records

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.transaction.ReturnStatus
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReportViewModel(
    val transactionRepository: TransactionRepository,
    profileRepository: ProfileRepository,
    private val tmsDatabase: TMSDATA,
) : ViewModel() {
    var startDate by mutableStateOf(LocalDate.now())

    var endDate by mutableStateOf(LocalDate.now())

    private val _summaryUiState = MutableStateFlow(ReconciliationTotals())
    val summaryUiState: StateFlow<ReconciliationTotals> = _summaryUiState

    private var profile = MutableStateFlow(Profile())

    private val dateFlow = MutableStateFlow(Pair<LocalDate, LocalDate>(startDate, endDate))

    private val transactions: StateFlow<QueryUiState> = run {
        transactionRepository.getAllTransactionsStream(
        ).catch { exception ->
            Log.d("TransactionHistoryViewModel", exception.message.toString())
        }.map {
            QueryUiState(
                it.filterNot { transaction -> transaction.type == TransactionType.CHECKIN }
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(TransactionHistoryViewModel.TIMEOUT_MILLIS),
            initialValue = QueryUiState()
        )
    }

    private val _filteredFlow = MutableStateFlow(QueryUiState())
    val filteredFlow: StateFlow<QueryUiState> = _filteredFlow

    init {
        viewModelScope.launch {
            transactions.combine(dateFlow) { transactions, datePair ->
                transactions.queryList.filter { transaction ->
                    isTransactionWithinRange(transaction, datePair.first, datePair.second)
                }
            }.collect {
                _filteredFlow.value = QueryUiState(it)
                _summaryUiState.value = QueryUiState(it).toSummaryUiState()
            }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                profile.value = profileRepository.get() ?: Profile()
            }
        }
    }


    /**
     * Holds home ui state. The list of items are retrieved from [TransactionRepository] and mapped to
     * [QueryUiState]
     */


    fun printReport(context: Context, printTransactions: Boolean) {
        Log.d("Report", "Manufacturer: ${Build.MANUFACTURER} Model: ${Build.MODEL}")
        val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
        paymentPrinter.printReport(
            context = context,
            transactions = filteredFlow.value.queryList,
            printTransactions = printTransactions,
            profile = profile.value,
            tmsDatabase = tmsDatabase,
            startDateTime = startDate.atStartOfDay(),
            endDateTime = endDate.atTime(LocalTime.MAX),
        )
    }
    fun applyDateFilter(startDateString: String, endDateString: String) {
        startDate = LocalDate.parse(startDateString, dateFormatter)
        endDate = LocalDate.parse(endDateString, dateFormatter)
        dateFlow.value = Pair(startDate, endDate)
    }

    fun convertDropdownItemToDate(option: String): Pair<String, String> {
        return when (option) {
            GlobalConnectPaymentApplication.instance.resources.getString(R.string.filter_today) -> Pair(
                LocalDate.now().format(
                    dateFormatter
                ), LocalDate.now().format(dateFormatter)
            )

            GlobalConnectPaymentApplication.instance.resources.getString(R.string.filter_last_week) -> Pair(
                LocalDate.now().minusDays(7).format(
                    dateFormatter
                ), LocalDate.now().format(dateFormatter)
            )

            GlobalConnectPaymentApplication.instance.resources.getString(R.string.filter_last_month) -> Pair(
                LocalDate.now().minusDays(30).format(
                    dateFormatter
                ), LocalDate.now().format(dateFormatter)
            )

            else -> Pair(
                LocalDate.now().format(
                    dateFormatter
                ), LocalDate.now().format(dateFormatter)
            )
        }
    }

    private fun isTransactionWithinRange(
        transaction: Transaction,
        startDate: LocalDate,
        endDate: LocalDate
    ): Boolean {
        val localDateTime = LocalDateTime.parse(transaction.localDateTime, dateTimeFormatter)
        return localDateTime.isAfter(startDate.atStartOfDay()) && localDateTime.isBefore(
            endDate.atTime(
                LocalTime.MAX
            )
        )
    }
}


data class QueryUiState(var queryList: List<Transaction> = listOf(), val isLoading: Boolean = true)

fun QueryUiState.toSummaryUiState(): ReconciliationTotals {
    val summaryUiState = ReconciliationTotals()
    for (transaction in this.queryList) {
        if (transaction.returnStatus == ReturnStatus.Voided) {
            if (transaction.type == TransactionType.REFUND) {
                summaryUiState.voidedRefunds.add(amountToAdd = transaction.totalAmount.toBigDecimal(), includeZeroAmount = true)
            } else {
                summaryUiState.voidedSales.add(amountToAdd = transaction.totalAmount.toBigDecimal(), includeZeroAmount = true)
            }
        } else {
            when (transaction.type) {
                TransactionType.SALE, TransactionType.CHECKOUT -> {
                    summaryUiState.creditSales.add(amountToAdd = transaction.totalAmount.toBigDecimal(), includeZeroAmount = true)
                    summaryUiState.tip.add(amountToAdd = transaction.tipAmount.toBigDecimal(), includeZeroAmount = false)
                    summaryUiState.tax1.add(amountToAdd = transaction.tax1Amount.toBigDecimal(), includeZeroAmount = false)
                    summaryUiState.tax2.add(amountToAdd = transaction.tax2Amount.toBigDecimal(), includeZeroAmount = false)
                    summaryUiState.tax1Discount.add(amountToAdd = transaction.tax1DiscountAmount.toBigDecimal(), includeZeroAmount = false)
                }
                TransactionType.REFUND -> {
                    summaryUiState.refunds.add(amountToAdd = transaction.totalAmount.toBigDecimal(), includeZeroAmount = true)
                }
                TransactionType.AUTHONLY -> {
                    summaryUiState.authorizations.add(amountToAdd = transaction.totalAmount.toBigDecimal(), includeZeroAmount = true)
                }
                else -> Unit
            }
        }
    }
    return summaryUiState
}



