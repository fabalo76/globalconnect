package com.uic.uicpaymentapp.records

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.tms.payment_app.TMSDATA
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.dao.TransactionRepository
import com.uic.uicpaymentapp.printer.NexGoPaymentPrinter
import com.uic.uicpaymentapp.printer.PaymentPrinter
import com.uic.uicpaymentapp.profile.Profile
import com.uic.uicpaymentapp.profile.profiledao.ProfileRepository
import com.uic.uicpaymentapp.transaction.Transaction
import com.uic.uicpaymentapp.transaction.TransactionType
import com.uic.uicpaymentapp.transaction.calcTotals
import com.uic.uicpaymentapp.transaction.createPrintableTotalsReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TotalsReportViewModel(
    private val transactionRepository: TransactionRepository,
    private val profileRepository: ProfileRepository,
    private val tmsDatabase: TMSDATA,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TotalsReportUiState(
            acquirers = buildAcquirerSummaries(tmsDatabase),
        ),
    )
    val uiState: StateFlow<TotalsReportUiState> = _uiState.asStateFlow()

    private var profile: Profile? = null

    init {
        viewModelScope.launch {
            profile = withContext(Dispatchers.IO) { profileRepository.get() }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun printTotals(context: Context, acquirerId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPrinting = true, message = null, shouldNavigateBack = false) }
            try {
                val transactions = withContext(Dispatchers.IO) {
                    transactionRepository.getAllTransactionsStream().first()
                }
                val filtered = filterTransactions(transactions, acquirerId)
                if (filtered.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isPrinting = false,
                            message = UiMessage(R.string.totals_message_no_transactions),
                            shouldNavigateBack = false,
                        )
                    }
                    return@launch
                }

                val totalsResult = withContext(Dispatchers.Default) {
                    calcTotals(filtered, tmsDatabase)
                }

                val printableReport = createPrintableTotalsReport(context, totalsResult, acquirerId)
                val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter

                withContext(Dispatchers.IO) {
                    paymentPrinter.printTotalsReport(
                        context = context,
                        report = printableReport,
                        profile = profile,
                        tmsDatabase = tmsDatabase,
                    )
                }

                _uiState.update {
                    it.copy(
                        isPrinting = false,
                        message = UiMessage(R.string.totals_message_success),
                        shouldNavigateBack = true,
                    )
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to print totals report", ex)
                _uiState.update {
                    it.copy(
                        isPrinting = false,
                        message = UiMessage(R.string.totals_message_error),
                        shouldNavigateBack = false,
                    )
                }
            }
        }
    }

    fun onNavigationHandled() {
        _uiState.update { it.copy(shouldNavigateBack = false) }
    }

    private fun filterTransactions(transactions: List<Transaction>, acquirerId: String?): List<Transaction> {
        val eligible = transactions.filter { it.type != TransactionType.CHECKIN }
        return if (acquirerId.isNullOrBlank()) {
            eligible
        } else {
            eligible.filter { it.acquirerId == acquirerId }
        }
    }

    companion object {
        private const val TAG = "TotalsReportViewModel"
    }
}

data class TotalsReportUiState(
    val acquirers: List<AcquirerSummary> = emptyList(),
    val isPrinting: Boolean = false,
    val message: UiMessage? = null,
    val shouldNavigateBack: Boolean = false,
)

data class AcquirerSummary(
    val id: String,
    val name: String,
    val merchantId: String?,
    val terminalId: String?,
)

data class UiMessage(
    @param:StringRes
    @get:StringRes
    val resId: Int,
    val args: List<Any> = emptyList()
)

private fun buildAcquirerSummaries(tmsDatabase: TMSDATA): List<AcquirerSummary> {
    return tmsDatabase.Acquirer.map { acquirer ->
        AcquirerSummary(
            id = acquirer.AcqID,
            name = acquirer.AcquirerName.takeIf { it.isNotBlank() } ?: acquirer.AcqID,
            merchantId = acquirer.MerchID.takeIf { it.isNotBlank() },
            terminalId = acquirer.AcqTermID.takeIf { it.isNotBlank() },
        )
    }.sortedBy { it.name.lowercase() }
}
