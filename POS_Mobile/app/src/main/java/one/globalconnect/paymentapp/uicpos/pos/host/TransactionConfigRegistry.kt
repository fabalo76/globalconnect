package one.globalconnect.paymentapp.uicpos.pos.host

import androidx.annotation.StringRes
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.transaction.AmountPromptConfig
import one.globalconnect.paymentapp.transaction.CurrencyTable

/**
 * Kotlin representation of the legacy `glTranConfig` table defined in the
 * A10TSTD `global.cpp` module. The structure captures both the ISO8583
 * identifiers and behavioural flags associated with every supported
 * transaction.
 */
object TransactionConfigRegistry {

    enum class TransactionCode(val code: String, val legacyTransaction: LegacyTransaction) {
        SALE(code = "Sale", legacyTransaction = LegacyTransaction.SALE),
        REFUND(code = "Refund", legacyTransaction = LegacyTransaction.REFUND),
        VOID(code = "Void", legacyTransaction = LegacyTransaction.VOID),
        TIP_ADJUST(code = "TipAdj", legacyTransaction = LegacyTransaction.SALE_COMP),
        PAYMENT(code = "Payment", legacyTransaction = LegacyTransaction.PAYMENT),
        LOYALTY_SALE(code = "LoyaltySale", legacyTransaction = LegacyTransaction.POINT_SALE),
        BALANCE(code = "Balance", legacyTransaction = LegacyTransaction.BALANCE),
        LOYALTY_BALANCE(code = "LoyaltyBalance", legacyTransaction = LegacyTransaction.POINT_BALANCE),
        INSTALLMENT_QUERY(code = "InstallmentQuery", legacyTransaction = LegacyTransaction.INSTALLMENT_QUERY),
        EXTRAS_QUERY(code = "ExtrasQuery", legacyTransaction = LegacyTransaction.EXTRAS_QUERY),
        EXTRAS_SALE(code = "ExtrasSale", legacyTransaction = LegacyTransaction.EXTRAS_SALE),
        QUOTA_SALE(code = "QuotaSale", legacyTransaction = LegacyTransaction.INSTALLMENT_SALE),
        EXTRAS_BALANCE(code = "ExtrasBalance", legacyTransaction = LegacyTransaction.EXTRAS_BALANCE),
        CASH(code = "Cash", legacyTransaction = LegacyTransaction.CASH),
        CHECK_IN(code = "CheckIN", legacyTransaction = LegacyTransaction.CHECKIN),
        CHECK_OUT(code = "CheckOOUT", legacyTransaction = LegacyTransaction.CHECK_OUT),
        VOID_CHECK_IN(code = "VoidCheckIN", legacyTransaction = LegacyTransaction.VOID),
        AUTH_ONLY(code = "AuthOnly", legacyTransaction = LegacyTransaction.AUTH),
        ADDITIONAL_AUTH(code = "AdditionalAuth", legacyTransaction = LegacyTransaction.TRAN_INCREMENTALAUTH),
        AUTH_CAPTURE(code = "AuthCapture", legacyTransaction = LegacyTransaction.SALE_COMP),
        FORCE_SALE(code = "ForceSale", legacyTransaction = LegacyTransaction.OFF_SALE),
        REVERSAL(code = "Reversal", legacyTransaction = LegacyTransaction.REVERSAL),
        OFFLINE_PIN_CHANGE(code = "OfflinePinChange", legacyTransaction = LegacyTransaction.OFFLINE_PIN_CHANGE),
        REVERSAL_OFFLINE_PIN_CHANGE(
            code = "ReversalOfflinePinChange",
            legacyTransaction = LegacyTransaction.REVERSAL_OFFLINE_PIN_CHANGE,
        ),
        PIN_UNBLOCK(code = "PinUnblock", legacyTransaction = LegacyTransaction.PIN_UNBLOCK),
        REVERSAL_PIN_UNBLOCK(
            code = "ReversalPinUnblock",
            legacyTransaction = LegacyTransaction.REVERSAL_PIN_UNBLOCK,
        ),
        SETTLEMENT(code = "Settlement", legacyTransaction = LegacyTransaction.SETTLEMENT),
    }

