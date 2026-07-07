package com.uic.uicpaymentapp.transaction

import android.content.Context
import android.os.Message
import android.util.Log
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.uicpos.pos.errcode.Constants
import com.uic.uicpaymentapp.uicpos.pos.errcode.ErrCode
import com.uic.uicpaymentapp.uicpos.pos.util.ApplSelectViewData
import com.uic.uicpaymentapp.uicpos.pos.util.TimerThread
import java.math.BigDecimal
import java.math.RoundingMode


/**
 * UI state for the Payment route
 *
 *
 */
sealed interface ProcessTransactionUiState {
    val message: String
    data class CONNECTING(override val message: String) : ProcessTransactionUiState
    data class AWAITINGCARD(override val message: String, val amount: String, val applNames: List<String> = listOf()) : ProcessTransactionUiState
    data class AUTHORIZING(override val message: String) : ProcessTransactionUiState
    data class AUTHORIZED(override val message: String) : ProcessTransactionUiState
    data class FAILED(override val message: String) : ProcessTransactionUiState
    data class RETRY(override val message: String) : ProcessTransactionUiState
    data class PARTIALAPPROVAL(override val message: String) : ProcessTransactionUiState
    data class CONNECTIONFAILED(override val message: String) : ProcessTransactionUiState
}

sealed class TerminalMessage(
    errMessage: String = "",
    val applNames: List<String> = listOf()
) {
    val message = errMessage
    class SUCCESS(message: String) : TerminalMessage(errMessage = message)
    class AUTHORIZING(message: String) : TerminalMessage(errMessage = message)
    class CONNECTING(message: String) : TerminalMessage(errMessage = message)
    class PROMPT(message: String, applNames: List<String> = listOf()) : TerminalMessage(errMessage = message, applNames = applNames)
    class FAILED(errMessage: String) : TerminalMessage(errMessage = errMessage)
    class RETRY(errMessage: String) : TerminalMessage(errMessage = errMessage)
    class CONNECTIONFAILED(errMessage: String) : TerminalMessage(errMessage = errMessage)
}

enum class StateType {
    CONNECTING,
    AWAITINGCARD,
    AUTHORIZING,
    AUTHORIZED,
    FAILED,
    RETRY,
    PARTIAL,
    CONNECTIONFAILED
}

data class PaymentViewModelState(
    val baseAmount: String,
    val tax1Amount: String,
    val tax2Amount: String,
    val tipAmount: String,
    val transactionType: TransactionType = TransactionType.ERROR,
    val state: StateType = StateType.CONNECTING,
    val error: Boolean,
    val message: String = "",
    val transactionId: String? = null,
    val approvedAmount: String? = null,
    val locked: Boolean = false,
    val applicationNames: List<String> = listOf<String>()
) {
    val totalAmount: String
        get() = try {
            (BigDecimal(baseAmount) + BigDecimal(tax1Amount) + BigDecimal(tax2Amount) + BigDecimal(tipAmount))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString()
        } catch (e: Exception) {
            "0.00" // Default value if parsing fails
        }
    fun toUiState(): ProcessTransactionUiState =
        when (state) {
            StateType.CONNECTING -> ProcessTransactionUiState.CONNECTING(message)
            StateType.AUTHORIZED -> {
                ProcessTransactionUiState.AUTHORIZED("PAYMENT SUCCESS")
            }

            StateType.AUTHORIZING -> ProcessTransactionUiState.AUTHORIZING(message)
            StateType.AWAITINGCARD -> ProcessTransactionUiState.AWAITINGCARD(message, totalAmount, applicationNames)
            StateType.FAILED -> ProcessTransactionUiState.FAILED(message)
            StateType.PARTIAL -> {
                if (baseAmount.isBlank()) {
                    ProcessTransactionUiState.FAILED("Failed to detect inputted amount")
                } else if (approvedAmount == null) {
                    ProcessTransactionUiState.FAILED("Failed to detect approved amount")
                } else {
                    ProcessTransactionUiState.PARTIALAPPROVAL(
                        message = UICApplication.instance.resources.getString(
                            R.string.partial_approve_amounts,
                            totalAmount,
                            approvedAmount,
                            try {
                                (BigDecimal(baseAmount) - BigDecimal(approvedAmount)).setScale(2, RoundingMode.HALF_UP)
                                    .toPlainString()
                            } catch (e: Exception) {
                                ""
                            }
                        )
                    )
                }
            }

            StateType.RETRY -> ProcessTransactionUiState.RETRY(message)

            StateType.CONNECTIONFAILED -> ProcessTransactionUiState.CONNECTIONFAILED(message)
        }
}


