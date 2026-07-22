package one.globalconnect.paymentapp.uicpos.pos.controller

import android.os.Handler
import android.os.Message
import android.util.Log
import android.util.Xml
import one.globalconnect.paymentapp.BuildConfig
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.uicpos.pos.errcode.Constants
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.host.HostAddress
import one.globalconnect.paymentapp.uicpos.pos.host.HostSettingsResolver
import one.globalconnect.paymentapp.uicpos.pos.host.toHexString
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.ReqCmd
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import one.globalconnect.paymentapp.uicpos.pos.transceiver.BaseSocketClient
import one.globalconnect.paymentapp.uicpos.pos.transceiver.SSLClient
import one.globalconnect.paymentapp.uicpos.pos.transceiver.SocketClientRecvMsgListener
import one.globalconnect.paymentapp.uicpos.pos.transceiver.TcpClient
import one.globalconnect.paymentapp.uicpos.pos.util.DispUtil
import one.globalconnect.paymentapp.uicpos.pos.util.document
import one.globalconnect.paymentapp.uicpos.pos.util.element
import one.globalconnect.paymentapp.util.LogSanitizer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.InputStream

class TransProc(val uiHandler: Handler? = null) {

    private val TAG = this::class.java.simpleName
    private var isRecvFinish = false

    private lateinit var socketClient : BaseSocketClient
    val dispUtil = DispUtil(uiHandler)

    fun transProcess(procInfo: ProcInfo)  : ErrCode {
        var ret : ErrCode


        do {
            // generate XML command
            ret = reqDataConvert(procInfo);
            if(ret != ErrCode.NO_ERR ) {
                break
            }


            val sanitized = LogSanitizer.sanitizeMessage(procInfo.SendData)
            Log.d(TAG, "Send Data [${procInfo.SendData.length} chars]: $sanitized")
            val hexPayload = LogSanitizer.sanitizeHexPayload(
                clearPan = procInfo.TransLog.PAN,
                track1 = procInfo.TransLog.Track1,
                track2 = procInfo.TransLog.Track2,
                track3 = procInfo.TransLog.Track3,
            ) { procInfo.SendData.toByteArray().toHexString() }
            Log.d(TAG, "Send Data HEX: $hexPayload")

            // send and receive terminal packet
            ret = sendRecvPacket( procInfo )
            if(ret != ErrCode.NO_ERR) break

            // process received terminal packet
            ret = afterTransProcess(procInfo)
            if(ret != ErrCode.NO_ERR) break

        } while(false)

        ret = this.finishTransProcess(ret, procInfo);

        return ret
    }

    fun afterTransProcess( procInfo: ProcInfo)  : ErrCode  {
        var ret : ErrCode = ErrCode.NO_ERR

        // status code
        if(procInfo.RespCmd.StatusCode != "0000") {
            ret = ErrCode.ERR_NO_DISP
            return ret
        }

        // display transaction result
        if( procInfo.RespCmd.CmdId != Constants.CMD_ID_START_TXN &&
            procInfo.RespCmd.CmdId != Constants.CMD_ID_SETTLE  ) {

            if( procInfo.RespId!!.isNotEmpty() && procInfo.dataHashMap.size > 0  ) {
                ret = ErrCode.ERR_CODE_MOCK_TESTING
            }  else  {
                dispUtil.dispResult(ErrCode.NO_ERR, procInfo, Constants.USER_OPER_TIMEOUT_SHORT)
            }

            return ret
        }

        dispUtil.dispResult(ErrCode.NO_ERR, procInfo, Constants.USER_OPER_TIMEOUT_SHORT)

        return ret
    }

    fun finishTransProcess(
        errCode: ErrCode,
        procInfo: ProcInfo,
        txnHandler: Handler? = null,
        txnResultListener: ((ProcInfo) -> Unit)? = null
    ) : ErrCode {
        if (errCode != ErrCode.NO_ERR) {
            dispUtil.dispResult(errCode, procInfo, Constants.USER_OPER_TIMEOUT)
        }

        if (errCode == ErrCode.ERR_COMM_RECEIVE_TIMEOUT) {
            procInfo.RespCmd.StatusCode = "1002"
        }

        txnHandler?.let {
            val msgBack = Message.obtain()
            msgBack.obj = procInfo
            txnHandler.sendMessage(msgBack)
        }
        txnResultListener?.invoke(procInfo)
        return errCode
    }

