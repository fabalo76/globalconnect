package com.uic.uicpaymentapp.transaction.hotel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.tms.payment_app.TMSDATA
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.uicpos.pos.host.FolioNumberProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckInUiState(
    val folioNumber: String = "",
    val autoFolio: Boolean = false,
    val errorMessage: String? = null,
)

class HotelCheckInViewModel(
    private val tmsDatabase: TMSDATA,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckInUiState())
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val autoFolio = tmsDatabase.Terminal.firstOrNull()?.AutoFolio == true
            val folio = if (autoFolio) FolioNumberProvider.nextFolioNumber() else ""
            _uiState.value = CheckInUiState(
                folioNumber = folio,
                autoFolio = autoFolio,
            )
        }
    }

    fun updateManualFolio(value: String) {
        val sanitized = value.filter { it.isDigit() }.take(MAX_FOLIO_LENGTH)
        _uiState.update { it.copy(folioNumber = sanitized, errorMessage = null) }
    }

    fun validateFolio(): Boolean {
        val folio = _uiState.value.folioNumber
        if (folio.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = UICApplication.instance.getString(R.string.hotel_folio_required))
            }
            return false
        }
        return true
    }

    fun folioForSubmission(): String = _uiState.value.folioNumber

    companion object {
        private const val MAX_FOLIO_LENGTH = 12
    }
}