fun statusCodeToTerminalMessage(statusCode: String, context: Context): TerminalMessage {
    Log.d("StatusCodeToMsg", statusCode)
    return when (statusCode) {
        "" -> TerminalMessage.CONNECTIONFAILED("Connection failed")

        "0000" -> TerminalMessage.SUCCESS("0000")

        "0001" -> TerminalMessage.FAILED("System not ready. Please press the 'Back' button and redo the transaction.")

        "0002" -> TerminalMessage.FAILED("Online authorization request")

        "0003" -> TerminalMessage.SUCCESS("Signature required. Please print and sign receipt.")

        "0006" -> TerminalMessage.FAILED("Transaction cancelled. Please restart transaction.")

        "0007" -> TerminalMessage.FAILED("Transaction terminated. Please restart transaction.")

        "0008" -> TerminalMessage.FAILED(context.getString(R.string.err_no_record))

        "0009" -> TerminalMessage.FAILED(context.getString(R.string.err_no_terminal_config))

        "000A" -> TerminalMessage.FAILED(context.getString(R.string.err_no_application_config))

        "000B" -> TerminalMessage.FAILED(context.getString(R.string.err_no_public_key))

        "000C" -> TerminalMessage.RETRY(context.getString(R.string.err_try_another_card))

        "000D" -> TerminalMessage.RETRY(context.getString(R.string.err_swipe_again))

        "000E" -> TerminalMessage.FAILED(context.getString(R.string.err_source_mac_unallowed))

        "000F" -> TerminalMessage.FAILED(context.getString(R.string.err_dest_mac_unallowed))

        "0010" -> TerminalMessage.FAILED(context.getString(R.string.err_hash_type_unsupported))

        "0011" -> TerminalMessage.FAILED(context.getString(R.string.err_MAC_incorrect))

        "0012" -> TerminalMessage.FAILED(context.getString(R.string.err_not_support_trans))

        "0013" -> TerminalMessage.FAILED(context.getString(R.string.err_account_type_not_support))

        "0014" -> TerminalMessage.FAILED(context.getString(R.string.err_currency_invalid))

        "0015" -> TerminalMessage.FAILED(context.getString(R.string.err_amount_invalid))

        "0016" -> TerminalMessage.FAILED(context.getString(R.string.err_force_online_setting))

        "0017" -> TerminalMessage.FAILED(context.getString(R.string.err_command_format_incorrect))

        "0018" -> TerminalMessage.FAILED(context.getString(R.string.err_LRC))

        "0019" -> TerminalMessage.FAILED(context.getString(R.string.err_parameter))

        "001A" -> TerminalMessage.FAILED(context.getString(R.string.err_unrecognized_command))

        "001B" -> TerminalMessage.FAILED(context.getString(R.string.err_cmd_timeout))

        "001C" -> TerminalMessage.RETRY(context.getString(R.string.err_remove_card_before_transaction))

        "001D" -> TerminalMessage.RETRY(context.getString(R.string.err_tap_again))

        "001E" -> TerminalMessage.RETRY(context.getString(R.string.err_phone_locked))

        "001F" -> TerminalMessage.RETRY(context.getString(R.string.err_try_another_interface))

        "1000" -> TerminalMessage.RETRY(context.getString(R.string.err_processor_err))

        "1001" -> TerminalMessage.RETRY(context.getString(R.string.err_transaction_response))

        "1002" -> TerminalMessage.RETRY(context.getString(R.string.err_communication_timeout))

        "1003" -> TerminalMessage.FAILED(context.getString(R.string.err_comm_error))

        "FF00" -> TerminalMessage.FAILED(context.getString(R.string.err_peripheral_comm))

        "FF01" -> TerminalMessage.FAILED(context.getString(R.string.err_parameter_incorrect))

        "FF02" -> TerminalMessage.FAILED(context.getString(R.string.err_out_of_memory))

        "FF03" -> TerminalMessage.FAILED(context.getString(R.string.err_buffer_size))

        "FF04" -> TerminalMessage.FAILED(context.getString(R.string.err_sys_timeout))

        "FF05" -> TerminalMessage.FAILED(context.getString(R.string.err_data_err))

        "FF06" -> TerminalMessage.FAILED(context.getString(R.string.err_sys_unsupported))

        "FF07" -> TerminalMessage.FAILED(context.getString(R.string.err_create_file))

        "FF08" -> TerminalMessage.FAILED(context.getString(R.string.err_read_file))

        "FF09" -> TerminalMessage.FAILED(context.getString(R.string.err_write_file))

        "FF0A" -> TerminalMessage.FAILED(context.getString(R.string.err_delete_file))

        "FF0B" -> TerminalMessage.FAILED(context.getString(R.string.err_file_dne))

        "FF0C" -> TerminalMessage.FAILED(context.getString(R.string.err_close_file_failed))

        "FF0D" -> TerminalMessage.FAILED(context.getString(R.string.err_emv_terminal_config_update_failed))

        "FF0E" -> TerminalMessage.FAILED(context.getString(R.string.err_emv_app_config_update_failed))

        "FF0F" -> TerminalMessage.FAILED(context.getString(R.string.err_emv_ca_pubkey_failed))

        "FF10" -> TerminalMessage.FAILED(context.getString(R.string.err_emv_data_format_config_failed))

        "FF11" -> TerminalMessage.FAILED(context.getString(R.string.err_sys_update_terminal_reader))

        "FF12" -> TerminalMessage.FAILED(context.getString(R.string.err_update_reader_firmware_failed))

        "FF13" -> TerminalMessage.FAILED(context.getString(R.string.err_please_update_system))

        "FFFE" -> TerminalMessage.FAILED(context.getString(R.string.err_unknown_error))

        "FFFF" -> TerminalMessage.FAILED(context.getString(R.string.err_fatal_err))

        else -> TerminalMessage.FAILED("Unhandled status code: ${statusCode}. Please restart the payment terminal or call the helpdesk.")
    }
}

