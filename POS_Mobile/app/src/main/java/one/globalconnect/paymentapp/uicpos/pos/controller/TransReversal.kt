package one.globalconnect.paymentapp.uicpos.pos.controller

import android.os.Handler
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.uicpos.pos.errcode.Constants
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.host.InvoiceNumberProvider
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam

class TransReversal(
    uiHandler: Handler,
    private val transaction: Transaction,
    private val txnHandler: Handler
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    private val uiHandler: Handler = uiHandler

    private val transTypeStr: String = GlobalConnectPaymentApplication.instance.resources.getString(R.string.trans_reversal)

    override fun run() {

        var ret: ErrCode

        //TransInit
        var procInfo  = ProcInfo()
        var sysParam  = SysParam.getInstance()
        var transProc = TransProc(uiHandler)

        procInfo.TransLog.InvoiceId = ""
        procInfo.TransLog.TxnType   = Constants.HOST_TRANS_REVERSAL
        procInfo.TransLog.CurrCode  = sysParam.CurrCode

        // title bar
        // TitleBarView.setTitleText(transTypeStr)


        do {
            //---------------------------------------------------------------------
            // transaction ID
            procInfo.TransLog.TxnId = transaction.transactionId
            procInfo.TransLog.TxnAmt = transaction.subTotal

            // Invoice No.
            procInfo.TransLog.InvoiceId = InvoiceNumberProvider.nextInvoiceNumber()

            // start transaction
            procInfo.ReqCmd.CmdId = Constants.CMD_ID_START_TXN

            ret = transProc.setCommReqField(procInfo)
            if(ret != ErrCode.NO_ERR) break

            ret = transProc.transProcess(procInfo)
            if(ret != ErrCode.NO_ERR) break

        } while (false)

        transProc.finishTransProcess(ret, procInfo, txnHandler)
    }

}