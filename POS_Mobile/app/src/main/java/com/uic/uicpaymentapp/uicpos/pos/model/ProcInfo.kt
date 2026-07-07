package com.uic.uicpaymentapp.uicpos.pos.model

import android.util.Log
import com.uic.uicpaymentapp.BuildConfig
import com.uic.uicpaymentapp.records.dateTimeFormatter
import com.uic.uicpaymentapp.security.EncryptionUtil
import com.uic.uicpaymentapp.transaction.CVMStringtoCvmType
import com.uic.uicpaymentapp.transaction.CheckStatus
import com.uic.uicpaymentapp.transaction.Transaction
import com.uic.uicpaymentapp.transaction.TransactionType
import com.uic.uicpaymentapp.transaction.obfuscatePAN
import com.uic.uicpaymentapp.transaction.transactionStringToTransactionType
import com.uic.uicpaymentapp.util.LogSanitizer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.HashMap

data class ProcInfo(
    var TransLog: TransLog = TransLog(),
    var SendData: String = "",
    var RecvData: String = "",
    var ReqCmd: ReqCmd = ReqCmd(),
    var RespCmd: RespCmd = RespCmd(),
    var RespCmdType: String = "", /// Resp, Event
    var RespId: String? = "", //response Id field
    var Auth: Auth = Auth(),
    var Error: RespError = RespError(),
    var ReqEvent: ReqEventType = ReqEventType(),
    var RespEvent: RespEventType = RespEventType(),
    var ReportMgmt: ReportMgmt = ReportMgmt(),
    var SysMgmt: SysMgmt = SysMgmt(),
    var PreTxn: PreTxn = PreTxn(),
    var DiagMgmt: DiagMgmt = DiagMgmt(),

    var dataHashMap: HashMap<String, String?> = HashMap()
)

fun ProcInfo.toTransaction(): Transaction {
    if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
        Log.d("ProcInfo", LogSanitizer.sanitizeTransLog(this.TransLog))
    }
    val cardEntryMethod = when (this.TransLog.TxnInterface) {
        "00" -> "UNSPECIFIED"
        "01" -> "MANUAL"
        "02" -> "SWIPE"
        "03" -> "EMV"
        "04" -> "EMV_CONTACTLESS"
        "05" -> "FALLBACK_SWIPE"
        "06" -> "06"
        "07" -> "07"
        "08" -> "08"
        else -> "${this.TransLog.TxnInterface}"
    }

    val totalAmount = this.TransLog.TxnAmt.replace(",", "")
    val tipAmount = this.TransLog.TipAmt.replace(",", "").ifBlank { "0.00" }
    val tax1Amount = this.TransLog.Tax1Amt.replace(",", "").ifBlank { "0.00" }
    val tax1DiscountAmount = this.TransLog.Tax1DiscountAmt.replace(",", "").ifBlank { "0.00" }
    val tax2Amount = this.TransLog.Tax2Amt.replace(",", "").ifBlank { "0.00" }
    val baseAmount = this.TransLog.BaseAmt.replace(",", "").ifBlank { "0.00" }

    val subTotal = BigDecimal(totalAmount)
        .minus(BigDecimal(tipAmount.ifBlank { "0.00" }))
        .setScale(2, RoundingMode.HALF_DOWN)
        .toPlainString()

    val expirationDate = if (this.TransLog.ExpireMonth.isNotBlank() && this.TransLog.ExpireYear.isNotBlank()) {
        val month = this.TransLog.ExpireMonth.padStart(2, '0')
        val year = this.TransLog.ExpireYear.takeLast(4)
        "$year-$month"
    } else {
        ""
    }

    val requiresSignature = this.TransLog.SignatureRequired ||
        (this.TransLog.CVMText?.equals("Signature", ignoreCase = true) == true)

    return Transaction(
        transactionId = this.TransLog.TxnId ?: "Err",
        stan = this.TransLog.TxnId ?: "Err",
        totalAmount = totalAmount,
        baseAmount = baseAmount,
        tipAmount = tipAmount,
        tax1Amount = tax1Amount,
        tax1DiscountAmount = tax1DiscountAmount,
        tax2Amount = tax2Amount,
        cardNumber = EncryptionUtil.encryptData(this.TransLog.PAN ?: ""),
        masked_cardNumber = obfuscatePAN(this.TransLog.PAN) ?: "************????",
        hashed_cardNumber = EncryptionUtil.hashPAN(this.TransLog.PAN ?: ""),
        accType = this.TransLog.AccType,
        invoiceId = this.TransLog.InvoiceId,
        acquirerId = this.TransLog.AcquirerId,
        issuerId = this.TransLog.IssuerId,
        cardRangeId = this.TransLog.CardRangeId,
        cardRangeName = this.TransLog.CardRangeName,
        type = this.TransLog.TxnType.transactionStringToTransactionType(),
        localDateTime = LocalDateTime.now().format(dateTimeFormatter),
        cardType = this.TransLog.CardType ?: "Err",
        authNtwkName = this.TransLog.AuthNtwkName,
        cardEntryMethod = cardEntryMethod,
        CVM = this.TransLog.CVMText.CVMStringtoCvmType(),
        CVMText = this.TransLog.CVMText ?: "",
        AID = this.TransLog.AID ?: "Err",
        TVR = this.TransLog.TVR ?: "Err",
        TSI = this.TransLog.TSI ?: "Err",
        AC = this.TransLog.AC ?: "Err",
        ATC = this.TransLog.ATC ?: "Err",
        ARC = this.TransLog.ARC ?: "Err",
        IAD = this.TransLog.IAD ?: "Err",
        emvCryptoInformation = this.TransLog.CryptoInfo ?: this.TransLog.IAD ?: "",
        emvCryptogram = this.TransLog.Cryptogram ?: this.TransLog.AC ?: "",
        applicationLabel = this.TransLog.AppLabel ?: this.TransLog.AppName ?: "",
        cardExpirationDate = expirationDate,
        retrievalReferenceNumber = this.TransLog.RefNbr ?: "",
        externalReferenceNumber = this.TransLog.ExternalRefNumber ?: "",
        authCode = this.TransLog.AuthCode ?: "Err",
        authorizationId = this.TransLog.AuthorizationId ?: this.TransLog.AuthCode ?: "",
        applicationName = this.TransLog.AppName ?: "Err",
        folioNumber = this.TransLog.FolioNumber ?: "",
        originalTransactionId = this.TransLog.OriginalTransactionId ?: "",
        tipProcessingInformation = this.TransLog.TipProcessingInfo ?: "",
        signatureRequired = requiresSignature,
        signatureCaptured = this.TransLog.SignatureCaptured,
        checkStatus = when (this.TransLog.TxnType.transactionStringToTransactionType()) {
            TransactionType.AUTHONLY -> CheckStatus.NeedTip
            TransactionType.CHECKIN -> CheckStatus.Open
            else -> CheckStatus.Closed
        },
        subTotal = subTotal,
        token = this.TransLog.Token ?: "",
        paymentPlan = this.TransLog.PaymentPlan,
        alternateHostResponse = this.TransLog.AlternateHostResponse ?: "",
        additionalHostPrintData = this.TransLog.AdditionalHostPrintData ?: "",
        paymentPlanQueryResponse = this.TransLog.PaymentPlanQueryResponse ?: ""
    )
}