    fun setCommReqField(procInfo: ProcInfo) : ErrCode {
        var ret : ErrCode = ErrCode.NO_ERR

        // source & Dest MAC
        if( SysParam.getInstance().SourceMAC.isNotEmpty() && SysParam.getInstance().DestMAC.isNotEmpty() ){

        }

        procInfo.ReqCmd.CmdTout = SysParam.getInstance().CmdTout

        return ret
    }

    // extension function for start transaciotn
    fun TransProc.makeStartTrans(xmlSerializer: XmlSerializer, procInfo: ProcInfo) {

        xmlSerializer.element("Param") {
            element("Txn") {
                element("TxnType", procInfo.TransLog.TxnType )
                element("AccType", procInfo.TransLog.AccType )
                if(procInfo.TransLog.CurrCode.isNotEmpty()) {
                    element("CurrCode", procInfo.TransLog.CurrCode )
                }
                if(procInfo.TransLog.TxnAmt.isNotEmpty()) {
                    //element("TxnAmt", procInfo.TransLog.TxnAmt )
                    // element("TxnAmt", appUtil.convertAmountDispFormat(procInfo.TransLog.TxnAmt, Constants.GA_DCC, SysParam.getInstance().DeciPosAmt, 12) )
                    element("TxnAmt", procInfo.TransLog.TxnAmt )
                }
                if(procInfo.TransLog.TipAmt.isNotEmpty()) {
                    //element("TipAmt", procInfo.TransLog.TipAmt )
                    // element("TipAmt", appUtil.convertAmountDispFormat(procInfo.TransLog.TipAmt, Constants.GA_DCC, SysParam.getInstance().DeciPosAmt, 12) )
                    element("TipAmt", "0.00" )
                }
                if(procInfo.TransLog.ForceOnline.isNotEmpty()) {
                    element("ForceOnline", procInfo.TransLog.ForceOnline )
                }
                if(procInfo.TransLog.TxnId!!.isNotEmpty()) {
                    element("TxnId", procInfo.TransLog.TxnId!!)
                }
                if(procInfo.TransLog.InvoiceId.isNotEmpty()) {
                    element("InvoiceId", procInfo.TransLog.InvoiceId )
                    //element("InvoiceId", "2022060100183941" )
                }
                if(procInfo.TransLog.CardDataSource.isNotEmpty()) {
                    element("CardDataSource", procInfo.TransLog.CardDataSource )
                }
                if(procInfo.TransLog.CardNbr.isNotEmpty()) {
                    element("CardNbr", procInfo.TransLog.CardNbr )
                }
                if(procInfo.TransLog.ExpireMonth.isNotEmpty()) {
                    element("ExpireMonth", procInfo.TransLog.ExpireMonth )
                }
                if(procInfo.TransLog.ExpireYear.isNotEmpty()) {
                    element("ExpireYear", procInfo.TransLog.ExpireYear )
                }
                if(procInfo.TransLog.CVV.isNotEmpty()) {
                    element("CVV", procInfo.TransLog.CVV )
                }
                if(procInfo.TransLog.AuthCode!!.isNotEmpty()) {
                    element("AuthCode", procInfo.TransLog.AuthCode!! )
                }
                if(procInfo.TransLog.AuthTimeStamp!!.isNotEmpty()) {
                    element("AuthTimeStamp", procInfo.TransLog.AuthTimeStamp!! )
                }
                if(procInfo.TransLog.Token!!.isNotEmpty()) {
                    element("Token", procInfo.TransLog.Token!! )
                }
                if(procInfo.TransLog.Address!!.isNotEmpty() || procInfo.TransLog.ZIP!!.isNotEmpty()) {
                    element("AVS") {
                        if( procInfo.TransLog.Address!!.isNotEmpty() ) {
                            element("Addr", procInfo.TransLog.Address!! )
                        }
                        if(procInfo.TransLog.ZIP!!.isNotEmpty()) {
                            element("PostalCode", procInfo.TransLog.ZIP!! )
                        }
                    }
                }
                makeHealthCare(xmlSerializer, procInfo)
            }
        }
    }

