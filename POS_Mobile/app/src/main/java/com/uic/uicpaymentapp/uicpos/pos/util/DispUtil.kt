package com.uic.uicpaymentapp.uicpos.pos.util

import android.os.Handler
import android.os.Message
import android.util.Log
import com.uic.uicpaymentapp.uicpos.pos.errcode.Constants
import com.uic.uicpaymentapp.uicpos.pos.errcode.ErrCode
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.transaction.ApplSelectController

class DispUtil(
    // context: Context,
    val uiHandler: Handler?
) {

    interface InputAmountListener<ErrCode, T> {

        fun onAmountResult(status: ErrCode, amoutText: T)
    }

    interface InputStringListener<ErrCode, T> {

        fun onStringResult(status: ErrCode, stringText: T)
    }

    interface ApplSelectListener<ErrCode, T> {

        fun onApplSelectResult(status: ErrCode, stringText: T)
    }

    interface selectAccountTypeListener<ErrCode, T> {

        fun onSelectAccountTypeResult(status: ErrCode, stringText: T)
    }


    companion object {
        private val TAG = this::class.java.simpleName
    }

//    private var context: Context = context
//    private var uiHandler: Handler = uiHandler

//    fun setUIHandler(uiHandler: Handler) {
//        this.uiHandler = uiHandler
//    }

    fun dispTerminalErrorResult(errorCode: ErrCode) {
        dispMsg(
            UICApplication.instance.getString(R.string.msg_warning_title),
            errorCode.ordinal.toString(),
            error = true
        )
    }

    fun dispTerminalHostResponse(@Suppress("UNUSED_PARAMETER") errorCode: ErrCode, procInfo: ProcInfo?) {
        if (procInfo == null) {
//            dispConfirmMsg(Constants.DISP_IMAGE_ID_ERROR, context.getString(R.string.msg_warning_title), errorCode.getMsg(context),
//                Constants.USER_OPER_TIMEOUT)
            return
        }

        /*
         TxnResult
            00 – Approval (offline, not available in US region because no offline EMV transaction)
            01 – Declined (offline, not available in US region because no offline EMV transaction
            02 – Approved (online, US)
            03 – Declined (online, US)
            04- offline approved unable to go online (not available in US region)
            05 – offline declined  unable to go online (not available in US region)
         */
        if (procInfo.TransLog.TxnResult == "00" || procInfo.TransLog.TxnResult == "02" || procInfo.TransLog.TxnResult == "04") {
            // approved
            var dispText = "(${procInfo.TransLog.TxnResult}) ${procInfo.TransLog.TxnResultMsg}"
            dispMsg(UICApplication.instance.resources.getString(R.string.msg_approved), dispText)
            // dispConfirmMsg(Constants.DISP_IMAGE_ID_OK, "", dispText, Constants.USER_OPER_TIMEOUT)

        } else {
            // declined
            var dispText = "(${procInfo.TransLog.TxnResult}) ${procInfo.TransLog.TxnResultMsg}"
            dispMsg(UICApplication.instance.resources.getString(R.string.msg_declined), dispText)
            // dispConfirmMsg(Constants.DISP_IMAGE_ID_ERROR, context.getString(R.string.msg_warning_title), dispText,
            //     Constants.USER_OPER_TIMEOUT)

        }

    }

    fun dispTerminalHostStatus(errorCode: ErrCode, procInfo: ProcInfo?) {
        if (procInfo == null) {
            // dispConfirmMsg(Constants.DISP_IMAGE_ID_ERROR, context.getString(R.string.msg_warning_title), errorCode.getMsg(context),
            //    Constants.USER_OPER_TIMEOUT)
            return
        }

        var dispText = ""

        // Status
        if (errorCode == ErrCode.ERR_TERMINAL_API_STATUS) {
            dispText = "(${procInfo.RespCmd.StatusCode}) ${procInfo.RespCmd.StatusText}"
        }

        // TxnResult
        if (procInfo.TransLog.TxnResult!!.isNotEmpty()) {
            dispText += "\n\n(${procInfo.TransLog.TxnResult}) ${procInfo.TransLog.TxnResultMsg}"
        }

        // Error: if error code is ErrCode.ERR_TERMINAL_API_STATUS
        if (errorCode == ErrCode.ERR_TERMINAL_API_STATUS &&
            (procInfo.Error.ErrText!!.isNotEmpty() || procInfo.Error.ErrCode!!.isNotEmpty())
        ) {

            if (procInfo.Error.ErrCode!!.isNotEmpty()) {
                dispText += "\n\n(${procInfo.Error.ErrText}) ${procInfo.Error.ErrText}"
            } else {
                dispText += "\n\n${procInfo.Error.ErrText}"
            }
        }

        // display
        /*
         TxnResult
            00 – Approval (offline, not available in US region because no offline EMV transaction)
            01 – Declined (offline, not available in US region because no offline EMV transaction
            02 – Approved (online, US)
            03 – Declined (online, US)
            04- offline approved unable to go online (not available in US region)
            05 – offline declined  unable to go online (not available in US region)
         */
        if (errorCode == ErrCode.NO_ERR && (procInfo.TransLog.TxnResult == "00" || procInfo.TransLog.TxnResult == "02" || procInfo.TransLog.TxnResult == "04")) {
            dispMsg(UICApplication.instance.resources.getString(R.string.msg_approved), dispText)
            // dispConfirmMsg(Constants.DISP_IMAGE_ID_OK, "", dispText,
            //   Constants.USER_OPER_TIMEOUT)
        } else {
            dispMsg(
                UICApplication.instance.resources.getString(R.string.msg_warning_title),
                dispText
            )
            //  dispConfirmMsg(Constants.DISP_IMAGE_ID_ERROR, context.getString(R.string.msg_warning_title), dispText,
            //   Constants.USER_OPER_TIMEOUT)
        }
    }

    fun dispResult(errorCode: ErrCode, procInfo: ProcInfo?, @Suppress("UNUSED_PARAMETER") timeoutSec: Int) {
        if (procInfo != null) {
            procInfo.ReqEvent.MesgStr?.let { Log.d("dispResult", it) }
        }
        when (errorCode) {
            ErrCode.ERR_NO_DISP -> {
                return
            }

            ErrCode.ERR_USERCANCEL -> {
                // don't display user cancel error code on the screen
                Log.d(TAG, "User Cancel")
                if (procInfo != null) {
                    procInfo.ReqEvent.MesgStr?.let {
                        dispMsg("", it)
                    }
                }
            }

            ErrCode.NO_ERR -> {
                if (procInfo != null) {
                    if (procInfo.TransLog.RspCode!!.isNotEmpty()) {
                        // host response
                        dispTerminalHostResponse(errorCode, procInfo)

                    } else if (procInfo.RespId!!.isNotEmpty() || procInfo.RespCmd.StatusCode!!.isNotEmpty()) {
                        // terminal status
                        dispTerminalHostStatus(errorCode, procInfo)

                    } else {
                        procInfo.ReqEvent.MesgStr?.let {
                            dispMsg(
                                UICApplication.instance.resources.getString(R.string.msg_approved),
                                it
                            )
                        }
                        // dispConfirmMsg(Constants.DISP_IMAGE_ID_OK, "", context.getString(R.string.msg_approved),
                        //   Constants.USER_OPER_TIMEOUT_SHORT)
                    }

                } else {
                    dispMsg(
                        UICApplication.instance.resources.getString(R.string.msg_approved),
                        "Approved"
                    )
                    // dispConfirmMsg(Constants.DISP_IMAGE_ID_OK, "", context.getString(R.string.msg_approved),
                    // Constants.USER_OPER_TIMEOUT_SHORT)
                }

            }

            ErrCode.ERR_TERMINAL_API_STATUS -> {
                dispTerminalHostStatus(errorCode, procInfo)
            }

            else -> {
                dispTerminalErrorResult(errorCode)
            }
        }
    }

    fun dispMsg(dispTitle: String, dispText: String, error: Boolean = false) {
        val dispObj = MessageViewData()
        dispObj.dispText = dispText
        dispObj.timeoutSec = Constants.USER_OPER_TIMEOUT_SHORT

        val msg = Message.obtain()
        if (dispTitle == Constants.ACTION_DISP_MSG.toString()) {
            msg.what = Constants.ACTION_DISP_MSG
        } else if (dispTitle == Constants.ACTION_DISP_APPL_SELECT.toString()) {
            msg.what = Constants.ACTION_DISP_APPL_SELECT
        } else if (error) {
            msg.what = Constants.ACTION_ERROR_MSG
        } else {
            msg.what = Constants.ACTION_DISP_MSG
        }
        msg.obj = dispText
        uiHandler?.sendMessage(msg)
    }

    //    fun dispInputAmount(
//        promptText: String,
//        prefix: String,
//        dispAmtText: String,
//        deciPos: Int,
//        minAmtLen: Int,
//        maxAmtLen: Int,
//        misc: Int,
//        timeoutSec: Int,
//        listener: inputAmoutListener<ErrCode, String>
//    ) : ErrCode {
//
////        val amountObj = InputAmountViewData()
////        amountObj.amountPromptText = promptText
////        amountObj.amountPrefixText = prefix
////        amountObj.amountText = dispAmtText
////        amountObj.amountDeciPos = deciPos
////        amountObj.amountMisc = misc
////        amountObj.amountMaxLen = maxAmtLen
////        amountObj.amountMinLen = minAmtLen
////        amountObj.timeoutSec = timeoutSec
////
////
////        val msg = Message()
////        msg.what = Constants.ACTION_INPUT_AMOUNT
////        msg.obj = amountObj
////        uiHandler.sendMessage( msg )
////
////        var ret = InputAmountViewController.waitResult()
////        if(  ret != ErrCode.NO_ERR ) {
////            return ret;
////        }
//
////        Log.d(TAG, "Input Amount : " + InputAmountViewController.amountTextResult)
////
////        listener.onAmountResult( ret, InputAmountViewController.amountTextResult)
////
////        return ret
//    }
//
//    fun dispInputString(
//        promptText: String,
//        dispText: String,
//        mode: Int,
//        minStrLen: Int,
//        maxStrLen: Int,
//        timeoutSec: Int,
//        listener: inputStringListener<ErrCode, String>
//    ) : ErrCode {
//
//        var stringObj = InputStringViewData()
//        stringObj.stringPromptText = promptText
//        stringObj.stringText = dispText
//        stringObj.stringMaxLen = maxStrLen
//        stringObj.stringMinLen = minStrLen
//        stringObj.stringMode = mode
//        stringObj.timeoutSec = timeoutSec
//
//        val msg = Message()
//        msg.what = Constants.ACTION_INPUT_STRING
//        msg.obj = stringObj
//        uiHandler.sendMessage( msg )
//
//        var ret = InputStringViewController.waitResult()
//        if(  ret != ErrCode.NO_ERR )
//            return ret;
//
//        var stringResult = InputStringViewController.stringTextResult
//        Log.d(TAG, "Input String " +  stringResult )
//
//        listener.onStringResult(ret, stringResult)
//
//        return ret
//    }
//
//    fun dispSelectAccountType(
//        titleText: String,
//        dispText: String,
//        timeoutSec: Int,
//        listener: selectAccountTypeListener<ErrCode, String>
//    ) : ErrCode {
//
//        var accountTypeDataObj = SelectAccountTypeViewData()
//        accountTypeDataObj.dispTitle = titleText
//        accountTypeDataObj.dispText = dispText
//        accountTypeDataObj.timeoutSec = timeoutSec
//
//        val msg = Message()
//        msg.what = Constants.ACTION_DISP_SELECT_ACCOUNT_TYPE
//        msg.obj = accountTypeDataObj
//        uiHandler.sendMessage( msg )
//
//        var ret = SelectAccountTypeViewController.waitResult()
//        if(  ret != ErrCode.NO_ERR )
//            return ret;
//
//        var accountTypeTextResult = SelectAccountTypeViewController.accountTypeTextResult
//        Log.d(TAG, "Account Type " +  accountTypeTextResult )
//
//        listener.onSelectAccountTypeResult(ret, accountTypeTextResult)
//
//        return ret
//    }
//
//
    fun dispConfirmDialog(
        dispText: String,
        timeoutSec: Int
    ): ErrCode {

        var confirmObj = MessageViewData()
        confirmObj.dispText = dispText
        confirmObj.timeoutSec = timeoutSec

        val msg = Message()
        msg.what = Constants.ACTION_CONFIRM_DIALOG
        msg.obj = confirmObj
        uiHandler?.sendMessage(msg)

        // var ret = ConfirmDialogViewController.waitResult()
        var ret = ErrCode.ERR_CODE_MOCK_TESTING

        return ret
    }

    //
//
//
//   fun dispConfirmMsg(
//        dispTitle: String,
//        dispText: String,
//        timeoutSec: Int
//    ): ErrCode {
//        return dispConfirmMsg(0, dispTitle, dispText, timeoutSec)
//
//    }
//
//    fun dispConfirmMsg(
//        dispImageID: Int,
//        dispTitle: String,
//        dispText: String,
//        timeoutSec: Int
//    ): ErrCode {
//
//        var messageObj = ConfirmMessageViewData()
//        messageObj.dispImageID = dispImageID
//        messageObj.dispText = dispText
//        messageObj.dispTitle = dispTitle
//        messageObj.timeoutSec =  timeoutSec
//
//        val msg = Message()
//        msg.what = Constants.ACTION_CONFIRM_MSG
//        msg.obj = messageObj
//        uiHandler.sendMessage( msg )
//
//        var ret = ConfirmMessageViewController.waitResult()
//
//        return ret
//    }
//
//    fun dispCardEntry(dispTitle: String, dispText: String)
//    {
//        var dispObj = CardEntryViewData()
//        dispObj.dispText = dispText
//        dispObj.timeoutSec = Constants.USER_OPER_TIMEOUT
//
//        val msg = Message()
//        msg.what = Constants.ACTION_DISP_CARD_ENTRY
//        msg.obj = dispObj
//        uiHandler.sendMessage( msg )
//    }
//
//    fun dispDetaiResult(
//            dispTitle: String = "",
//            dataHashMap: HashMap<String, String?>,
//            timeoutSec: Int = Constants.USER_OPER_TIMEOUT
//    ) : ErrCode {
//
//        var dispObj = DetailResultViewData()
//        dispObj.dispTitle = dispTitle
//        dispObj.dispDataHashMap = dataHashMap
//        dispObj.timeoutSec = timeoutSec
//
//        val msg = Message()
//        msg.what = Constants.ACTION_DISP_DETAIL_RESULT
//        msg.obj = dispObj
//        uiHandler.sendMessage( msg )
//
//        var ret = DetailResultViewController.waitResult()
//
//        return ret
//    }
//
    fun dispApplSelect(
        dispTitle: String = "",
        applList: List<String>,
        timeoutSec: Int = Constants.USER_OPER_TIMEOUT,
        listener: ApplSelectListener<ErrCode, String>
    ): ErrCode {
        var dispObj = ApplSelectViewData()
        dispObj.dispTitle = dispTitle
        dispObj.dispApplSelectList = applList
        dispObj.timeoutSec = timeoutSec

        val msg = Message()
        msg.what = Constants.ACTION_DISP_APPL_SELECT
        msg.obj = dispObj
        uiHandler?.sendMessage(msg)

        var ret = ApplSelectController.waitResult()
//        if (ret != ErrCode.NO_ERR) {
//            return ret;
//        }

        Log.d(TAG, "Appl Select : " + ApplSelectController.applSelecResult)
        listener.onApplSelectResult(ret, ApplSelectController.applSelecResult)
        return ret
    }
}

class MessageViewData {
    var dispText: String = ""
    var timeoutSec: Int = Constants.USER_OPER_TIMEOUT
}

class ApplSelectViewData {
    var dispID: Int = 0
    var dispTitle: String = ""
    var dispApplSelectList: List<String>? = null
    var timeoutSec: Int = Constants.USER_OPER_TIMEOUT
}

