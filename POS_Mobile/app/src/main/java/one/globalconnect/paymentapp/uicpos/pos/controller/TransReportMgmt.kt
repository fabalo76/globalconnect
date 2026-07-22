package one.globalconnect.paymentapp.uicpos.pos.controller

import android.content.Context
import android.os.Handler
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.uicpos.pos.errcode.Constants
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.util.AppUtil

class TransReportMgmt(
    context: Context,
    uiHandler: Handler,
    reportID: String
) : Runnable {

    companion object {
        private val TAG = this::class.java.simpleName
    }

    private val context: Context    = context
    private val uiHandler: Handler  = uiHandler
    private val reportID: String    = reportID
    private val reportParam: String = ""

    private var transTypeStr: String = context.applicationContext.getString(R.string.trans_report)

    private val appUtil: AppUtil = AppUtil(context, uiHandler)

    override fun run() {

        var ret: ErrCode

        //TransInit
        var procInfo  = ProcInfo()
        var transProc =TransProc(uiHandler)

//        when(reportID) {
//            Constants.REPORT_VIEW_PEDDING_TXN -> transTypeStr = context.applicationContext.getString(
//                R.string.trans_view_pending_record)
//            Constants.REPORT_DEL_PEDDING_TXN  -> transTypeStr = context.applicationContext.getString(R.string.trans_view_pending_record)
//            Constants.REPORT_VIEW_FAILED_TXN  -> transTypeStr = context.applicationContext.getString(R.string.trans_view_pending_record)
//            Constants.REPORT_DEL_FAILED_TXN   -> transTypeStr = context.applicationContext.getString(R.string.trans_view_pending_record)
//            Constants.REPORT_VIEW_SYSTEM_LOG  -> transTypeStr = context.applicationContext.getString(R.string.trans_view_pending_record)
//        }

        // title bar
        // TitleBarView.setTitleText(transTypeStr)

        do {
            //---------------------------------------------------------------------

            // start transaction
            procInfo.ReqCmd.CmdId  = Constants.CMD_ID_REPORT_MGMT
            procInfo.ReportMgmt.Id = reportID
            //procInfo.Report.Para  = reportParam

            ret = transProc.setCommReqField(procInfo)
            if(ret != ErrCode.NO_ERR) break

            ret = transProc.transProcess(procInfo)
            if(ret != ErrCode.NO_ERR) break

        }while (false)

        transProc.finishTransProcess(ret, procInfo)
    }

}