    fun TransProc.makeHealthCare(xmlSerializer: XmlSerializer, procInfo: ProcInfo) {

        if(procInfo.TransLog.HealthCare.AccType.isNotEmpty()) {
            xmlSerializer.element("Healthcare") {
                element("AccType", procInfo.TransLog.HealthCare.AccType)
                element("PrescriptionAmt", procInfo.TransLog.HealthCare.PrescriptionAmt)
                element("VisionAmt", procInfo.TransLog.HealthCare.VisionAmt)
                element("DentalAmt", procInfo.TransLog.HealthCare.DentalAmt)
                element("ClinicAmt", procInfo.TransLog.HealthCare.ClinicAmt)
                element("QualifiedIIAS", procInfo.TransLog.HealthCare.QualifiedIIAS)
                element("CustomerId", procInfo.TransLog.HealthCare.CustomerId)
            }
        }
    }


    fun TransProc.makePreprocessTrans(xmlSerializer: XmlSerializer, procInfo: ProcInfo) {

        if(procInfo.PreTxn.TxnType != null && procInfo.PreTxn.TxnType!!.isNotEmpty() ) {
            xmlSerializer.element("Param") {
                element("TxnPreprocess") {
                    element("TxnType", procInfo.PreTxn.TxnType!!)
                }
            }
        }
    }

    fun TransProc.makeReportMgmt(xmlSerializer: XmlSerializer, procInfo: ProcInfo) {

        if( procInfo.ReportMgmt.Id != null && procInfo.ReportMgmt.Id!!.isNotEmpty() ) {
            xmlSerializer.element("Param") {
                element("Report") {
                    element("Id", procInfo.ReportMgmt.Id!!)
                    if(procInfo.ReportMgmt.Para !=null && procInfo.ReportMgmt.Para!!.isNotEmpty()) {
                        element("Para", procInfo.ReportMgmt.Para!!)
                    }
                }
            }
        }
    }

    fun TransProc.makeDiagMgmt(xmlSerializer: XmlSerializer, procInfo: ProcInfo) {

        if( procInfo.DiagMgmt.Id != null && procInfo.DiagMgmt.Id!!.isNotEmpty() ) {
            xmlSerializer.element("Param") {
                element("Report") {
                    element("Id", procInfo.DiagMgmt.Id!!)
                    if(procInfo.ReportMgmt.Para !=null && procInfo.DiagMgmt.IPAddr!!.isNotEmpty()) {
                        element("Para", procInfo.DiagMgmt.IPAddr!!)
                    }
                }
            }
        }
    }


    fun TransProc.makeSysMgmt(xmlSerializer: XmlSerializer, procInfo: ProcInfo) {

        if( procInfo.SysMgmt.Id != null && procInfo.SysMgmt.Id!!.isNotEmpty() ) {
            xmlSerializer.element("Param") {
                element("Sys") {
                    element("Id", procInfo.SysMgmt.Id!!)
                    if( procInfo.SysMgmt.AlarmCount !=null && procInfo.SysMgmt.AlarmCount!!.isNotEmpty() ) {
                        element("AlarmCount", procInfo.SysMgmt.AlarmCount!!)
                    }
                    if( procInfo.SysMgmt.AlarmDuration !=null && procInfo.SysMgmt.AlarmDuration!!.isNotEmpty() ) {
                        element("AlarmDuration",  procInfo.SysMgmt.AlarmDuration!!)
                    }
                    if( procInfo.SysMgmt.AlarmInterval !=null && procInfo.SysMgmt.AlarmInterval!!.isNotEmpty() ) {
                        element("AlarmInterval", procInfo.SysMgmt.AlarmInterval!!)
                    }
                }
            }

        }
    }

    fun makeBasicTrans( procInfo: ProcInfo) : String {

        val xmlSerializer = Xml.newSerializer()

        var sysParam = SysParam.getInstance()

        var xmlStr = xmlSerializer.document {
            element("Req") {
                if(sysParam.SourceMAC.isNotEmpty() && sysParam.DestMAC.isNotEmpty()) {
                    element("Header") {
                        element("SourceMAC", sysParam.SourceMAC)
                        element("DestMAC", sysParam.DestMAC)
                    }
                }
                element("Cmd") {
                    element("CmdId", procInfo.ReqCmd.CmdId)
                    element("CmdTout", procInfo.ReqCmd.CmdTout)
                }

                when(procInfo.ReqCmd.CmdId){
                    Constants.CMD_ID_START_TXN  -> makeStartTrans(xmlSerializer, procInfo)
                    Constants.CMD_ID_PRE_TXN    -> makePreprocessTrans(xmlSerializer, procInfo)
                    Constants.CMD_ID_GET_RESULT -> { /* none */}
                    Constants.CMD_ID_CANCEL_TXN -> { /* none */}
                    Constants.CMD_ID_SETTLE     -> { /* none */}
                    Constants.CMD_ID_REPORT_MGMT-> makeReportMgmt(xmlSerializer, procInfo)
                    Constants.CMD_ID_DIAG_MGMT  -> makeDiagMgmt(xmlSerializer, procInfo)
                    Constants.CMD_ID_SYSTEM_MGMT-> makeSysMgmt(xmlSerializer, procInfo)
                }

                if( procInfo.Auth.HashType!!.isNotEmpty() && procInfo.Auth.MAC!!.isNotEmpty() ) {
                    element("Auth") {
                        element("HashType", procInfo.Auth.HashType!!)
                        element("MAC", procInfo.Auth.MAC!!)
                    }
                }

            }
        }

        xmlStr = xmlStr.replace("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>", "")

        procInfo.SendData = xmlStr

        return xmlStr
    }


