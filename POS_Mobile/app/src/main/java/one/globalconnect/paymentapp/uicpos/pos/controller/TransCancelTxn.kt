package one.globalconnect.paymentapp.uicpos.pos.controller

import android.os.Handler
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.uicpos.pos.errcode.Constants
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo

class TransCancelTxn(
//    context: Context,
    val uiHandler: Handler,
    val txnHandler: Handler?
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

//    private val context: Context = context
//
      private val transTypeStr: String = GlobalConnectPaymentApplication.instance.resources.getString(R.string.trans_cancel)
//
//    private val appUtil: AppUtil = AppUtil(context, uiHandler)

    override fun run() {

        var ret: ErrCode

        //TransInit
        var procInfo  = ProcInfo()
        var transProc = TransProc(uiHandler)

        // title bar
       //  TitleBarView.setTitleText(transTypeStr)

        do {
            //---------------------------------------------------------------------

            // start transaction
            procInfo.ReqCmd.CmdId = Constants.CMD_ID_CANCEL_TXN

            ret = transProc.setCommReqField(procInfo)
            if(ret != ErrCode.NO_ERR) break

            ret = transProc.transProcess(procInfo)

            if (ret != ErrCode.NO_ERR) break

        } while (false)



        transProc.finishTransProcess(ret, procInfo, txnHandler)
    }

}