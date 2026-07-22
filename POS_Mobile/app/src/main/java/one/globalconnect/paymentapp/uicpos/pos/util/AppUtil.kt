package one.globalconnect.paymentapp.uicpos.pos.util

import android.content.Context
import android.os.Handler
import android.util.Log
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.uicpos.pos.errcode.Constants
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.host.InvoiceNumberProvider
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import java.text.SimpleDateFormat
import java.util.*


class AppUtil
{

    companion object {
        private val TAG = this::class.java.simpleName
    }

    constructor() {

    }

    constructor(
        context: Context,
        uiHandler: Handler?
    ) {
        this.context = context
        this.uiHandler =  uiHandler
        this.dispUtil = DispUtil(uiHandler)
    }

    object AmoutObj {
        lateinit var amtResult: String
    }

    private lateinit var context: Context
    private var uiHandler: Handler? = null
    private lateinit var dispUtil: DispUtil

    fun pubAscAdd(addend1: String, addend2: String, maxLen: Int): String {

        var intAdd1: Int = 0
        if(addend1.isNotEmpty())  {
            intAdd1 = addend1.toInt()
        }

        var intAdd2: Int = 0
        if(addend2.isNotEmpty()) {
            intAdd2 = addend2.toInt()
        }

        var intAdd = intAdd1 + intAdd2
        var strAdd = String.format("%d", intAdd)
        repeat(maxLen - strAdd.length) {
            strAdd = '0' + strAdd
        }

        return strAdd
    }

    fun chkAllowTipAmount(procInfo: ProcInfo): Boolean {

//        if(!SysParam.getInstance().TipEnable) {
//            return false
//        }

        if(procInfo.TransLog.TxnType == Constants.HOST_TRANS_SALE ||
            procInfo.TransLog.TxnType == Constants.HOST_TRANS_TIP_ADJ ||
            procInfo.TransLog.TxnType == Constants.HOST_TRANS_AUTH_ONLY ||
            procInfo.TransLog.TxnType == Constants.HOST_TRANS_AUTH_CAPTURE ||
            procInfo.TransLog.TxnType == Constants.HOST_TRANS_FORCE_SALE ) {
            return true
        }

        return false
    }

    fun validAdjustAmount(baseAmt: String, totalAmt: String,  isAdjAmt: Boolean ) : Boolean {

        if(!isAdjAmt) {
            if(  baseAmt.toInt() > totalAmt.toInt() ) {
                // amount incorrect

                dispUtil.dispResult(ErrCode.ERR_INVALID_AMOUNT, null, Constants.USER_OPER_TIMEOUT)

                return false
            }
        }

        if( baseAmt.toInt() == totalAmt.toInt() ) return true

        return true
    }

    // convert amount format: "000001000000"  -->  "1,000.00"
    fun convertAmountDispFormat(originAmountStr: String, amountMisc: Int, amountDeciPos: Int, maxAmtLen: Int): String {

        var amountStr = originAmountStr
        var deciPos = amountDeciPos

        // remove other character, only accept "0" ~ "9"
        amountStr = amountStr.replace(("[^0-9]").toRegex(), "")

        // remove starting position within the character "0"
        amountStr = amountStr.replace(("^0+(?!$)").toRegex(), "")

        if(amountStr.length> maxAmtLen) {
            return amountStr.substring(0, amountStr.length)
        }

        // for local currency (TW), decimal = 0
        if( amountMisc and Constants.GA_DCC == 0 ){
            deciPos = 0
        }

        //
        val bSeparator: Int = if( (amountMisc and Constants.GA_SEPARATOR) !=0 ) 1 else 0
        var currencyAmoutStr: String = ""
        val iInLen: Int = amountStr.length
        var iIntegerLen: Int

        // Amount: Integer part
        var ii : Int
        if (iInLen > amountDeciPos) {
            iIntegerLen = iInLen - amountDeciPos
            ii = iIntegerLen
            while (ii > 0) {
                currencyAmoutStr += amountStr.substring(iIntegerLen - ii, iIntegerLen - ii +1 )
                if ( (bSeparator == 1) && ((ii - 1) > 0) && (ii - 1) % 3 == 0) currencyAmoutStr += ','
                ii -= 1
            }
        } else {
            currencyAmoutStr = '0' + currencyAmoutStr
        }

        // Amount: Decimal part
        if (deciPos != 0) {
            var szTempLen: Int = if (amountDeciPos - amountStr.length > 0) amountDeciPos - amountStr.length else amountDeciPos
            var szTemp: String = ""
            if(amountStr.isNotEmpty()) {
                szTemp =  amountStr.substring(amountStr.length - szTempLen, amountStr.length)
            }

            ii = deciPos - amountStr.length
            while (ii > 0 && (ii--) > 0) {
                szTemp = "0$szTemp"
            }
            szTemp = ".$szTemp"
            currencyAmoutStr += szTemp
        }

        return currencyAmoutStr
    }

