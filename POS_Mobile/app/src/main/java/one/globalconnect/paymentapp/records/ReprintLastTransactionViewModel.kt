package one.globalconnect.paymentapp.records

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.signature.SignatureRepository
import one.globalconnect.paymentapp.transaction.printTransactionReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReprintLastTransactionViewModel(
    private val transactionRepository: TransactionRepository,
    private val profileRepository: ProfileRepository,
    private val signatureRepository: SignatureRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReprintLastTransactionUiState>(
        ReprintLastTransactionUiState.Printing,
    )
    val uiState: StateFlow<ReprintLastTransactionUiState> = _uiState.asStateFlow()
    private var hasStarted = false

    init {
        reprint()
    }

    fun reprint() {
        if (_uiState.value == ReprintLastTransactionUiState.Printing && hasStarted) return
        hasStarted = true
        _uiState.value = ReprintLastTransactionUiState.Printing
        viewModelScope.launch {
            try {
                val transaction = transactionRepository.getLastTransaction()
                if (transaction == null) {
                    _uiState.value = ReprintLastTransactionUiState.NoTransactions
                    return@launch
                }
                printTransactionReceipt(
                    transaction = transaction,
                    profileRepository = profileRepository,
                    signatureRepository = signatureRepository,
                )
                _uiState.value = ReprintLastTransactionUiState.Printed
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Unable to reprint the last transaction", error)
                _uiState.value = ReprintLastTransactionUiState.Failed
            }
        }
    }

    private companion object {
        private const val TAG = "ReprintLastTransaction"
    }
}

sealed interface ReprintLastTransactionUiState {
    data object Printing : ReprintLastTransactionUiState
    data object Printed : ReprintLastTransactionUiState
    data object NoTransactions : ReprintLastTransactionUiState
    data object Failed : ReprintLastTransactionUiState
}
