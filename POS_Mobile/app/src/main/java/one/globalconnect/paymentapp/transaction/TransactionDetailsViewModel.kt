package one.globalconnect.paymentapp.transaction

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.navigation.TRANSACTION_ID_KEY
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.records.applyFormattedTimes
import one.globalconnect.paymentapp.signature.SignatureRepository
import one.globalconnect.paymentapp.transactions.TransactionReportBridge
import one.globalconnect.paymentapp.transactions.TransactionReportEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

class TransactionDetailsViewModel(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val profileRepository: ProfileRepository,
    private val signatureRepository: SignatureRepository,
    private val context: Context,
    private val tmsDatabase: TMSDATA,
) : ViewModel() {

    val transactionId = savedStateHandle[TRANSACTION_ID_KEY] ?: ""

    private val _transactionFlow = MutableStateFlow(Transaction().applyFormattedTimes())
    val transactionFlow: StateFlow<Transaction> = _transactionFlow

    private val _returnUiState = MutableStateFlow<ReturnUiState>(ReturnUiState.None())
    val returnUiState: StateFlow<ReturnUiState> = _returnUiState

    private val returnProcessor = TransactionReturnProcessor(context)

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                transactionRepository.getTransactionFlowFromId(id = transactionId.toInt()).collect {
                    _transactionFlow.value = it.applyFormattedTimes()
                }
            }
        }
    }

    fun printReceipt() {
        viewModelScope.launch {
            printTransactionReceipt(
                transaction = _transactionFlow.value,
                profileRepository = profileRepository,
                signatureRepository = signatureRepository,
            )
        }
    }


    fun startReturn() {
        _returnUiState.value = ReturnUiState.Loading(context.getString(R.string.msg_processing))
        val transaction: Transaction = _transactionFlow.value
        viewModelScope.launch {
            when (transaction.type) {
                TransactionType.SALE -> {
                    val result = returnTransaction(transaction, ReturnAction.VOID)
                    if (result.isSuccess) {
                        withContext(Dispatchers.IO) {
                            transactionRepository.update(result.transaction)
                        }
                        TransactionReportBridge.reportTransaction(
                            context,
                            result.transaction,
                            tmsDatabase,
                            TransactionReportEvent.Void,
                        )
                    }
                    _returnUiState.value =
                        ReturnUiState.ResultReady(result.isSuccess, result.message)
                }

                TransactionType.AUTHONLY -> {
                    val returnResult: ReturnResult =
                        returnTransaction(transaction, ReturnAction.REVERSAL)
                    if (returnResult.isSuccess) {
                        withContext(Dispatchers.IO) {
                            transactionRepository.update(returnResult.transaction)
                        }
                        TransactionReportBridge.reportTransaction(
                            context,
                            returnResult.transaction,
                            tmsDatabase,
                            TransactionReportEvent.Reversal,
                        )
                    }

                    _returnUiState.value =
                        ReturnUiState.ResultReady(returnResult.isSuccess, returnResult.message)
                }

                TransactionType.REFUND -> {
                    val returnResult: ReturnResult =
                        returnTransaction(transaction, ReturnAction.VOID)
                    if (returnResult.isSuccess) {
                        withContext(Dispatchers.IO) {
                            transactionRepository.update(returnResult.transaction)
                        }
                        TransactionReportBridge.reportTransaction(
                            context,
                            returnResult.transaction,
                            tmsDatabase,
                            TransactionReportEvent.Void,
                        )
                    }
                    _returnUiState.value =
                        ReturnUiState.ResultReady(returnResult.isSuccess, returnResult.message)
                }

                else -> {
                    Log.d("Return", "Unexpected type: ${transaction.type}")
                    _returnUiState.value = ReturnUiState.ResultReady(
                        false,
                        "Unexpected transaction type: ${transaction.type}"
                    )
                }
            }
        }
    }

    fun resetReturnUiState() {
        _returnUiState.value = ReturnUiState.None()
    }

    suspend fun returnTransaction(
        transaction: Transaction,
        returnAction: ReturnAction
    ): ReturnResult {
        return returnProcessor.execute(transaction, returnAction) { status ->
            _returnUiState.value = status
        }
    }

    fun confirmTip(newTipAmount: String) {
        val transaction = _transactionFlow.value
        val newTotal = BigDecimal(transaction.subTotal).add(BigDecimal(newTipAmount))
            .setScale(2, RoundingMode.HALF_DOWN).toPlainString()
        val checkStatus = if (transaction.checkStatus == CheckStatus.NeedTip) CheckStatus.Open else transaction.checkStatus
        _transactionFlow.value =
            _transactionFlow.value.copy(
                tipAmount = newTipAmount,
                totalAmount = newTotal,
                checkStatus = checkStatus
            ).applyFormattedTimes()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                transactionRepository.update(_transactionFlow.value)
            }
            TransactionReportBridge.reportTransaction(
                context,
                _transactionFlow.value,
                tmsDatabase,
                TransactionReportEvent.TipAdjust,
            )
        }
    }
}

data class ReturnResult(
    val isSuccess: Boolean,
    val message: String = "",
    val transaction: Transaction = Transaction()
)

enum class ReturnAction {
    VOID,

    //  REFUND,
    REVERSAL
}

sealed class ReturnUiState(val isSuccess: Boolean, val message: String = "") {
    class Loading(message: String) : ReturnUiState(false, message = message)

    class ResultReady(isSuccess: Boolean, message: String) : ReturnUiState(isSuccess, message)

    class None() : ReturnUiState(false, "")
}