    // convert amount format: "1,000.00" --> "000001000000"
    fun convertAmountISOFormat(amount: String?, maxAmtLen: Int ) : String {

        var amtFormatStr : String = ""

        if(amount == null || amount.isEmpty()) {
            // append '0'
            repeat(maxAmtLen ) {
                amtFormatStr = '0' + amtFormatStr
            }

        } else  {
            amtFormatStr = amount.replace(("[^0-9]").toRegex(), "")

            // append '0'
            if( ( maxAmtLen - amtFormatStr.length ) > 0) {
                repeat(maxAmtLen - amtFormatStr.length) {
                    amtFormatStr = '0' + amtFormatStr
                }
            }
        }


        return amtFormatStr
    }


    fun getTransNameStr( transType: String ) : String {

        return when(transType) {
            Constants.HOST_TRANS_SALE         -> context.getString(R.string.trans_sale)
            Constants.HOST_TRANS_AUTH_ONLY    -> context.getString(R.string.trans_auth)
            Constants.HOST_TRANS_AUTH_ADD     -> context.getString(R.string.trans_auth_add)
            Constants.HOST_TRANS_AUTH_CAPTURE -> context.getString(R.string.trans_auth_capture)
            Constants.HOST_TRANS_TIP_ADJ      -> context.getString(R.string.trans_tip)
            Constants.HOST_TRANS_VOID         -> context.getString(R.string.trans_void)
            Constants.HOST_TRANS_REFUND       -> context.getString(R.string.trans_refund)
            else -> { "" }
        }

    }

    fun getTransAmtInfo(procInfo: ProcInfo): Int {

        var signChar: Int = 0

        if(procInfo.TransLog.TxnType == Constants.HOST_TRANS_VOID) {
            signChar = Constants.GA_NEGATIVE;
        }
        else if( procInfo.TransLog.TxnType == Constants.HOST_TRANS_REFUND )
        {
            signChar = Constants.GA_NEGATIVE;
        }

        signChar += Constants.GA_SEPARATOR or Constants.GA_LEADSIGN
        if(SysParam.getInstance().DeciPosAmt > 0) {
            signChar += Constants.GA_DCC
        }

        return signChar
    }

    fun convertAmtPrefix(amtPrefix: String, miscAmt: Int) : String {

        var amoutDispPrefix: String = amtPrefix

        if( (miscAmt and Constants.GA_LEADSIGN) == 0) {
            amoutDispPrefix = ""
        }

        if( (miscAmt and Constants.GA_NEGATIVE) != 0 ) {
            amoutDispPrefix = "-$amoutDispPrefix"
        }

        return amoutDispPrefix
    }

    fun convertAmtText(procInfo: ProcInfo) : String {

        var deciPos: Int          = SysParam.getInstance().DeciPosAmt
        var dispAmtPre: String    = SysParam.getInstance().CurrencyName
        var miscAmt: Int          = getTransAmtInfo(procInfo)

        var dispPrefix: String    = convertAmtPrefix(dispAmtPre, miscAmt)

        // add tips
        var amtText = pubAscAdd( procInfo.TransLog.TxnAmt, procInfo.TransLog.TipAmt, 12)

        var dispAmtText : String  = convertAmountDispFormat(amtText, miscAmt, deciPos,12 )

        var disText = dispPrefix + ' ' + dispAmtText

        return disText
    }

