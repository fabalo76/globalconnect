package com.uic.uicpaymentapp.uicpos.pos.controller

import android.content.Context
import android.os.Handler
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.uicpos.pos.errcode.Constants
import com.uic.uicpaymentapp.uicpos.pos.errcode.ErrCode
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import com.uic.uicpaymentapp.uicpos.pos.util.AppUtil

class TransGetTxnResult(
    context: Context,
    uiHandler: Handler
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    private val context: Context = context
    private val uiHandler: Handler = uiHandler

    private val transTypeStr: String = context.applicationContext.getString(R.string.trans_get_result)

    private val appUtil: AppUtil = AppUtil(context, uiHandler)

    override fun run() {

        var ret: ErrCode

        //TransInit
        var procInfo  = ProcInfo()
        var transProc = TransProc(uiHandler)

        // title bar
        // TitleBarView.setTitleText(transTypeStr)

        do {
            //---------------------------------------------------------------------

            // start transaction
            procInfo.ReqCmd.CmdId = Constants.CMD_ID_GET_RESULT

            ret = transProc.setCommReqField(procInfo)
            if(ret != ErrCode.NO_ERR) break

            ret = transProc.transProcess(procInfo)
            if(ret != ErrCode.NO_ERR) break

        }while (false)

        transProc.finishTransProcess(ret, procInfo)
    }

}