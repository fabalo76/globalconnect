package one.globalconnect.paymentapp.transaction.hotel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.uicpos.pos.host.FolioNumberProvider
import one.globalconnect.paymentapp.dao.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckInUiState(
    val folioNumber: String = "",
    val autoFolio: Boolean = false,
    val checkInType: HotelCheckInType = HotelCheckInType.ByFolio,
    val folioInputMode: FolioInputMode = FolioInputMode.Numeric,
    val step: CheckInStep = CheckInStep.Loading,
    val isCheckingFolio: Boolean = false,
    val errorMessage: String? = null,
)

enum class CheckInStep {
    Loading,
    Folio,
    Amount,
}

class HotelCheckInViewModel(
    private val tmsDatabase: TMSDATA,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckInUiState())
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    init {
        val terminal = tmsDatabase.Terminal.firstOrNull()
        val checkInType = HotelCheckInType.fromTms(terminal?.checkInType.orEmpty())
        val inputMode = FolioInputMode.fromTms(terminal?.folioInputMode.orEmpty())
        val autoFolio = terminal?.AutoFolio == true && checkInType.requiresFolio

        when {
            !checkInType.requiresFolio -> {
                _uiState.value = CheckInUiState(
                    checkInType = checkInType,
                    folioInputMode = inputMode,
                    step = CheckInStep.Amount,
                )
            }

            autoFolio -> prepareAutomaticFolio(checkInType, inputMode)

            else -> {
                _uiState.value = CheckInUiState(
                    checkInType = checkInType,
                    folioInputMode = inputMode,
                    step = CheckInStep.Folio,
                )
            }
        }
    }

    fun updateManualFolio(value: String) {
        val sanitized = FolioRules.sanitize(value, _uiState.value.folioInputMode)
        _uiState.update { it.copy(folioNumber = sanitized, errorMessage = null) }
    }

    fun removeLastFolioCharacter() {
        updateManualFolio(_uiState.value.folioNumber.dropLast(1))
    }

    fun continueFromFolio() {
        val currentState = _uiState.value
        if (currentState.isCheckingFolio) return

        val folio = FolioRules.normalize(currentState.folioNumber, currentState.folioInputMode)
        if (folio.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = GlobalConnectPaymentApplication.instance.getString(R.string.hotel_folio_required))
            }
            return
        }

        _uiState.update { it.copy(folioNumber = folio, isCheckingFolio = true, errorMessage = null) }
        viewModelScope.launch {
            val existingCheckIn = transactionRepository.getOpenCheckInByFolio(folio)
            _uiState.update {
                if (existingCheckIn == null) {
                    it.copy(step = CheckInStep.Amount, isCheckingFolio = false)
                } else {
                    it.copy(
                        isCheckingFolio = false,
                        errorMessage = GlobalConnectPaymentApplication.instance.getString(R.string.hotel_folio_exists),
                    )
                }
            }
        }
    }

    fun folioForSubmission(): String = _uiState.value.folioNumber

    private fun prepareAutomaticFolio(
        checkInType: HotelCheckInType,
        inputMode: FolioInputMode,
    ) {
        _uiState.value = CheckInUiState(
            autoFolio = true,
            checkInType = checkInType,
            folioInputMode = inputMode,
            step = CheckInStep.Loading,
            isCheckingFolio = true,
        )
        viewModelScope.launch {
            val folio = generateAvailableAutomaticFolio()
            _uiState.update {
                it.copy(
                    folioNumber = folio,
                    step = CheckInStep.Amount,
                    isCheckingFolio = false,
                )
            }
        }
    }

    private suspend fun generateAvailableAutomaticFolio(): String {
        repeat(MAX_AUTO_FOLIO_ATTEMPTS) {
            val candidate = FolioNumberProvider.nextFolioNumber()
            if (transactionRepository.getOpenCheckInByFolio(candidate) == null) return candidate
        }
        return FolioNumberProvider.nextFolioNumber()
    }

    companion object {
        private const val MAX_AUTO_FOLIO_ATTEMPTS = 100
    }
}
