package com.uic.uicpaymentapp.uicpos.pos.controller

import android.os.Handler
import com.uic.uicpaymentapp.uicpos.pos.errcode.Constants
import com.uic.uicpaymentapp.uicpos.pos.errcode.ErrCode
import com.uic.uicpaymentapp.uicpos.pos.host.InvoiceNumberProvider
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import com.uic.uicpaymentapp.uicpos.pos.model.SysParam

class TransExtrasSale(
    uiHandler: Handler,
    val txnHandler: Handler,
    val totalamount: String,
    val baseamount: String,
    val tax1amount: String = "0.00",
    val tax2amount: String = "0.00",
    val tipAmount: String = "0.00"
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    private val uiHandler: Handler = uiHandler

    private val transTypeStr: String = "Sale"

    private val sysParam  = SysParam.getInstance()

    private fun setReqField(procInfo: ProcInfo): ErrCode {

        procInfo.TransLog.CurrCode  = sysParam.CurrCode

        return ErrCode.NO_ERR
    }

    override fun run() {

        var ret: ErrCode

        //TransInit
        var procInfo  = ProcInfo()
        var transProc = TransProc(uiHandler)
        procInfo.TransLog.TxnType   = Constants.HOST_TRANS_EXTRASSALE

        procInfo.TransLog.InvoiceId = InvoiceNumberProvider.nextInvoiceNumber()

        // title bar
        // TitleBarView.setTitleText(transTypeStr)

        do {
            // get account type
//            ret = appUtil.getAccountType(procInfo)
//            if(ret != ErrCode.NO_ERR) break
            procInfo.TransLog.AccType = "Credit"

            // get amount
//            ret = appUtil.getAmount(procInfo)
//            if(ret !=  ErrCode.NO_ERR ) break
            procInfo.TransLog.TxnAmt = totalamount
            procInfo.TransLog.BaseAmt = baseamount
            procInfo.TransLog.Tax1Amt = tax1amount
            procInfo.TransLog.Tax2Amt = tax2amount
            procInfo.TransLog.TipAmt = tipAmount

            // start transaction
            procInfo.ReqCmd.CmdId = Constants.CMD_ID_START_TXN
            ret = transProc.setCommReqField(procInfo)
            if(ret != ErrCode.NO_ERR) break

            ret = this.setReqField(procInfo)
            if(ret != ErrCode.NO_ERR) break

            ret = transProc.transProcess(procInfo)
            if(ret != ErrCode.NO_ERR) break

        } while (false)

        transProc.finishTransProcess(ret, procInfo, txnHandler)
    }

}