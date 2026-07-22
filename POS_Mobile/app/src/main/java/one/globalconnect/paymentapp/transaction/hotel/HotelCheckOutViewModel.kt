package one.globalconnect.paymentapp.transaction.hotel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.transaction.Transaction
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
)

class HotelCheckOutViewModel(
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckOutUiState())
    val uiState: StateFlow<CheckOutUiState> = _uiState.asStateFlow()

    fun updateFolioInput(value: String) {
        val sanitized = value.filter { it.isDigit() }.take(MAX_FOLIO_LENGTH)
        _uiState.update { it.copy(folioInput = sanitized, errorMessage = null) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(matchedCheckIn = null, errorMessage = null) }
    }

    fun searchForCheckIn() {
        val folio = _uiState.value.folioInput
        if (folio.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = GlobalConnectPaymentApplication.instance.getString(R.string.hotel_folio_required))
            }
            return
        }
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
                    it.copy(matchedCheckIn = result, isSearching = false, errorMessage = null)
                }
            }
        }
    }

    companion object {
        private const val MAX_FOLIO_LENGTH = 12
    }
}