    fun makeEventRespTrans(procInfo: ProcInfo) : String {

        val xmlSerializer = Xml.newSerializer()

        //var sysParam = SysParam.getInstance()

        var xmlStr = xmlSerializer.document {
            element("EventResp") {
                element("Type") {
                    when(procInfo.ReqEvent.Type) {
                        "ReqSelectItem" -> element("SelectItem", procInfo.RespEvent.SelectItem)
                    }
                }
            }
        }

        xmlStr = xmlStr.replace("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>", "")

        procInfo.SendData = xmlStr

        return xmlStr
    }

    fun respDataConvert(procInfo: ProcInfo, msg: String) : ErrCode {
        var ret = ErrCode.NO_ERR

        try {
            //creating a XmlPull parse Factory instance
            val parserFactory = XmlPullParserFactory.newInstance()
            val parser = parserFactory.newPullParser()

            // setting the namespaces feature to false
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)

            // setting the input to the parser
            val inputStream: InputStream = msg.byteInputStream()
            parser.setInput(inputStream, null)

            // working with the input stream
            var tag: String?
            var text: String? = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                tag = parser.name
                when (event) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "Event" -> procInfo.RespCmdType = "Event"
                        "Resp" -> procInfo.RespCmdType = "Resp"
                        "ReqDispMesg" -> procInfo.ReqEvent.Type = "ReqDispMesg"
                        "ReqTxnData" -> procInfo.ReqEvent.Type = "ReqTxnData"
                        "ReqConfirm" -> procInfo.ReqEvent.Type = "ReqConfirm"
                        "ReqSelectItem" -> procInfo.ReqEvent.Type = "ReqSelectItem"
                        "ReqPINBlock" -> procInfo.ReqEvent.Type = "ReqPINBlock"
                    }

                    XmlPullParser.TEXT -> {
                        text = ""
                        if (parser.text.isNotEmpty()) {
                            text = parser.text
                        }

                    }

