package one.globalconnect.paymentapp.records

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.calcTotals
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.uicpos.pos.model.ReconciliationTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportViewModel(
    val transactionRepository: TransactionRepository,
    profileRepository: ProfileRepository,
    private val tmsDatabase: TMSDATA,
) : ViewModel() {
    private val _summaryUiState = MutableStateFlow(ReconciliationTotals())
    val summaryUiState: StateFlow<ReconciliationTotals> = _summaryUiState

    private var profile = MutableStateFlow(Profile())

    private val batchTransactions: StateFlow<QueryUiState> = run {
        transactionRepository.getAllTransactionsStream(
        ).catch { exception ->
            Log.d("TransactionHistoryViewModel", exception.message.toString())
        }.map {
            QueryUiState(it)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(TransactionHistoryViewModel.TIMEOUT_MILLIS),
            initialValue = QueryUiState()
        )
    }

    init {
        viewModelScope.launch {
            batchTransactions.collect {
                _summaryUiState.value = ReconciliationTotals(
                    totals = calcTotals(it.queryList, tmsDatabase).terminal,
                )
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
            transactions = batchTransactions.value.queryList,
            printTransactions = printTransactions,
            profile = profile.value,
            tmsDatabase = tmsDatabase,
        )
    }
}


data class QueryUiState(var queryList: List<Transaction> = listOf(), val isLoading: Boolean = true)



