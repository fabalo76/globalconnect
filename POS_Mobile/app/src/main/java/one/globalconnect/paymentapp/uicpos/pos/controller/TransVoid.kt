package one.globalconnect.paymentapp.uicpos.pos.controller

import android.os.Handler
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.security.EncryptionUtil
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.uicpos.pos.errcode.Constants
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo

class TransVoid(
    val uiHandler: Handler,
    val finishedHandler: Handler,
    val transaction: Transaction
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    private val transTypeStr: String = GlobalConnectPaymentApplication.instance.resources.getString(R.string.trans_void)

    override fun run() {

        var ret: ErrCode

        //TransInit
        var procInfo  = ProcInfo()
        var transProc = TransProc(uiHandler)

        procInfo.TransLog.InvoiceId = ""
        procInfo.TransLog.TxnType   = Constants.HOST_TRANS_VOID

        do {
            procInfo.TransLog.TxnId = transaction.transactionId
            procInfo.TransLog.AuthCode = transaction.authCode
            procInfo.TransLog.CardNbr = EncryptionUtil.decryptData(transaction.cardNumber)
            procInfo.TransLog.MaskedCardNbr = transaction.masked_cardNumber
            procInfo.TransLog.HashedCardNbr = transaction.hashed_cardNumber

            // start transaction
            procInfo.ReqCmd.CmdId = Constants.CMD_ID_START_TXN

            ret = transProc.setCommReqField(procInfo)
            if(ret != ErrCode.NO_ERR) break

            ret = transProc.transProcess(procInfo)
            if(ret != ErrCode.NO_ERR) break

        } while (false)

        transProc.finishTransProcess(ret, procInfo, finishedHandler)
    }

}