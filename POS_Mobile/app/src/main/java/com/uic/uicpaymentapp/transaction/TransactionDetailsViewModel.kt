package com.uic.uicpaymentapp.transaction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uic.tms.payment_app.TMSDATA
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.dao.TransactionRepository
import com.uic.uicpaymentapp.navigation.TRANSACTION_ID_KEY
import com.uic.uicpaymentapp.profile.Profile
import com.uic.uicpaymentapp.profile.profiledao.ProfileRepository
import com.uic.uicpaymentapp.records.applyFormattedTimes
import com.uic.uicpaymentapp.signature.SignatureRepository
import com.uic.uicpaymentapp.transactions.TransactionReportBridge
import com.uic.uicpaymentapp.transactions.TransactionReportEvent
import com.uic.uicpaymentapp.uicpos.pos.controller.TransCancelTxn
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import kotlinx.coroutines.CompletableDeferred
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
        _returnUiState.value = ReturnUiState.Loading("PROCESSING")
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

    fun cancelTransaction() {
        val cancelHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.obj.toString().isNotBlank() && msg.obj.toString() != "37") {
                    Log.d("MsgHandler", messageIdToTerminalMessage(msg, context).message)

                }
                super.handleMessage(msg)
            }
        }

        val txnCancelHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.obj != null) {
                    val procInfo = msg.obj as ProcInfo
                    val statusCode = procInfo.RespCmd.StatusCode
                    if (statusCode != null) {
                        Log.d("TxnHandler", statusCodeToTerminalMessage(statusCode, context).message)
                    }
                }
                super.handleMessage(msg)
            }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                TransCancelTxn(cancelHandler, txnCancelHandler).run()
            }
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
