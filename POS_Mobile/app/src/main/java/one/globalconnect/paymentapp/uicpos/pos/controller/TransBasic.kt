package one.globalconnect.paymentapp.uicpos.pos.controller

import android.content.Context
import android.os.Handler
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.util.AppUtil

class TransBasic(
    context: Context,
    uiHandler: Handler,
    procInfo: ProcInfo,
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    private val context: Context   = context
    private val uiHandler: Handler = uiHandler
    private var procInfo: ProcInfo = procInfo
    private val transTypeStr: String = context.applicationContext.getString(R.string.trans_auth)

    private val appUtil: AppUtil = AppUtil(context, uiHandler)

    override fun run() {

        var ret: ErrCode

        //TransInit
        var transProc = TransProc(uiHandler)

        do {
            // start transaction
            ret = transProc.setCommReqField(procInfo)
            if(ret != ErrCode.NO_ERR) break

            ret = transProc.transProcess(procInfo)
            if(ret != ErrCode.NO_ERR) break

        } while (false)

        transProc.finishTransProcess(ret, procInfo)
    }

}