fun messageIdToTerminalMessage(message: Message, context: Context): TerminalMessage {
    if (message.what == Constants.ACTION_DISP_MSG) {
        val msgId = message.obj
        return when (msgId) {
            "Connecting" -> TerminalMessage.CONNECTING("Connecting")
            "Sending" -> TerminalMessage.CONNECTING("Sending")
            "Receiving" -> TerminalMessage.CONNECTING("Receiving")
            "Conectando" -> TerminalMessage.CONNECTING("Connecting")
            "Enviando" -> TerminalMessage.CONNECTING("Sending")
            "Recibiendo" -> TerminalMessage.CONNECTING("Receiving")
            "(02) Online Approved." -> TerminalMessage.SUCCESS("(02) Online Approved.")
            "(03) Online Declined." -> TerminalMessage.FAILED("(03) Online Declined.")
            "(02) Online Approved" -> TerminalMessage.SUCCESS("(02) Online Approved.")
            "(06) Partial Approved." -> TerminalMessage.SUCCESS("(06) Partial Approved.")
            "01" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_01))
            "02" -> TerminalMessage.PROMPT(context.getString(R.string.msg_02))
            "03" -> TerminalMessage.SUCCESS(context.getString(R.string.msg_03))
            "04" -> TerminalMessage.FAILED(context.getString(R.string.msg_04))
            "05" -> TerminalMessage.PROMPT(context.getString(R.string.msg_05))
            "06" -> TerminalMessage.RETRY(context.getString(R.string.msg_06))
            "07" -> TerminalMessage.RETRY(context.getString(R.string.msg_07))
            "08" -> TerminalMessage.PROMPT(context.getString(R.string.msg_08))
            "09" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_09))
            "10" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_10))
            "11" -> TerminalMessage.PROMPT(context.getString(R.string.msg_11))
            "12" -> TerminalMessage.RETRY(context.getString(R.string.msg_12))
            "13" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_13))
            "14" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_14))
            "15" -> TerminalMessage.RETRY(context.getString(R.string.msg_15))
            "16" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_16))
            // CHeck Kozen behavior for 17, 18
            "17" -> TerminalMessage.PROMPT(context.getString(R.string.msg_17))
            "18" -> TerminalMessage.PROMPT(context.getString(R.string.msg_18))
            "19" -> TerminalMessage.RETRY(context.getString(R.string.msg_19))
            "20" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_20))
            "21" -> TerminalMessage.PROMPT(context.getString(R.string.msg_21))
            "22" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_22))
            "23" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_23))
            "24" -> TerminalMessage.PROMPT(context.getString(R.string.msg_24))
            "25" -> TerminalMessage.RETRY(context.getString(R.string.msg_25))
            "26" -> TerminalMessage.SUCCESS(context.getString(R.string.msg_26))
            "27" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_27))
            "28" -> TerminalMessage.RETRY(context.getString(R.string.msg_28))
            "29" -> TerminalMessage.PROMPT(context.getString(R.string.msg_29))