    fun dispConfirmAmtInfo(@Suppress("UNUSED_PARAMETER") procInfo: ProcInfo) : ErrCode {

        //var disText = context.getString(R.string.msg_amount_confirm) +"\n"+ convertAmtText(procInfo)

//        var ret = dispUtil.dispConfirmDialog( getTransNameStr(procInfo.TransLog.TxnType),
//            disText,
//            Constants.USER_OPER_TIMEOUT)
//
//        return ret
        return ErrCode.ERR_CODE_MOCK_TESTING
    }


//    fun setEditTextListener(inputTextLayout: com.google.android.material.textfield.TextInputLayout,
//                                    editText: EditText
//    ) {
//
//        editText.setOnFocusChangeListener { view, hasFocuse ->
//            if(hasFocuse && view != null) {
//                // clear error message
//                inputTextLayout.error = null
//            }
//        }
//    }
//
//    fun dispInvalidMsg(scrollView: ScrollView, textInputLayout: TextInputLayout,
//                               editText: EditText,
//                               errMsg: String)
//    {
//        // display error message
//        textInputLayout.error = errMsg
//
//        // scroll to invalid field
//        scrollView.scrollTo(0, textInputLayout.top)
//    }
//
//    fun setAmountEditTextListener(inputTextLayout: com.google.android.material.textfield.TextInputLayout?,
//                                          editText: EditText
//    ) {
//
//        val textChangeListener = object : TextWatcher {
//            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
//
//            }
//
//            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
//
//                editText.removeTextChangedListener(this);
//
//                var amountStr = AppUtil().convertAmountDispFormat( editText.text.toString(), Constants.GA_DCC or Constants.GA_SEPARATOR, 2, 12 )
//                Log.d(TAG, "Amount: $amountStr")
//
//                editText.setText(amountStr)
//                editText.setSelection(amountStr.length);
//                editText.addTextChangedListener(this)
//            }
//
//            override fun afterTextChanged(s: Editable?) {
//
//            }
//
//        }
//
//        if(inputTextLayout != null) {
//            setEditTextListener(inputTextLayout, editText)
//        }
//
//        editText.addTextChangedListener(textChangeListener)
//    }

//    fun getTipAmount(procInfo: ProcInfo) : ErrCode {
//
//        if (procInfo.TransLog.TipAmt.isNotEmpty())
//            return ErrCode.NO_ERR
//
//        val minAmtLen: Int = 1
//        val maxAmtLen: Int = 12
//
//        var dispAmtPrompt: String   = context.getString(R.string.msg_input_tip_amount)
//        var dispAmtPre: String      = SysParam.getInstance().CurrencyName
//        var dispInitAmtText: String = procInfo.TransLog.TipAmt
//        var amtObj: AmoutObj = AmoutObj
//        var deciPos: Int            = SysParam.getInstance().DeciPosAmt
//
//        var miscAmt: Int = getTransAmtInfo(procInfo)
//
//        var ret = ErrCode.NO_ERR
//        do {
//            ret = ErrCode.NO_ERR
//            AmoutObj.amtResult = ""
//
//            ret = dispGetAmount(
//                procInfo,
//                dispAmtPrompt,
//                dispAmtPre,
//                dispInitAmtText,
//                amtObj,
//                deciPos,
//                minAmtLen,
//                maxAmtLen,
//                miscAmt,
//                Constants.USER_OPER_TIMEOUT
//            )
//
//            if (ret != ErrCode.NO_ERR) {
//                return ret
//            }
//
//            var tipAmt = convertAmountISOFormat(AmoutObj.amtResult, maxAmtLen )
//
//            val totalAmt: String = pubAscAdd(procInfo.TransLog.TxnAmt, tipAmt, maxAmtLen)
//            if(validAdjustAmount( procInfo.TransLog.TxnAmt,  totalAmt, false)) {
//                break
//            }
//
//            // tip amount incorrect, re-input
//            dispUtil.dispResult(ErrCode.ERR_TIPS_AMOUNT_INCORRECT, procInfo, Constants.USER_OPER_TIMEOUT )
//
//        }while(true)
//
//        procInfo.TransLog.TipAmt = AmoutObj.amtResult
//
//        return ret
//    }
//
//    fun chkAmountValild(amount: String, minAmtLen: Int, maxAmtLen: Int,): Boolean {
//
//        // remove other character, only accept "0" ~ "9"
//        val inputAmout = amount.replace(("[^0-9]").toRegex(), "")
//
//
//        if( inputAmout.length in minAmtLen until maxAmtLen) {
//            return true
//
//        } else {
//
//            if( !(minAmtLen == 0) && (inputAmout.length == 0)) {
//                return false
//
//            }
//
//            return true
//        }
//
//        return false
//    }