    enum class TransactionAttribute {
        PRINTS_RECEIPT,
        INCREMENTS_TRACE,
        INCREMENTS_INVOICE,
        INCLUDES_IN_SALE_TOTAL,
        INCLUDES_IN_REFUND_TOTAL,
        ALLOWS_VOID,
        NEEDS_REVERSAL,
        WRITES_RECORD,
        NEEDS_EMV_ADVICE
    }

    enum class LegacyTransaction {
        TRAN_NOTHING,
        SALE,
        IPP_SALE,
        CASH,
        CARD_VERIFY,
        AUTH,
        PREAUTH,
        UPLOAD,
        REFUND,
        REVERSAL,
        SETTLEMENT,
        VOID,
        OFFLINE_SEND,
        OFF_SALE,
        SALE_COMP,
        PAYMENT,
        CASH_ADVANCE,
        TC_SEND,
        TMS_ECHOTEST,
        TMS_PARADOWNLOAD,
        TMS_FILEDOWNLOAD,
        TMS_FILESETDOWNLOAD,
        TMS_TAMPER_UPD,
        TMS_HOUSEKEEPING,
        TMS_SETTLEUPLOAD,
        POINT_BALANCE,
        POINT_TEST,
        POINT_SALE,
        POINT_REDEM,
        POINT_PARTIAL_REDEEM,
        BALANCE,
        DISCOUNT,
        CHECKIN,
        CHECK_OUT,
        CHECKIN_QUERY,
        FLEET_SALE,
        TRAN_ECHOTEST,
        TRAN_SENDBATCH,
        TRAN_DOWNLOADBATCH,
        TRAN_ADMIN,
        TRAN_KEYREQUEST,
        TRAN_TKEYREQUEST,
        TRAN_CUSTOMTRAN,
        INSTALLMENT_QUERY,
        INSTALLMENT_SALE,
        EXTRAS_QUERY,
        EXTRAS_SALE,
        EXTRAS_BALANCE,
        QRSALE,
        CASHBACK_BALANCE,
        CASHBACK,
        TRAN_INCREMENTALAUTH,
        TRAN_FOLIOQUERY,
        TRAN_SESSIONTOKENREQUEST,
        OFFLINE_PIN_CHANGE,
        REVERSAL_OFFLINE_PIN_CHANGE,
        PIN_UNBLOCK,
        REVERSAL_PIN_UNBLOCK,
    }

    data class TransactionConfig(
        val shortLabel: String,
        @param:StringRes
        @get:StringRes
        val displayLabelRes: Int,
        @param:StringRes
        @get:StringRes
        val receiptLabelRes: Int,
        val messageType: String,
        val processingCode: String,
        val needAmount: Boolean,
        val needTax1Amount: Boolean,
        val needTax2Amount: Boolean,
        val needTipAmount: Boolean,
        val attributes: Set<TransactionAttribute>
    ) {
        fun hasAttribute(attribute: TransactionAttribute): Boolean =
            attributes.contains(attribute)
    }

    private fun config(
        shortLabel: String,
        @StringRes displayLabelRes: Int,
        @StringRes receiptLabelRes: Int,
        messageType: String,
        processingCode: String,
        needAmount: Boolean,
        needTax1Amount: Boolean,
        needTax2Amount: Boolean,
        needTipAmount: Boolean,
        attributes: Set<TransactionAttribute>
    ): TransactionConfig = TransactionConfig(
        shortLabel = shortLabel,
        displayLabelRes = displayLabelRes,
        receiptLabelRes = receiptLabelRes,
        messageType = messageType,
        processingCode = processingCode,
        needAmount = needAmount,
        needTax1Amount = needTax1Amount,
        needTax2Amount = needTax2Amount,
        needTipAmount = needTipAmount,
        attributes = attributes
    )