//        "30" -> TerminalMessage.SUCCESS(context.getString(R.string.msg_30))
//        "31" -> TerminalMessage.WAITING(context.getString(R.string.msg_31))
            "32" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_32))
            "33" -> TerminalMessage.PROMPT(context.getString(R.string.msg_33))
            "34" -> TerminalMessage.SUCCESS(context.getString(R.string.msg_34))
            "35" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_35))
            "36" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_36))
            "37" -> TerminalMessage.RETRY(context.getString(R.string.msg_37))
            "38" -> TerminalMessage.SUCCESS(context.getString(R.string.msg_38))
            "39" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_39))
            "40" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_40))
            "41" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_41))
            "42" -> TerminalMessage.PROMPT(context.getString(R.string.msg_42))
            "43" -> TerminalMessage.AUTHORIZING(context.getString(R.string.msg_43))
            else -> TerminalMessage.FAILED("Unexpected message code $msgId")
        }
    } else if (message.what == Constants.ACTION_ERROR_MSG) {
        val errCodeId = message.obj.toString().toIntOrNull()
        val errorCode = errCodeId?.let { ErrCode.entries[errCodeId] }
        return when (errorCode) {
            null -> TerminalMessage.FAILED("Error code was null")
            else -> {
                Log.d("MsgIdToMsg", UICApplication.instance.getString(errorCode.getId()))
                TerminalMessage.CONNECTIONFAILED(UICApplication.instance.getString(errorCode.getId()))
            }
        }
    } else if (message.what == Constants.ACTION_DISP_APPL_SELECT) {
        val dispObj = message.obj as ApplSelectViewData
        Log.d("DispObj", dispObj.dispApplSelectList.toString())
        return TerminalMessage.PROMPT(message = Constants.ACTION_DISP_APPL_SELECT.toString(),
            applNames = dispObj.dispApplSelectList ?: listOf())
    } else {
        return TerminalMessage.FAILED("Erroneous message received: ${message.obj}")
    }
}

object ApplSelectController {
    private val TAG : String = this::class.java.simpleName

    var applSelecResult: String = ""
    var applSelectResultViewActionResult: Int =  0
    var timeoutSec: Int = Constants.USER_OPER_TIMEOUT

    fun waitResult(): ErrCode {
        var ret: ErrCode

        var timerThread = TimerThread()

        this.applSelectResultViewActionResult = 0

        timerThread.startTimer(this.timeoutSec)

        // detect input amount view
        while (true) {
            if (timerThread.isTimeout() == true) {
                // timeout
                this.applSelectResultViewActionResult = Constants.USER_PRESS_TIMEOUT
            }

            when (this.applSelectResultViewActionResult) {
                Constants.USER_PRESS_OK_BUTTON -> {
                    Log.d(TAG, "USER_PRESS_OK_BUTTON")
                    ret = ErrCode.NO_ERR
                    break
                }
                Constants.USER_PRESS_TIMEOUT -> {
                    Log.d(TAG, "USER_PRESS_TIMEOUT")
                    // timeout
                    ret = ErrCode.ERR_TIMEOUT
                    break
                }
                Constants.USER_PRESS_CANCEL_BUTTON, Constants.USER_PRESS_NATIVE_BACK_BUTTON -> {
                    Log.d(TAG, "USER_PRESS_CANCEL")
                    // user cancel
                    ret = ErrCode.ERR_USERCANCEL
                    break
                }
            }
        }

        // stop timer
        timerThread.stopTimer()

        return ret
    }

    fun selectOption(selectResult: Int, selectedString: String = "") {
        this.applSelectResultViewActionResult = selectResult
        this.applSelecResult = selectedString
    }

}