    fun getAmount(procInfo: ProcInfo) : ErrCode {

        if(procInfo.TransLog.TxnAmt.isNotEmpty())
            return ErrCode.NO_ERR

        val minAmtLen: Int = 1
        val maxAmtLen: Int = 12

        var dispAmtPrompt: String   = context.getString(R.string.msg_amount)
        var dispAmtPre: String      = SysParam.getInstance().CurrencyName
        var dispInitAmtText: String = procInfo.TransLog.TxnAmt
        var amtObj: AmoutObj = AmoutObj
        var deciPos: Int            = SysParam.getInstance().DeciPosAmt
        var miscAmt: Int            = getTransAmtInfo(procInfo)

        var ret = dispGetAmount(procInfo,
            dispAmtPrompt,
            dispAmtPre,
            dispInitAmtText,
            amtObj,
            deciPos,
            minAmtLen,
            maxAmtLen,
            miscAmt,
            Constants.USER_OPER_TIMEOUT )

        if(ret != ErrCode.NO_ERR) {
            return ret
        }

        // add "0"
        procInfo.TransLog.TxnAmt = AmoutObj.amtResult
        repeat( (maxAmtLen - procInfo.TransLog.TxnAmt.length)  ) {
                procInfo.TransLog.TxnAmt = '0' + procInfo.TransLog.TxnAmt
        }

//        // get tip amount
//        if( chkAllowTipAmount(procInfo) ) {
//            ret= getTipAmount(procInfo)
//            if(ret != ErrCode.NO_ERR) return ret
//        }

        // display confirm message
        ret = dispConfirmAmtInfo(procInfo)

        return ret
    }

    fun getTransID(procInfo: ProcInfo) : ErrCode {
        var ret = ErrCode.NO_ERR

        var stringResult: String = ""
        //val promptText: String = context.getString(R.string.msg_transaction_id)
//        ret = dispUtil.dispInputString(
//            promptText,
//            "",
//            Constants.NUM_IN,
//            1,
//            20,
//            Constants.USER_OPER_TIMEOUT,
//            object : DispUtil.InputStringListener<ErrCode, String> {
//                override fun onStringResult(status: ErrCode, stringText: String) {
//                    ret = status
//                    stringResult = stringText
//                }
//            })

        if (ret != ErrCode.NO_ERR) return ret

        Log.d(TAG, "Transaction ID: $stringResult")
        procInfo.TransLog.TxnId = stringResult

        return ret
    }

    fun getAccountType(procInfo: ProcInfo) : ErrCode {
        var ret = ErrCode.NO_ERR

        var stringResult: String = ""
        //val promptText: String = context.getString(R.string.msg_account_type)
//        ret = dispUtil.dispSelectAccountType(
//            "",
//            promptText,
//            Constants.USER_OPER_TIMEOUT,
//            object : DispUtil.selectAccountTypeListener<ErrCode, String> {
//                override fun onSelectAccountTypeResult(status: ErrCode, stringText: String) {
//                    ret = status
//                    stringResult = stringText
//                }
//            })

        if (ret != ErrCode.NO_ERR) return ret

        Log.d(TAG, "Account Type: $stringResult")
        procInfo.TransLog.AccType = stringResult

        return ret
    }

    fun getNewInvoiceNo(): String {
        return InvoiceNumberProvider.nextInvoiceNumber()
    }