                    XmlPullParser.END_TAG -> {
                        when (tag) {
                            // Cmd
                            "CmdId" -> procInfo.RespCmd.CmdId = text
                            "StatusCode" -> procInfo.RespCmd.StatusCode = text
                            "StatusText" -> procInfo.RespCmd.StatusText = text

                            // Event
                            "MesgId" -> procInfo.ReqEvent.MesgId = text
                            "MesgStr" -> procInfo.ReqEvent.MesgStr = text
                            "MesgStrList" -> procInfo.ReqEvent.MesgStrList = text

                            // Data/Txn
                            "TxnResult" -> procInfo.TransLog.TxnResult = text
                            "TxnResultMsg" -> procInfo.TransLog.TxnResultMsg = text
                            "RspCode" -> procInfo.TransLog.RspCode = text
                            "RspDT" -> procInfo.TransLog.RspDT = text
                            "RspText" -> procInfo.TransLog.RspText = text
                            "TxnId" -> procInfo.TransLog.TxnId = text
                            "TxnAmt" -> procInfo.TransLog.TxnAmt = text.toString()
                            "AuthCode" -> procInfo.TransLog.AuthCode = text
                            "RefNbr" -> procInfo.TransLog.RefNbr = text
                            "ExternalRefNumber" -> procInfo.TransLog.ExternalRefNumber = text
                            "AuthorizationId" -> procInfo.TransLog.AuthorizationId = text
                            "FolioNumber" -> procInfo.TransLog.FolioNumber = text
                            "OriginalTransactionId" -> procInfo.TransLog.OriginalTransactionId = text
                            "TipProcessingInfo" -> procInfo.TransLog.TipProcessingInfo = text
                            "AppName" -> procInfo.TransLog.AppName = text
                            "AppId" -> procInfo.TransLog.AppId = text
                            "AID" -> procInfo.TransLog.AID = text
                            "AppLabel" -> procInfo.TransLog.AppLabel = text
                            "PAN" -> procInfo.TransLog.PAN = text
                            "CVMResult" -> procInfo.TransLog.CVMResult = text
                            "CVMText" -> procInfo.TransLog.CVMText = text
                            "TVR" -> procInfo.TransLog.TVR = text
                            "TSI" -> procInfo.TransLog.TSI = text
                            "AC" -> procInfo.TransLog.AC = text
                            "ATC" -> procInfo.TransLog.ATC = text
                            "ARC" -> procInfo.TransLog.ARC = text
                            "IAD" -> procInfo.TransLog.IAD = text
                            "CryptoInfo" -> procInfo.TransLog.CryptoInfo = text
                            "Cryptogram" -> procInfo.TransLog.Cryptogram = text
                            "TxnInterface" -> procInfo.TransLog.TxnInterface = text
                            "CardhdrName" -> procInfo.TransLog.CardhdrName = text
                            "Token" -> procInfo.TransLog.Token = text
                            "CardType" -> procInfo.TransLog.CardType = text
                            "AuthNtwkName" -> procInfo.TransLog.AuthNtwkName = text.toString()
                            "ExpireMonth" -> procInfo.TransLog.ExpireMonth = text.toString()
                            "ExpireYear" -> procInfo.TransLog.ExpireYear = text.toString()
                            "AcquirerId" -> procInfo.TransLog.AcquirerId = text.toString()
                            "IssuerId" -> procInfo.TransLog.IssuerId = text.toString()
                            "CardRangeId" -> procInfo.TransLog.CardRangeId = text.toString()
                            "CardRangeName" -> procInfo.TransLog.CardRangeName = text.toString()
                            "SignatureRequired" -> procInfo.TransLog.SignatureRequired = text.equals("1", ignoreCase = true) || text.equals("true", ignoreCase = true)
                            "SignatureCaptured" -> procInfo.TransLog.SignatureCaptured = text.equals("1", ignoreCase = true) || text.equals("true", ignoreCase = true)


                            // Data/NonTxn
                            "Track1" -> procInfo.TransLog.Track1 = text
                            "Track2" -> procInfo.TransLog.Track2 = text
                            "Track3" -> procInfo.TransLog.Track3 = text

                            // Error
                            "CurrState" -> procInfo.Error.CurrState = text
                            "ErrCode" -> procInfo.Error.ErrCode = text
                            "ErrText" -> procInfo.Error.ErrText = text

                            // Auth
                            "HashType" -> procInfo.Auth.HashType = text
                            "Hash" -> procInfo.Auth.MAC = text

                            // Settle
                            "BatchId" -> procInfo.TransLog.BatchId = text
                            "TxnCnt" -> procInfo.TransLog.TxnCnt = text
                            "TotalAmt" -> procInfo.TransLog.TotalAmt = text
                            "BatchSeqNbr" -> procInfo.TransLog.BatchSeqNbr = text

                            "Id" -> {
                                procInfo.RespId = text
                                text = ""
                            }
                            else -> {
                                //Info management, Report management, Diagnostic management, system management
                                if (procInfo.RespId!!.isNotEmpty() && text!!.isNotEmpty()) {
                                    procInfo.dataHashMap[tag] = text
                                }

                                text = ""
                            }

                        }
                    }
                }
                event = parser.next()
            }

        } catch (e: XmlPullParserException) {
            Log.d(TAG, "Unknown XML Tag")

            ret = ErrCode.ERR_PROC_RECV_DATA
            return ret
        }

        return ret
    }

    fun reqDataConvert(procInfo: ProcInfo) : ErrCode {
        // XML builder

        var ret = ErrCode.NO_ERR

        when(procInfo.ReqCmd.CmdId){
            Constants.CMD_ID_START_TXN  -> makeBasicTrans(procInfo)
            Constants.CMD_ID_PRE_TXN    -> makeBasicTrans(procInfo)
            Constants.CMD_ID_GET_RESULT -> makeBasicTrans(procInfo)
            Constants.CMD_ID_CANCEL_TXN -> makeBasicTrans(procInfo)
            Constants.CMD_ID_SETTLE     -> makeBasicTrans(procInfo)
            Constants.CMD_ID_REPORT_MGMT-> makeBasicTrans(procInfo)
            Constants.CMD_ID_DIAG_MGMT  -> makeBasicTrans(procInfo)
            Constants.CMD_ID_SYSTEM_MGMT-> makeBasicTrans(procInfo)
            else -> ret = ErrCode.ERR_NOT_SUPPORT_TRANS
        }
        if (ret == ErrCode.ERR_NOT_SUPPORT_TRANS) {
            Log.d("reqDataConvert", "Not support this txn")
        }

        return ret
    }

    fun saveStartTransResp(procInfo: ProcInfo){
        Log.d("saveStartTransResp",procInfo.ReqCmd.CmdId.toString())
    }

    fun saveTransResp(procInfo: ProcInfo) {
        Log.d("saveTransResp",procInfo.ReqCmd.CmdId.toString())

    }


    fun saveRecvPackData(procInfo: ProcInfo): ErrCode {
        // XML parse
        var ret = ErrCode.NO_ERR

        var RespStr = "Resp"
        when(procInfo.RespCmd.CmdId){
            Constants.CMD_ID_START_TXN+RespStr  -> saveStartTransResp(procInfo)
            Constants.CMD_ID_PRE_TXN+RespStr    -> saveTransResp(procInfo)
            Constants.CMD_ID_GET_RESULT+RespStr -> saveTransResp(procInfo)
            Constants.CMD_ID_CANCEL_TXN+RespStr -> saveTransResp(procInfo)
            Constants.CMD_ID_SETTLE+RespStr     -> saveTransResp(procInfo)
            Constants.CMD_ID_REPORT_MGMT+RespStr-> saveTransResp(procInfo)
            Constants.CMD_ID_DIAG_MGMT+RespStr  -> saveTransResp(procInfo)
            Constants.CMD_ID_SYSTEM_MGMT+RespStr-> saveTransResp(procInfo)
            else -> ret = ErrCode.ERR_NOT_SUPPORT_TRANS
        }
        if (ret == ErrCode.ERR_NOT_SUPPORT_TRANS) {
            Log.d("SaveRecvData", "Not support this txn")
        }

        return ret
    }

    private fun procRecvDisMesgEvent(procInfo: ProcInfo) : ErrCode {
        val msgId = procInfo.ReqEvent.MesgId
        if (msgId.isNullOrBlank()) {
            dispUtil.dispMsg(Constants.ACTION_DISP_MSG.toString(), procInfo.ReqEvent.MesgStr!!.toString() )
        } else {
            dispUtil.dispMsg(Constants.ACTION_DISP_MSG.toString(), msgId)
        }
//        if (procInfo.ReqEvent.Type == "ReqDispMesg") {
//            val msgId = procInfo.ReqEvent.MesgId
//            if (msgId.isNullOrBlank()) {
//                dispUtil.dispMsg(Constants.ACTION_DISP_MSG.toString(), procInfo.ReqEvent.MesgStr!!.toString() )
//            } else {
//                dispUtil.dispMsg(Constants.ACTION_DISP_MSG.toString(), msgId)
//            }
//        } else if (procInfo.ReqEvent.Type == "ReqSelectItem") {
//            val msgId = procInfo.ReqEvent.MesgStrList
//            if (msgId.isNullOrBlank()) {
//
//            } else {
//                dispUtil.dispMsg(Constants.ACTION_DISP_APPL_SELECT.toString(), msgId)
//            }


//        if(procInfo.ReqEvent.MesgId == "24") {
//             val dispAmt = context.getString(R.string.msg_amount) + " "+appUtil.convertAmtText(procInfo)
//             dispUtil.dispCardEntry("", dispAmt + "\n" +procInfo.ReqEvent.MesgStr!!.toString())
//        } else {
//             dispUtil.dispMsg("", procInfo.ReqEvent.MesgStr!!.toString() )
//        }

        return ErrCode.NO_ERR
    }

    private fun procRecvSelectItemEvent(procInfo: ProcInfo) : ErrCode {
        Log.d("ApplSelecResultListener", "ProcRecvSelectItemEvent: $procInfo")

        var ret : ErrCode

        if( procInfo.ReqEvent.MesgStrList != null && procInfo.ReqEvent.MesgStrList!!.isNotEmpty()) {

            var applList = procInfo.ReqEvent.MesgStrList!!.split("\n").toMutableList()

            // remove starting position within the character " "
            for( ii in applList.indices) {
                applList[ii] = applList[ii].replace(("^ +(?!$)").toRegex(), "")
            }

            // remove empty string
            applList.removeAll(listOf("", " ",null))

            ret = dispUtil.dispApplSelect("",
                applList,
                Constants.USER_OPER_TIMEOUT,
                object : DispUtil.ApplSelectListener<ErrCode, String> {
                    override fun onApplSelectResult(
                        status: ErrCode,
                        stringText: String)
                    {
                        Log.d("ApplSelecResultListener", "Status code: $status\nString text: $stringText")
                        // reply event resp
                        if(status == ErrCode.NO_ERR) {
                            procInfo.RespEvent.SelectItem = stringText
                            sendEventResp(procInfo)
                        } else {
                            Log.d("ProcRecvApplSelect", "Sending cancel req")
                            val procInfoCancel = ProcInfo(ReqCmd = ReqCmd(CmdId = Constants.CMD_ID_CANCEL_TXN, CmdTout = SysParam.getInstance().CmdTout))
                            makeBasicTrans(procInfoCancel)
                            socketClient.sendMsg(procInfoCancel.SendData)
                        }
                    }
                }
            )

            if(ret != ErrCode.NO_ERR) {
                // dispUtil.dispResult(ret, procInfo, Constants.USER_OPER_TIMEOUT_SHORT)
                Log.d("ProcRecvSelectItemEvent", ret.toString())
            }
        }

        // WHY DO WE RETURN NO ERROR HERE INSTEAD OF USER CANCEL OR SOMETHING? IDK BUT SOCKET
        // ONLY STAYS CONNECTED IF WE RETURN NO_ERR AND WE NEED IT TO STAY CONNECTED TO GET THE
        // TRANSACTION CANCELLED MESSAGE FROM TERMINAL ASDKLFJKLZXCJLKJK

        return ErrCode.NO_ERR
    }

    fun procRecvPacket(procInfo: ProcInfo) : ErrCode {

        var ret = ErrCode.NO_ERR

        if(procInfo.RespCmdType == "Event") {
            Log.d(TAG, "Receive Event")

            when(procInfo.ReqEvent.Type) {
                "ReqDispMesg" -> ret = procRecvDisMesgEvent(procInfo)
                "ReqSelectItem" -> ret = procRecvSelectItemEvent(procInfo)
            }

        } else if (procInfo.RespCmdType == "Resp") {
            Log.d(TAG, "Receive Resp")
            ret = saveRecvPackData(procInfo)
            isRecvFinish = true
        }

        return ret
    }

    fun sendEventResp(procInfo: ProcInfo) {

        makeEventRespTrans(procInfo)

        socketClient.sendMsg(procInfo.SendData)

    }

    fun sendRecvPacket(procInfo: ProcInfo): ErrCode {
        val hostSettings = HostSettingsResolver.resolve(procInfo.TransLog.AcquirerId)
            ?: return ErrCode.ERR_COMM_OPEN_FAIL
        val endpoints = buildList {
            hostSettings.primary?.takeIf { it.port > 0 }?.let { add(it) }
            hostSettings.secondary?.takeIf { it.port > 0 }?.let { add(it) }
        }
        if (endpoints.isEmpty()) {
            Log.w(TAG, "No host endpoints configured for acquirer ${procInfo.TransLog.AcquirerId}")
            return ErrCode.ERR_COMM_OPEN_FAIL
        }

        val connectTimeout = hostSettings.connectTimeoutSeconds.coerceAtLeast(1)
        val readTimeout = hostSettings.readTimeoutSeconds.coerceAtLeast(1)
        val sendTimeout = readTimeout
        val attempts = hostSettings.attempts.coerceAtLeast(1)

        Log.d(TAG, "Request XML length=${procInfo.SendData.length} chars")

        var result: ErrCode = ErrCode.ERR_COMM_OPEN_FAIL

        repeat(attempts) { attemptIndex ->
            endpoints.forEachIndexed { index, endpoint ->
                val retries = if (index == 0) hostSettings.primaryRetries else hostSettings.secondaryRetries
                repeat(retries.coerceAtLeast(1)) { retryIndex ->
                    result = transmitWithEndpoint(
                        endpoint = endpoint,
                        useTls = hostSettings.isTls,
                        connectTimeoutSec = connectTimeout,
                        sendTimeoutSec = sendTimeout,
                        readTimeoutSec = readTimeout,
                        procInfo = procInfo,
                    )
                    if (result == ErrCode.NO_ERR) {
                        Log.d(
                            TAG,
                            "Transmission succeeded on attempt=${attemptIndex + 1} retry=${retryIndex + 1} endpoint=${endpoint.displayValue}"
                        )
                        return result
                    }
                }
            }
        }

        return result
    }

    private fun transmitWithEndpoint(
        endpoint: HostAddress,
        useTls: Boolean,
        connectTimeoutSec: Int,
        sendTimeoutSec: Int,
        readTimeoutSec: Int,
        procInfo: ProcInfo,
    ): ErrCode {
        var ret: ErrCode = ErrCode.NO_ERR
        Log.d(
            TAG,
            "sendRecvPacket starting with SSL=$useTls host=${endpoint.displayValue} connectTimeout=${connectTimeoutSec}s sendTimeout=${sendTimeoutSec}s readTimeout=${readTimeoutSec}s"
        )

        socketClient = if (useTls) {
            SSLClient()
        } else {
            TcpClient()
        }

        isRecvFinish = false
        val recvMsgListener = object : SocketClientRecvMsgListener<ErrCode, String> {
            override fun onRecvMsg(status: ErrCode, msg: String) {
                ret = status
                if (status == ErrCode.NO_ERR) {
                    do {
                        if (msg.isEmpty()) {
                            ret = ErrCode.ERR_COMM_RECEIVE_FAIL
                            break
                        }

                        val sanitized = LogSanitizer.sanitizeMessage(msg)
                        Log.d(TAG, "Receive [" + msg.length + "]: \n" + sanitized)
                        val hexPayload = LogSanitizer.sanitizeHexPayload(
                            clearPan = procInfo.TransLog.PAN,
                            track1 = procInfo.TransLog.Track1,
                            track2 = procInfo.TransLog.Track2,
                            track3 = procInfo.TransLog.Track3,
                        ) { msg.toByteArray().toHexString() }
                        Log.d(TAG, "Receive HEX: $hexPayload")

                        ret = respDataConvert(procInfo, msg)
                        if (ret == ErrCode.ERR_NOT_SUPPORT_TRANS && BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                            Log.d("OnRecvMsg", "Not supported with msg: ${LogSanitizer.sanitizeMessage(msg)}")
                        }

                        if (ret != ErrCode.NO_ERR) break

                        procInfo.RecvData = msg

                        ret = procRecvPacket(procInfo)
                        if (ret == ErrCode.ERR_NOT_SUPPORT_TRANS && BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                            Log.d("OnRecvMsg", "Not supported with msg: ${LogSanitizer.sanitizeMessage(msg)}")
                        }
                        if (ret != ErrCode.NO_ERR) break

                    } while (false)
                }
            }

        }

        socketClient.init(
            endpoint.host,
            endpoint.port,
            connectTimeoutSec,
            sendTimeoutSec,
            readTimeoutSec
        )
        socketClient.setRecvMsgListener(recvMsgListener)
        socketClient.start()

        do {
            dispUtil.dispMsg(
                GlobalConnectPaymentApplication.instance.resources.getString(R.string.msg_comm_title),
                GlobalConnectPaymentApplication.instance.resources.getString(R.string.msg_comm_connecting)
            )
            ret = socketClient.connect()
            if (ret != ErrCode.NO_ERR) {
                break
            }

            dispUtil.dispMsg(
                GlobalConnectPaymentApplication.instance.resources.getString(R.string.msg_comm_title),
                GlobalConnectPaymentApplication.instance.resources.getString(R.string.msg_comm_send)
            )

            ret = socketClient.sendMsg(procInfo.SendData)
            if (ret == ErrCode.NO_ERR) {
                Log.d(TAG, "Host request transmitted successfully to ${endpoint.displayValue}")
            } else {
                Log.d(TAG, "Host request failed to send with status=$ret")
            }
            if (ret != ErrCode.NO_ERR) {
                break
            }

            dispUtil.dispMsg(
                GlobalConnectPaymentApplication.instance.resources.getString(R.string.msg_comm_title),
                GlobalConnectPaymentApplication.instance.resources.getString(R.string.msg_comm_recv)
            )
            while (true) {
                Thread.sleep(1)

                if (isRecvFinish || ret != ErrCode.NO_ERR) {
                    break
                }
            }

        } while (false)

        socketClient.disconnect()
        return ret
    }

}