    private val legacyConfigurations: Map<LegacyTransaction, TransactionConfig> = mapOf(
        LegacyTransaction.TRAN_NOTHING to config(
            shortLabel = "NA",
            displayLabelRes = R.string.transaction_display_tran_nothing,
            receiptLabelRes = R.string.transaction_receipt_tran_nothing,
            messageType = "0000",
            processingCode = "000000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = emptySet()
        ),
        LegacyTransaction.SALE to config(
            shortLabel = "VE",
            displayLabelRes = R.string.transaction_display_sale,
            receiptLabelRes = R.string.transaction_receipt_sale,
            messageType = "0200",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = true,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.IPP_SALE to config(
            shortLabel = "IPV",
            displayLabelRes = R.string.transaction_display_ipp_sale,
            receiptLabelRes = R.string.transaction_receipt_ipp_sale,
            messageType = "0200",
            processingCode = "890000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = true,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.CASH to config(
            shortLabel = "EF",
            displayLabelRes = R.string.transaction_display_cash,
            receiptLabelRes = R.string.transaction_receipt_cash,
            messageType = "0200",
            processingCode = "010000",
            needAmount = true,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.CARD_VERIFY to config(
            shortLabel = "VE",
            displayLabelRes = R.string.transaction_display_card_verify,
            receiptLabelRes = R.string.transaction_receipt_card_verify,
            messageType = "0100",
            processingCode = "380000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.AUTH to config(
            shortLabel = "AU",
            displayLabelRes = R.string.transaction_display_auth,
            receiptLabelRes = R.string.transaction_receipt_auth,
            messageType = "0100",
            processingCode = "000000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.PREAUTH to config(
            shortLabel = "PA",
            displayLabelRes = R.string.transaction_display_preauth,
            receiptLabelRes = R.string.transaction_receipt_preauth,
            messageType = "0100",
            processingCode = "000000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.UPLOAD to config(
            shortLabel = "BU",
            displayLabelRes = R.string.transaction_display_upload,
            receiptLabelRes = R.string.transaction_receipt_upload,
            messageType = "0320",
            processingCode = "000000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.REFUND to config(
            shortLabel = "DE",
            displayLabelRes = R.string.transaction_display_refund,
            receiptLabelRes = R.string.transaction_receipt_refund,
            messageType = "0200",
            processingCode = "200000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_REFUND_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.REVERSAL to config(
            shortLabel = "RV",
            displayLabelRes = R.string.transaction_display_reversal,
            receiptLabelRes = R.string.transaction_receipt_reversal,
            messageType = "0400",
            processingCode = "000000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = emptySet()
        ),
        LegacyTransaction.OFFLINE_PIN_CHANGE to config(
            shortLabel = "CP",
            displayLabelRes = R.string.transaction_display_offline_pin_change,
            receiptLabelRes = R.string.transaction_receipt_offline_pin_change,
            messageType = "0200",
            processingCode = "920000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(
                TransactionAttribute.INCREMENTS_TRACE,
                TransactionAttribute.NEEDS_REVERSAL,
                TransactionAttribute.NEEDS_EMV_ADVICE,
            ),
        ),
        LegacyTransaction.REVERSAL_OFFLINE_PIN_CHANGE to config(
            shortLabel = "RCP",
            displayLabelRes = R.string.transaction_display_reversal_offline_pin_change,
            receiptLabelRes = R.string.transaction_receipt_reversal_offline_pin_change,
            messageType = "0400",
            processingCode = "920000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = emptySet(),
        ),
        LegacyTransaction.PIN_UNBLOCK to config(
            shortLabel = "PU",
            displayLabelRes = R.string.transaction_display_pin_unblock,
            receiptLabelRes = R.string.transaction_receipt_pin_unblock,
            messageType = "0200",
            processingCode = "910000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(
                TransactionAttribute.INCREMENTS_TRACE,
                TransactionAttribute.NEEDS_REVERSAL,
                TransactionAttribute.NEEDS_EMV_ADVICE,
            ),
        ),
        LegacyTransaction.REVERSAL_PIN_UNBLOCK to config(
            shortLabel = "RPU",
            displayLabelRes = R.string.transaction_display_reversal_pin_unblock,
            receiptLabelRes = R.string.transaction_receipt_reversal_pin_unblock,
            messageType = "0400",
            processingCode = "910000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = emptySet(),
        ),
        LegacyTransaction.SETTLEMENT to config(
            shortLabel = "ST",
            displayLabelRes = R.string.transaction_display_settlement,
            receiptLabelRes = R.string.transaction_receipt_settlement,
            messageType = "0500",
            processingCode = "920000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.VOID to config(
            shortLabel = "AN",
            displayLabelRes = R.string.transaction_display_void,
            receiptLabelRes = R.string.transaction_receipt_void,
            messageType = "0200",
            processingCode = "020000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.WRITES_RECORD)
        ),
        LegacyTransaction.OFFLINE_SEND to config(
            shortLabel = "AV",
            displayLabelRes = R.string.transaction_display_offline_send,
            receiptLabelRes = R.string.transaction_receipt_offline_send,
            messageType = "0220",
            processingCode = "000000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID)
        ),
        LegacyTransaction.OFF_SALE to config(
            shortLabel = "OF",
            displayLabelRes = R.string.transaction_display_off_sale,
            receiptLabelRes = R.string.transaction_receipt_off_sale,
            messageType = "0220",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = true,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID)
        ),
        LegacyTransaction.SALE_COMP to config(
            shortLabel = "SC",
            displayLabelRes = R.string.transaction_display_sale_comp,
            receiptLabelRes = R.string.transaction_receipt_sale_comp,
            messageType = "0220",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = true,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID)
        ),
        LegacyTransaction.PAYMENT to config(
            shortLabel = "PA",
            displayLabelRes = R.string.transaction_display_payment,
            receiptLabelRes = R.string.transaction_receipt_payment,
            messageType = "0200",
            processingCode = "210000",
            needAmount = true,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_REFUND_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.CASH_ADVANCE to config(
            shortLabel = "EF",
            displayLabelRes = R.string.transaction_display_cash_advance,
            receiptLabelRes = R.string.transaction_receipt_cash_advance,
            messageType = "0200",
            processingCode = "010000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.TC_SEND to config(
            shortLabel = "TU",
            displayLabelRes = R.string.transaction_display_tc_send,
            receiptLabelRes = R.string.transaction_receipt_tc_send,
            messageType = "0220",
            processingCode = "000000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TMS_ECHOTEST to config(
            shortLabel = "ET",
            displayLabelRes = R.string.transaction_display_tms_echotest,
            receiptLabelRes = R.string.transaction_receipt_tms_echotest,
            messageType = "0800",
            processingCode = "990000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TMS_PARADOWNLOAD to config(
            shortLabel = "PD",
            displayLabelRes = R.string.transaction_display_tms_paradownload,
            receiptLabelRes = R.string.transaction_receipt_tms_paradownload,
            messageType = "0800",
            processingCode = "930000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TMS_FILEDOWNLOAD to config(
            shortLabel = "FD",
            displayLabelRes = R.string.transaction_display_tms_filedownload,
            receiptLabelRes = R.string.transaction_receipt_tms_filedownload,
            messageType = "0800",
            processingCode = "940000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TMS_FILESETDOWNLOAD to config(
            shortLabel = "SD",
            displayLabelRes = R.string.transaction_display_tms_filesetdownload,
            receiptLabelRes = R.string.transaction_receipt_tms_filesetdownload,
            messageType = "0800",
            processingCode = "950000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TMS_TAMPER_UPD to config(
            shortLabel = "TU",
            displayLabelRes = R.string.transaction_display_tms_tamper_upd,
            receiptLabelRes = R.string.transaction_receipt_tms_tamper_upd,
            messageType = "0800",
            processingCode = "920000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TMS_HOUSEKEEPING to config(
            shortLabel = "HK",
            displayLabelRes = R.string.transaction_display_tms_housekeeping,
            receiptLabelRes = R.string.transaction_receipt_tms_housekeeping,
            messageType = "0800",
            processingCode = "910000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TMS_SETTLEUPLOAD to config(
            shortLabel = "SU",
            displayLabelRes = R.string.transaction_display_tms_settleupload,
            receiptLabelRes = R.string.transaction_receipt_tms_settleupload,
            messageType = "0800",
            processingCode = "960000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.POINT_BALANCE to config(
            shortLabel = "BP",
            displayLabelRes = R.string.transaction_display_point_balance,
            receiptLabelRes = R.string.transaction_receipt_point_balance,
            messageType = "0100",
            processingCode = "310000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE)
        ),
        LegacyTransaction.POINT_TEST to config(
            shortLabel = "PTS",
            displayLabelRes = R.string.transaction_display_point_test,
            receiptLabelRes = R.string.transaction_receipt_point_test,
            messageType = "0800",
            processingCode = "009500",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE)
        ),
        LegacyTransaction.POINT_SALE to config(
            shortLabel = "VP",
            displayLabelRes = R.string.transaction_display_point_sale,
            receiptLabelRes = R.string.transaction_receipt_point_sale,
            messageType = "0200",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = true,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.POINT_REDEM to config(
            shortLabel = "PR",
            displayLabelRes = R.string.transaction_display_point_redem,
            receiptLabelRes = R.string.transaction_receipt_point_redem,
            messageType = "0200",
            processingCode = "009500",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.POINT_PARTIAL_REDEEM to config(
            shortLabel = "PPR",
            displayLabelRes = R.string.transaction_display_point_partial_redeem,
            receiptLabelRes = R.string.transaction_receipt_point_partial_redeem,
            messageType = "0200",
            processingCode = "009500",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.BALANCE to config(
            shortLabel = "B",
            displayLabelRes = R.string.transaction_display_balance,
            receiptLabelRes = R.string.transaction_receipt_balance,
            messageType = "0100",
            processingCode = "310000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE)
        ),
        LegacyTransaction.DISCOUNT to config(
            shortLabel = "DS",
            displayLabelRes = R.string.transaction_display_discount,
            receiptLabelRes = R.string.transaction_receipt_discount,
            messageType = "0200",
            processingCode = "000000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.CHECKIN to config(
            shortLabel = "CI",
            displayLabelRes = R.string.transaction_display_checkin,
            receiptLabelRes = R.string.transaction_receipt_checkin,
            messageType = "0100",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.CHECK_OUT to config(
            shortLabel = "CO",
            displayLabelRes = R.string.transaction_display_check_out,
            receiptLabelRes = R.string.transaction_receipt_check_out,
            messageType = "0200",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = true,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID)
        ),
        LegacyTransaction.CHECKIN_QUERY to config(
            shortLabel = "BCH",
            displayLabelRes = R.string.transaction_display_checkin_query,
            receiptLabelRes = R.string.transaction_receipt_checkin_query,
            messageType = "0100",
            processingCode = "320000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.FLEET_SALE to config(
            shortLabel = "GAS",
            displayLabelRes = R.string.transaction_display_fleet_sale,
            receiptLabelRes = R.string.transaction_receipt_fleet_sale,
            messageType = "0200",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.TRAN_ECHOTEST to config(
            shortLabel = "ET",
            displayLabelRes = R.string.transaction_display_tran_echotest,
            receiptLabelRes = R.string.transaction_receipt_tran_echotest,
            messageType = "0800",
            processingCode = "990000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TRAN_SENDBATCH to config(
            shortLabel = "BUL",
            displayLabelRes = R.string.transaction_display_tran_sendbatch,
            receiptLabelRes = R.string.transaction_receipt_tran_sendbatch,
            messageType = "0800",
            processingCode = "610000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TRAN_DOWNLOADBATCH to config(
            shortLabel = "BDL",
            displayLabelRes = R.string.transaction_display_tran_downloadbatch,
            receiptLabelRes = R.string.transaction_receipt_tran_downloadbatch,
            messageType = "0800",
            processingCode = "620000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TRAN_ADMIN to config(
            shortLabel = "ADM",
            displayLabelRes = R.string.transaction_display_tran_admin,
            receiptLabelRes = R.string.transaction_receipt_tran_admin,
            messageType = "0800",
            processingCode = "630000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TRAN_KEYREQUEST to config(
            shortLabel = "SL",
            displayLabelRes = R.string.transaction_display_tran_keyrequest,
            receiptLabelRes = R.string.transaction_receipt_tran_keyrequest,
            messageType = "0800",
            processingCode = "920000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TRAN_TKEYREQUEST to config(
            shortLabel = "LTP",
            displayLabelRes = R.string.transaction_display_tran_tkeyrequest,
            receiptLabelRes = R.string.transaction_receipt_tran_tkeyrequest,
            messageType = "0800",
            processingCode = "950000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TRAN_CUSTOMTRAN to config(
            shortLabel = "CT",
            displayLabelRes = R.string.transaction_display_tran_customtran,
            receiptLabelRes = R.string.transaction_receipt_tran_customtran,
            messageType = "0200",
            processingCode = "999999",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID)
        ),
        LegacyTransaction.INSTALLMENT_QUERY to config(
            shortLabel = "BC",
            displayLabelRes = R.string.transaction_display_installment_query,
            receiptLabelRes = R.string.transaction_receipt_installment_query,
            messageType = "0100",
            processingCode = "310000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.INSTALLMENT_SALE to config(
            shortLabel = "VC",
            displayLabelRes = R.string.transaction_display_installment_sale,
            receiptLabelRes = R.string.transaction_receipt_installment_sale,
            messageType = "0200",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.EXTRAS_QUERY to config(
            shortLabel = "CEX",
            displayLabelRes = R.string.transaction_display_extras_query,
            receiptLabelRes = R.string.transaction_receipt_extras_query,
            messageType = "0100",
            processingCode = "310000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE)
        ),
        LegacyTransaction.EXTRAS_SALE to config(
            shortLabel = "VEX",
            displayLabelRes = R.string.transaction_display_extras_sale,
            receiptLabelRes = R.string.transaction_receipt_extras_sale,
            messageType = "0200",
            processingCode = "010000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.EXTRAS_BALANCE to config(
            shortLabel = "SE",
            displayLabelRes = R.string.transaction_display_extras_balance,
            receiptLabelRes = R.string.transaction_receipt_extras_balance,
            messageType = "0100",
            processingCode = "300000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE)
        ),
        LegacyTransaction.QRSALE to config(
            shortLabel = "QR",
            displayLabelRes = R.string.transaction_display_qrsale,
            receiptLabelRes = R.string.transaction_receipt_qrsale,
            messageType = "0200",
            processingCode = "000000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = true,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.CASHBACK_BALANCE to config(
            shortLabel = "BCB",
            displayLabelRes = R.string.transaction_display_cashback_balance,
            receiptLabelRes = R.string.transaction_receipt_cashback_balance,
            messageType = "0100",
            processingCode = "860000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE)
        ),
        LegacyTransaction.CASHBACK to config(
            shortLabel = "CB",
            displayLabelRes = R.string.transaction_display_cashback,
            receiptLabelRes = R.string.transaction_receipt_cashback,
            messageType = "0200",
            processingCode = "850000",
            needAmount = true,
            needTax1Amount = true,
            needTax2Amount = true,
            needTipAmount = true,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.INCLUDES_IN_SALE_TOTAL, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.TRAN_INCREMENTALAUTH to config(
            shortLabel = "AI",
            displayLabelRes = R.string.transaction_display_tran_incrementalauth,
            receiptLabelRes = R.string.transaction_receipt_tran_incrementalauth,
            messageType = "0100",
            processingCode = "250000",
            needAmount = true,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.PRINTS_RECEIPT, TransactionAttribute.INCREMENTS_TRACE, TransactionAttribute.INCREMENTS_INVOICE, TransactionAttribute.NEEDS_REVERSAL, TransactionAttribute.WRITES_RECORD, TransactionAttribute.ALLOWS_VOID, TransactionAttribute.NEEDS_EMV_ADVICE)
        ),
        LegacyTransaction.TRAN_FOLIOQUERY to config(
            shortLabel = "CF",
            displayLabelRes = R.string.transaction_display_tran_folioquery,
            receiptLabelRes = R.string.transaction_receipt_tran_folioquery,
            messageType = "0100",
            processingCode = "280000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        ),
        LegacyTransaction.TRAN_SESSIONTOKENREQUEST to config(
            shortLabel = "SK",
            displayLabelRes = R.string.transaction_display_tran_sessiontokenrequest,
            receiptLabelRes = R.string.transaction_receipt_tran_sessiontokenrequest,
            messageType = "0800",
            processingCode = "940000",
            needAmount = false,
            needTax1Amount = false,
            needTax2Amount = false,
            needTipAmount = false,
            attributes = setOf(TransactionAttribute.INCREMENTS_TRACE)
        )
    )

    private val transactionCodeMapping: Map<String, LegacyTransaction> =
        TransactionCode.entries.associate { it.code to it.legacyTransaction }

    fun configFor(transactionType: String): TransactionConfig? =
        transactionCodeMapping[transactionType]?.let(legacyConfigurations::get)

    fun configFor(transaction: LegacyTransaction): TransactionConfig? =
        legacyConfigurations[transaction]

    fun amountPromptConfigFor(
        transactionType: String,
        terminal: TMS_Terminal?,
        acquirers: List<TMS_Acquirer> = emptyList(),
    ): AmountPromptConfig? {
        val legacyTransaction = transactionCodeMapping[transactionType] ?: return null
        return amountPromptConfigFor(legacyTransaction, terminal, acquirers)
    }

    fun amountPromptConfigFor(
        transaction: LegacyTransaction,
        terminal: TMS_Terminal?,
        acquirers: List<TMS_Acquirer> = emptyList(),
    ): AmountPromptConfig? {
        val config = configFor(transaction) ?: return null
        return config.toAmountPromptConfig(transaction, terminal, acquirers)
    }

    private fun TransactionConfig.toAmountPromptConfig(
        transaction: LegacyTransaction,
        terminal: TMS_Terminal?,
        acquirers: List<TMS_Acquirer>,
    ): AmountPromptConfig {
        val refundUsesTax = terminal?.RefundUseTax ?: false
        val askTax1 = when (transaction) {
            LegacyTransaction.REFUND -> needTax1Amount && refundUsesTax && (terminal?.ApplyTax ?: false)
            else -> needTax1Amount && (terminal?.ApplyTax ?: false)
        }
        val askTax2 = when (transaction) {
            LegacyTransaction.REFUND -> needTax2Amount && refundUsesTax && (terminal?.ApplyTax2 ?: false)
            else -> needTax2Amount && (terminal?.ApplyTax2 ?: false)
        }
        val manualTipEnabled = if (terminal != null && terminal.tipProcessingMode.isNotBlank()) {
            terminal.TIPProcs == 1L
        } else {
            acquirers.any { it.TIPProcs == 1L }
        }
        val currencies = CurrencyTable.build(acquirers)
        val currencySymbol = if (currencies.size == 1) currencies.first().symbol else null

        return AmountPromptConfig(
            askAmount = needAmount,
            askTax1 = askTax1,
            askTax2 = askTax2,
            askTip = needTipAmount && manualTipEnabled,
            tax1ZeroAmountAllowed = terminal?.Tax1Mandatory == false,
            tax1DiscountPercentage = if (askTax1) terminal?.TaxDiscount ?: 0.0 else 0.0,
            currencySymbol = currencySymbol,
        )
    }
}