    fun getNewAuthTimeStamp(): String {
        // current timestamp
        val tsLong = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
        val netDate = Date(tsLong)
        return sdf.format(netDate)
    }

    // get current date/time, format: YYYYMMDDHHMMSS
    fun getCurrentDateTime() : String {
        // current timestamp
        val tsLong = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyyMMddhhmmss")
        val netDate = Date(tsLong)
        return sdf.format(netDate)
    }


    fun getInvoiceNo(procInfo: ProcInfo) : ErrCode {
        var ret = ErrCode.NO_ERR

        var stringResult: String = ""
        //val promptText: String = context.getString(R.string.msg_invoice_no)
//        ret = dispUtil.dispInputString(
//            promptText,
//            "",
//            Constants.ALPHA_IN,
//            0,
//            20,
//            Constants.USER_OPER_TIMEOUT,
//            object : DispUtil.InputStringListener<ErrCode, String> {
//                override fun onStringResult(status: ErrCode, stringText: String) {
//                    ret = status
//                    stringResult = stringText
//                }
//            })

        if (ret != ErrCode.NO_ERR) return ret

        Log.d(TAG, "Invoice No.: $stringResult")
        procInfo.TransLog.InvoiceId = stringResult

        return ret
    }

    fun getAuthCode(procInfo: ProcInfo) : ErrCode {
        var ret = ErrCode.NO_ERR

        if(procInfo.TransLog.TxnType != Constants.HOST_TRANS_FORCE_SALE)
            return ret

        var stringResult: String = ""
        //val promptText: String = context.getString(R.string.msg_auth_code)
//        ret = dispUtil.dispInputString(
//            promptText,
//            "",
//            Constants.ALPHA_IN,
//            0,
//            10,
//            Constants.USER_OPER_TIMEOUT,
//            object : DispUtil.InputStringListener<ErrCode, String> {
//                override fun onStringResult(status: ErrCode, stringText: String) {
//                    ret = status
//                    stringResult = stringText
//                }
//            })

        if (ret != ErrCode.NO_ERR) return ret

        Log.d(TAG, "Auth Code: $stringResult")
        procInfo.TransLog.AuthCode = stringResult

        return ret
    }

    fun dispGetAmount(@Suppress("UNUSED_PARAMETER") procInfo: ProcInfo,
                      @Suppress("UNUSED_PARAMETER") dispAmtPrompt: String,
                      @Suppress("UNUSED_PARAMETER") dispAmtPre:String,
                      dispInitAmt: String,
                      @Suppress("UNUSED_PARAMETER") amtObj: AmoutObj,
                      deciPos: Int,
                      minAmtLen: Int,
                      maxAmtLen: Int,
                      @Suppress("UNUSED_PARAMETER") miscAmt: Int,
                      @Suppress("UNUSED_PARAMETER") timeoutSec: Int = Constants.USER_OPER_TIMEOUT
    ): ErrCode {

        if( (deciPos > 5) || (minAmtLen>maxAmtLen) || (maxAmtLen > 12) )
            return ErrCode.ERR_INVALID_AMOUNT

        var ret:ErrCode = ErrCode.NO_ERR

        //var amoutResult: String = ""
        //var amoutDispPrefix: String =  convertAmtPrefix(dispAmtPre, miscAmt)

        dispInitAmt.trimStart('0')

//        do {
//            // input amount
//            ret = dispUtil.dispInputAmount(dispAmtPrompt,
//                amoutDispPrefix,
//                dispInitAmt,
//                deciPos,
//                minAmtLen,
//                maxAmtLen,
//                miscAmt,
//                timeoutSec,
//                object : DispUtil.inputAmoutListener<ErrCode, String> {
//                    override fun onAmountResult(status: ErrCode, amoutText: String) {
//                        ret = status
//                        amoutResult = amoutText
//                    }
//
//                }
//
//            )
//            if (ret != ErrCode.NO_ERR) break
//
//            if( (miscAmt and Constants.GA_DCC) == 0 ) {
//                amoutResult +="00"
//            }
//
//            Log.d(TAG, "Input Amount : " + amoutResult)
//            AmoutObj.amtResult = amoutResult
//
//        }while(false)

        return ret
    }

}