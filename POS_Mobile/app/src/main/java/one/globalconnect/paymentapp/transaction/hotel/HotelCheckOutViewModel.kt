package one.globalconnect.paymentapp.transaction.hotel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.transaction.CheckStatus
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.transaction.TransactionType
import one.globalconnect.tms.paymentapp.TMSDATA
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckOutUiState(
    val folioInput: String = "",
    val matchedCheckIn: Transaction? = null,
    val errorMessage: String? = null,
    val isSearching: Boolean = false,
    val folioInputMode: FolioInputMode = FolioInputMode.Numeric,
    val step: CheckOutStep = CheckOutStep.Folio,
)

enum class CheckOutStep {
    Loading,
    Folio,
    Amount,
}

class HotelCheckOutViewModel(
    private val transactionRepository: TransactionRepository,
    tmsDatabase: TMSDATA,
) : ViewModel() {

    private var initialSelectionHandled = false

    private val _uiState = MutableStateFlow(
        CheckOutUiState(
            folioInputMode = FolioInputMode.fromTms(
                tmsDatabase.Terminal.firstOrNull()?.folioInputMode.orEmpty(),
            ),
        ),
    )
    val uiState: StateFlow<CheckOutUiState> = _uiState.asStateFlow()

    fun updateFolioInput(value: String) {
        val sanitized = FolioRules.sanitize(value, _uiState.value.folioInputMode)
        _uiState.update { it.copy(folioInput = sanitized, errorMessage = null) }
    }

    fun removeLastFolioCharacter() {
        updateFolioInput(_uiState.value.folioInput.dropLast(1))
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                matchedCheckIn = null,
                errorMessage = null,
                step = CheckOutStep.Folio,
            )
        }
    }

    fun initializeSelectedCheckIn(checkInId: Int?) {
        if (initialSelectionHandled) return
        initialSelectionHandled = true
        if (checkInId == null) return

        _uiState.update { it.copy(step = CheckOutStep.Loading, isSearching = true) }
        viewModelScope.launch {
            val checkIn = transactionRepository.getTransactionFromId(checkInId)
                ?.takeIf { transaction ->
                    transaction.type == TransactionType.CHECKIN &&
                        transaction.checkStatus == CheckStatus.Open
                }
            _uiState.update {
                if (checkIn == null) {
                    it.copy(
                        step = CheckOutStep.Folio,
                        isSearching = false,
                        errorMessage = GlobalConnectPaymentApplication.instance.getString(
                            R.string.hotel_check_out_not_found,
                        ),
                    )
                } else {
                    it.copy(
                        folioInput = checkIn.folioNumber,
                        matchedCheckIn = checkIn,
                        step = CheckOutStep.Amount,
                        isSearching = false,
                        errorMessage = null,
                    )
                }
            }
        }
    }

    fun searchForCheckIn() {
        val folio = FolioRules.normalize(_uiState.value.folioInput, _uiState.value.folioInputMode)
        if (folio.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = GlobalConnectPaymentApplication.instance.getString(R.string.hotel_folio_required))
            }
            return
        }
        _uiState.update { it.copy(folioInput = folio) }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null) }
            val result = transactionRepository.getOpenCheckInByFolio(folio)
            _uiState.update {
                if (result == null) {
                    it.copy(
                        matchedCheckIn = null,
                        isSearching = false,
                        errorMessage = GlobalConnectPaymentApplication.instance.getString(R.string.hotel_check_out_not_found),
                    )
                } else {
                    it.copy(
                        matchedCheckIn = result,
                        step = CheckOutStep.Amount,
                        isSearching = false,
                        errorMessage = null,
                    )
                }
            }
        }
    }

}
