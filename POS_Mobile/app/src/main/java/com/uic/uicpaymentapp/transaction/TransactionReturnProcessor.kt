package com.uic.uicpaymentapp.transaction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.transaction.ReturnAction.REVERSAL
import com.uic.uicpaymentapp.transaction.ReturnAction.VOID
import com.uic.uicpaymentapp.transaction.messageIdToTerminalMessage
import com.uic.uicpaymentapp.transaction.statusCodeToTerminalMessage
import com.uic.uicpaymentapp.uicpos.pos.controller.TransReversal
import com.uic.uicpaymentapp.uicpos.pos.controller.TransVoid
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ReturnProcessor"

/**
 * Shared helper that encapsulates the host communication required to execute voids or reversals.
 * The class mirrors the logic that previously lived in [TransactionDetailsViewModel] so new
 * features can trigger the same flow without duplicating implementation details.
 */
class TransactionReturnProcessor(private val context: Context = UICApplication.instance) {

    suspend fun execute(
        transaction: Transaction,
        returnAction: ReturnAction,
        onStatusUpdate: (ReturnUiState) -> Unit = {},
    ): ReturnResult {
        val deferred = CompletableDeferred<ReturnResult>()

        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.obj.toString().isNotBlank()) {
                    val result = messageIdToTerminalMessage(msg, context)
                    Log.d(TAG, "Message received: ${result.message}")
                    when (result) {
                        is TerminalMessage.PROMPT -> onStatusUpdate(ReturnUiState.Loading(result.message))
                        is TerminalMessage.CONNECTIONFAILED,
                        is TerminalMessage.FAILED,
                        is TerminalMessage.RETRY -> completeIfNeeded(
                            deferred,
                            ReturnResult(false, result.message, transaction),
                        )

                        else -> Unit
                    }
                }
                super.handleMessage(msg)
            }
        }

        val txnHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val procInfo = msg.obj as? ProcInfo
                val statusCode = procInfo?.RespCmd?.StatusCode
                if (statusCode != null) {
                    val terminalMessage = statusCodeToTerminalMessage(statusCode, context)
                    Log.d(TAG, "Status code received: ${terminalMessage.message}")
                    when (terminalMessage) {
                        is TerminalMessage.PROMPT -> onStatusUpdate(ReturnUiState.Loading(terminalMessage.message))
                        is TerminalMessage.CONNECTIONFAILED,
                        is TerminalMessage.FAILED,
                        is TerminalMessage.RETRY -> completeIfNeeded(
                            deferred,
                            ReturnResult(false, terminalMessage.message, transaction),
                        )

                        is TerminalMessage.SUCCESS -> {
                            when (procInfo.TransLog.TxnResult) {
                                "02" -> {
                                    transaction.checkStatus = CheckStatus.Closed
                                    transaction.returnStatus = ReturnStatus.Voided
                                    val message = "$returnAction completed successfully"
                                    completeIfNeeded(
                                        deferred,
                                        ReturnResult(true, message, transaction),
                                    )
                                }

                                "03" -> {
                                    val message = context.getString(R.string.err_void_window_expired)
                                    completeIfNeeded(
                                        deferred,
                                        ReturnResult(false, message, transaction),
                                    )
                                }

                                else -> completeIfNeeded(
                                    deferred,
                                    ReturnResult(false, terminalMessage.message, transaction),
                                )
                            }
                        }

                        else -> Unit
                    }
                }
                super.handleMessage(msg)
            }
        }

        withContext(Dispatchers.IO) {
            when (returnAction) {
                VOID -> TransVoid(handler, txnHandler, transaction).run()
                REVERSAL -> TransReversal(handler, transaction, txnHandler).run()
            }
        }

        return deferred.await()
    }

    private fun completeIfNeeded(
        deferred: CompletableDeferred<ReturnResult>,
        result: ReturnResult,
    ) {
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }
}

