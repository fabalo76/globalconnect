package com.uic.uicpaymentapp.uicpos.pos.controller

import android.os.Handler
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.uicpos.pos.errcode.Constants
import com.uic.uicpaymentapp.uicpos.pos.errcode.ErrCode
import com.uic.uicpaymentapp.uicpos.pos.host.InvoiceNumberProvider
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import com.uic.uicpaymentapp.uicpos.pos.model.SysParam

class TransRefund(
    uiHandler: Handler,
    private val amount: String,
    private val txnHandler: Handler
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    private val uiHandler: Handler = uiHandler

    private val transTypeStr: String = UICApplication.instance.resources.getString(R.string.trans_refund)

    override fun run() {

        var ret: ErrCode

        //TransInit
        var procInfo  = ProcInfo()
        var sysParam  = SysParam.getInstance()
        var transProc = TransProc(uiHandler)

        procInfo.TransLog.InvoiceId = ""
        procInfo.TransLog.TxnType   = Constants.HOST_TRANS_REFUND
        procInfo.TransLog.CurrCode  = sysParam.CurrCode

        // title bar
        // TitleBarView.setTitleText(transTypeStr)


        do {
            //---------------------------------------------------------------------
            // transaction ID
//            procInfo.TransLog.TxnId = transaction.transactionId
//            procInfo.TransLog.AuthCode = transaction.authCode
//            procInfo.TransLog.CardNbr = transaction.cardNumber

            // get amount
            procInfo.TransLog.TxnAmt = amount

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