package one.globalconnect.paymentapp.transaction.hotel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.records.applyFormattedTimes
import one.globalconnect.paymentapp.signature.SignatureRepository
import one.globalconnect.paymentapp.transaction.ReturnAction
import one.globalconnect.paymentapp.transaction.ReturnUiState
import one.globalconnect.paymentapp.transaction.ReturnUiState.Loading
import one.globalconnect.paymentapp.transaction.ReturnUiState.ResultReady
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionReturnProcessor
import one.globalconnect.paymentapp.transaction.printTransactionReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HotelCheckInReportUiState(
    val searchTerm: String = "",
    val isLoading: Boolean = true,
    val entries: List<Transaction> = emptyList(),
    val actionStatus: ReturnUiState? = null,
    val isPrinting: Boolean = false,
)

sealed class HotelCheckInReportEvent {
    data class ShowMessage(val message: String) : HotelCheckInReportEvent()
}

class HotelCheckInReportViewModel(
    private val transactionRepository: TransactionRepository,
    private val profileRepository: ProfileRepository,
    private val signatureRepository: SignatureRepository,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HotelCheckInReportUiState())
    val uiState: StateFlow<HotelCheckInReportUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HotelCheckInReportEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HotelCheckInReportEvent> = _events.asSharedFlow()

    private val allCheckIns = MutableStateFlow<List<Transaction>>(emptyList())
    private val returnProcessor = TransactionReturnProcessor(context)

    init {
        viewModelScope.launch {
            transactionRepository.getOpenCheckIns()
                .onStart { _uiState.update { it.copy(isLoading = true) } }
                .collect { transactions ->
                    val sorted = transactions
                        .map { it.applyFormattedTimes() }
                        .sortedByDescending { it.localDateTime }
                    allCheckIns.value = sorted
                    _uiState.update {
                        it.copy(
                            entries = filterTransactions(sorted, it.searchTerm),
                            isLoading = false,
                        )
                    }
                }
        }
    }

    fun updateSearchTerm(value: String) {
        val sanitized = value.filter { it.isDigit() }.take(MAX_SEARCH_LENGTH)
        _uiState.update {
            it.copy(
                searchTerm = sanitized,
                entries = filterTransactions(allCheckIns.value, sanitized),
            )
        }
    }

    fun reprint(transaction: Transaction) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPrinting = true) }
            runCatching {
                printTransactionReceipt(
                    transaction = transaction,
                    profileRepository = profileRepository,
                    signatureRepository = signatureRepository,
                )
            }.onSuccess {
                val message = context.getString(R.string.hotel_check_in_report_printing)
                _events.tryEmit(HotelCheckInReportEvent.ShowMessage(message))
            }.onFailure { throwable ->
                val message = throwable.localizedMessage
                    ?: context.getString(R.string.hotel_check_in_report_print_failed)
                _events.tryEmit(HotelCheckInReportEvent.ShowMessage(message))
            }
            _uiState.update { it.copy(isPrinting = false) }
        }
    }

    fun showIncrementPlaceholder() {
        _events.tryEmit(
            HotelCheckInReportEvent.ShowMessage(
                context.getString(R.string.hotel_check_in_report_increment_placeholder)
            )
        )
    }

    fun voidCheckIn(transaction: Transaction) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionStatus = Loading(context.getString(R.string.hotel_check_in_report_void_in_progress))) }
            val result = returnProcessor.execute(transaction, ReturnAction.REVERSAL) { status ->
                _uiState.update { current -> current.copy(actionStatus = status) }
            }
            if (result.isSuccess) {
                withContext(Dispatchers.IO) { transactionRepository.update(result.transaction) }
            }
            val message = result.message.ifBlank {
                if (result.isSuccess) {
                    context.getString(R.string.hotel_check_in_report_void_success)
                } else {
                    context.getString(R.string.hotel_check_in_report_void_failed)
                }
            }
            _uiState.update { it.copy(actionStatus = ResultReady(result.isSuccess, message)) }
            _events.tryEmit(HotelCheckInReportEvent.ShowMessage(message))
        }
    }

    fun clearActionStatus() {
        _uiState.update { it.copy(actionStatus = null) }
    }

    private fun filterTransactions(entries: List<Transaction>, term: String): List<Transaction> {
        if (term.isBlank()) return entries
        return entries.filter { transaction ->
            val folioMatches = transaction.folioNumber.contains(term, ignoreCase = true)
            val maskedDigits = transaction.masked_cardNumber.filter { it.isDigit() }
            val lastFourMatches = maskedDigits.takeLast(4).contains(term)
            folioMatches || lastFourMatches
        }
    }

    companion object {
        private const val MAX_SEARCH_LENGTH = 12
    }
}

