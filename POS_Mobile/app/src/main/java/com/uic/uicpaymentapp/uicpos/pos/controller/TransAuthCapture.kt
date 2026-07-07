package com.uic.uicpaymentapp.uicpos.pos.controller

import android.os.Handler
import com.uic.uicpaymentapp.security.EncryptionUtil
import com.uic.uicpaymentapp.transaction.Transaction
import com.uic.uicpaymentapp.uicpos.pos.errcode.Constants
import com.uic.uicpaymentapp.uicpos.pos.errcode.ErrCode
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import com.uic.uicpaymentapp.uicpos.pos.model.SysParam

class TransAuthCapture(
    val uiHandler: Handler,
    val txnHandler: Handler,
    val totalAmount: String,
    val tipAmount: String,
    val transaction: Transaction
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    override fun run() {

        var ret: ErrCode

        // TransInit
        var procInfo  = ProcInfo()
        var sysParam  = SysParam.getInstance()
        var transProc = TransProc(uiHandler)

        procInfo.TransLog.InvoiceId = ""
        procInfo.TransLog.TxnType   = Constants.HOST_TRANS_AUTH_CAPTURE
        procInfo.TransLog.CurrCode  = sysParam.CurrCode

        do {
            //---------------------------------------------------------------------
            // transaction ID
            procInfo.TransLog.TxnId = transaction.transactionId
            /*ret = appUtil.getTransID(procInfo)
            if(ret != ErrCode.NO_ERR) break*/

            // get amount
//            ret = appUtil.getAmount(procInfo)
//            if(ret !=  ErrCode.NO_ERR ) break
            procInfo.TransLog.TxnAmt = totalAmount
            procInfo.TransLog.TipAmt = tipAmount
            procInfo.TransLog.InvoiceId = transaction.invoiceId
            procInfo.TransLog.AuthCode = transaction.authCode
            procInfo.TransLog.CardNbr = EncryptionUtil.decryptData(transaction.cardNumber)
            procInfo.TransLog.MaskedCardNbr = transaction.masked_cardNumber
            procInfo.TransLog.HashedCardNbr = transaction.hashed_cardNumber

            // Invoice No.
//            ret = appUtil.getInvoiceNo(procInfo)
//            if(ret !=  ErrCode.NO_ERR ) break

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