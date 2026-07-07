package com.uic.uicpaymentapp.uicpos.pos.controller

import android.os.Handler
import com.uic.uicpaymentapp.uicpos.pos.errcode.Constants
import com.uic.uicpaymentapp.uicpos.pos.errcode.ErrCode
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import com.uic.uicpaymentapp.uicpos.pos.model.SysParam

class TransLoyaltyBalance(
    uiHandler: Handler,
    private val txnHandler: Handler,
    val amount: String
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    private val uiHandler: Handler = uiHandler

    override fun run() {

        var ret: ErrCode = ErrCode.NO_ERR

        //TransInit
        var procInfo  = ProcInfo()
        var sysParam  = SysParam.getInstance()
        var transProc = TransProc(uiHandler)

        procInfo.TransLog.TxnType   = Constants.HOST_TRANS_LOYALTYBALANCE
        procInfo.TransLog.CurrCode  = sysParam.CurrCode

        do {
            // get account type
            //  ret = appUtil.getAccountType(procInfo)
            procInfo.TransLog.AccType = "Credit"
            if(ret != ErrCode.NO_ERR) break

            // get amount
            // ret = appUtil.getAmount(procInfo)
           // if(ret !=  ErrCode.NO_ERR ) break
            procInfo.TransLog.TxnAmt = amount

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