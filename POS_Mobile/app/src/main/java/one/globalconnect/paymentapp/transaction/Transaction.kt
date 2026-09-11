package one.globalconnect.paymentapp.transaction

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry.TransactionCode
import java.util.Locale

enum class TransactionType {
    SALE,
    REFUND,
    CASH,
    PAYMENT,
    LOYALTY_SALE,
    LOYALTY_BALANCE,
    EXTRAS_SALE,
    QUOTA_SALE,
    EXTRAS_BALANCE,
    CHECKIN,
    CHECKOUT,
    VOIDCHECKIN,
    VOID,
    TIPADJUST,
    PARTIALVOID,
    TOKENSALE,
    MANUALSALE,
    MANUALREFUND,

    // Restaurant
    AUTHONLY,
    ADDITIONALAUTH,
    AUTHCAPTURE,
    REVERSAL,
    FORCESALE,
    TOKENAUTH,
    MANUALAUTH,
    MANUALFORCESALE,
    MOTO,

    SETTLEMENT,
    OFFLINE_PIN_CHANGE,
    REVERSAL_OFFLINE_PIN_CHANGE,
    PIN_UNBLOCK,
    REVERSAL_PIN_UNBLOCK,

    // Error case
    ERROR
}

enum class CheckStatus {
    NeedTip,
    Open,
    Closed,

    // Error case
    Error
}

enum class ReturnStatus {
    None,
    Voided,
    Refunded,
}


fun String?.CVMStringtoCvmType(): CVMType =
    when (this?.trim()?.lowercase(Locale.ENGLISH)) {
        null -> CVMType.None
        "" -> CVMType.None
        "signature", "offline pin + signature" -> CVMType.Signature
        "pin verified", "online pin", "offline pin" -> CVMType.PinVerified
        "pin failed" -> CVMType.PinFailed
        "no cvm" -> CVMType.None
        "cvm not performed" -> CVMType.NotPerformed
        else -> CVMType.Error
    }

fun String?.toCheckStatus(): CheckStatus =
    when (this) {
        "NeedTip" -> CheckStatus.NeedTip
        "Open" -> CheckStatus.Open
        "Closed" -> CheckStatus.Closed
        else -> CheckStatus.Error
    }

fun String?.toReturnStatus(): ReturnStatus =
    when (this) {
        "Voided" -> ReturnStatus.Voided
        "Refunded" -> ReturnStatus.Refunded
        else -> ReturnStatus.None
    }

@Composable
fun TransactionType.toTransactionName(): String =
    when (this) {
        TransactionType.SALE -> stringResource(id = R.string.trans_sale)

        TransactionType.PAYMENT -> stringResource(id = R.string.trans_payment)
        TransactionType.LOYALTY_SALE -> stringResource(id = R.string.trans_loyalty_sale)
        TransactionType.LOYALTY_BALANCE -> stringResource(id = R.string.trans_loyalty_balance)
        TransactionType.EXTRAS_SALE -> stringResource(id = R.string.trans_extras_sale)
        TransactionType.QUOTA_SALE -> stringResource(id = R.string.trans_quota_sale)
        TransactionType.EXTRAS_BALANCE -> stringResource(id = R.string.trans_extras_balance)
        TransactionType.CASH -> stringResource(id = R.string.trans_cash)
        TransactionType.CHECKIN -> stringResource(id = R.string.trans_check_in)
        TransactionType.CHECKOUT -> stringResource(id = R.string.trans_check_out)
        TransactionType.VOIDCHECKIN -> stringResource(id = R.string.trans_void_check_in)


        TransactionType.REFUND -> stringResource(id = R.string.trans_refund)
        TransactionType.VOID -> stringResource(id = R.string.trans_void)
        TransactionType.TIPADJUST -> stringResource(id = R.string.trans_tip)
        TransactionType.PARTIALVOID -> stringResource(id = R.string.trans_partial_void)
        TransactionType.TOKENSALE -> stringResource(id = R.string.trans_token_sale)
        TransactionType.MANUALSALE -> stringResource(id = R.string.trans_manual_sale)
        TransactionType.MANUALREFUND -> stringResource(id = R.string.trans_manual_refund)

            // Restaurant
        TransactionType.AUTHONLY -> stringResource(id = R.string.trans_auth)
        TransactionType.ADDITIONALAUTH -> stringResource(id = R.string.trans_auth_add)
        TransactionType.AUTHCAPTURE -> stringResource(id = R.string.trans_auth_capture)
        TransactionType.REVERSAL -> stringResource(id = R.string.trans_reversal)
        TransactionType.FORCESALE -> stringResource(id = R.string.trans_force_sale)
        TransactionType.TOKENAUTH -> stringResource(id = R.string.trans_token_auth)
        TransactionType.MANUALAUTH -> stringResource(id = R.string.trans_manual_auth)
        TransactionType.MANUALFORCESALE -> stringResource(id = R.string.trans_manual_force_sale)
        TransactionType.MOTO -> stringResource(id = R.string.trans_moto)

        TransactionType.SETTLEMENT -> stringResource(id = R.string.trans_settlement)
        TransactionType.OFFLINE_PIN_CHANGE -> stringResource(id = R.string.trans_offline_pin_change)
        TransactionType.REVERSAL_OFFLINE_PIN_CHANGE -> stringResource(id = R.string.trans_reversal)
        TransactionType.PIN_UNBLOCK -> stringResource(id = R.string.trans_pin_unblock)
        TransactionType.REVERSAL_PIN_UNBLOCK -> stringResource(id = R.string.trans_reversal)
            // Error case
        TransactionType.ERROR -> stringResource(id = R.string.trans_error)

    }
private val transactionTypeToCode: Map<TransactionType, TransactionCode> = mapOf(
    TransactionType.SALE to TransactionCode.SALE,
    TransactionType.REFUND to TransactionCode.REFUND,
    TransactionType.VOID to TransactionCode.VOID,
    TransactionType.TIPADJUST to TransactionCode.TIP_ADJUST,
    TransactionType.PAYMENT to TransactionCode.PAYMENT,
    TransactionType.LOYALTY_SALE to TransactionCode.LOYALTY_SALE,
    TransactionType.LOYALTY_BALANCE to TransactionCode.LOYALTY_BALANCE,
    TransactionType.EXTRAS_SALE to TransactionCode.EXTRAS_SALE,
    TransactionType.QUOTA_SALE to TransactionCode.QUOTA_SALE,
    TransactionType.EXTRAS_BALANCE to TransactionCode.EXTRAS_BALANCE,
    TransactionType.CASH to TransactionCode.CASH,
    TransactionType.CHECKIN to TransactionCode.CHECK_IN,
    TransactionType.CHECKOUT to TransactionCode.CHECK_OUT,
    TransactionType.VOIDCHECKIN to TransactionCode.VOID_CHECK_IN,
    TransactionType.AUTHONLY to TransactionCode.AUTH_ONLY,
    TransactionType.ADDITIONALAUTH to TransactionCode.ADDITIONAL_AUTH,
    TransactionType.AUTHCAPTURE to TransactionCode.AUTH_CAPTURE,
    TransactionType.FORCESALE to TransactionCode.FORCE_SALE,
    TransactionType.REVERSAL to TransactionCode.REVERSAL,
    TransactionType.SETTLEMENT to TransactionCode.SETTLEMENT,
    TransactionType.OFFLINE_PIN_CHANGE to TransactionCode.OFFLINE_PIN_CHANGE,
    TransactionType.REVERSAL_OFFLINE_PIN_CHANGE to TransactionCode.REVERSAL_OFFLINE_PIN_CHANGE,
    TransactionType.PIN_UNBLOCK to TransactionCode.PIN_UNBLOCK,
    TransactionType.REVERSAL_PIN_UNBLOCK to TransactionCode.REVERSAL_PIN_UNBLOCK,
)

private val transactionCodeToType: Map<String, TransactionType> =
    transactionTypeToCode.entries.associate { (type, code) -> code.code to type }

fun TransactionType.toTransactionString(): String =
    transactionTypeToCode[this]?.code
        ?: resolveString(R.string.trans_error, "Error")

fun String.transactionStringToTransactionType(): TransactionType =
    transactionCodeToType[this] ?: TransactionType.ERROR

private data class TransactionLabel(@param:StringRes val resId: Int, val fallback: String)

private fun TransactionType.label(): TransactionLabel =
    when (this) {
        TransactionType.SALE -> TransactionLabel(R.string.trans_sale, "Sale")
        TransactionType.REFUND -> TransactionLabel(R.string.trans_refund, "Refund")
        TransactionType.VOID -> TransactionLabel(R.string.trans_void, "Void")
        TransactionType.TIPADJUST -> TransactionLabel(R.string.trans_tip, "Tip Adjust")

        TransactionType.PAYMENT -> TransactionLabel(R.string.trans_payment, "Payment")
        TransactionType.LOYALTY_SALE -> TransactionLabel(R.string.trans_loyalty_sale, "Loyalty Sale")
        TransactionType.LOYALTY_BALANCE -> TransactionLabel(R.string.trans_loyalty_balance, "Loyalty Balance")
        TransactionType.EXTRAS_SALE -> TransactionLabel(R.string.trans_extras_sale, "Extras Sale")
        TransactionType.QUOTA_SALE -> TransactionLabel(R.string.trans_quota_sale, "Quota Sale")
        TransactionType.EXTRAS_BALANCE -> TransactionLabel(R.string.trans_extras_balance, "Extras Balance")
        TransactionType.CASH -> TransactionLabel(R.string.trans_cash, "Cash")
        TransactionType.CHECKIN -> TransactionLabel(R.string.trans_check_in, "Check In")
        TransactionType.CHECKOUT -> TransactionLabel(R.string.trans_check_out, "Check Out")
        TransactionType.VOIDCHECKIN -> TransactionLabel(R.string.trans_void_check_in, "Void Check In")

        TransactionType.PARTIALVOID -> TransactionLabel(R.string.trans_partial_void, "Partial Void")
        TransactionType.TOKENSALE -> TransactionLabel(R.string.trans_token_sale, "Token Sale")

        // Restaurant
        TransactionType.AUTHONLY -> TransactionLabel(R.string.trans_auth, "Auth Only")
        TransactionType.ADDITIONALAUTH -> TransactionLabel(R.string.trans_auth_add, "Auth Add")
        TransactionType.AUTHCAPTURE -> TransactionLabel(R.string.trans_auth_capture, "Auth Capture")
        TransactionType.FORCESALE -> TransactionLabel(R.string.trans_force_sale, "Force Sale")
        TransactionType.REVERSAL -> TransactionLabel(R.string.trans_reversal, "Reversal")

        TransactionType.SETTLEMENT -> TransactionLabel(R.string.trans_settlement, "Settlement")
        TransactionType.OFFLINE_PIN_CHANGE -> TransactionLabel(
            R.string.trans_offline_pin_change,
            "Change Offline PIN",
        )
        TransactionType.REVERSAL_OFFLINE_PIN_CHANGE -> TransactionLabel(
            R.string.trans_reversal,
            "Offline PIN Change Reversal",
        )
        TransactionType.PIN_UNBLOCK -> TransactionLabel(
            R.string.trans_pin_unblock,
            "PIN Unblock",
        )
        TransactionType.REVERSAL_PIN_UNBLOCK -> TransactionLabel(
            R.string.trans_reversal,
            "PIN Unblock Reversal",
        )
        else -> TransactionLabel(R.string.trans_error, "Error")
    }

private fun resolveString(@StringRes resId: Int, fallback: String): String {
    val resources = GlobalConnectPaymentApplication.instanceOrNull?.resources
    return resources?.getString(resId) ?: fallback
}

fun TransactionType.toStringForUsers(): String {
    val (resId, fallback) = label()
    return resolveString(resId, fallback)
}

@Entity(tableName = "reset_state")
data class ResetState(
    @PrimaryKey var id: Int = 1,
    var should_reset: Boolean = false
)

@Entity(tableName = "Transaction")
data class Transaction(
    val transactionId: String = "",
    val stan: String = "",
    var totalAmount: String = "",
    var baseAmount: String = "",
    var tax1Amount: String = "",
    var tax1DiscountAmount: String = "",
    var tax2Amount: String = "",
    var tipAmount: String = "",
    val cardNumber: String = "",
    val masked_cardNumber: String = "",
    val hashed_cardNumber: String = "",
    val accType: String = "",
    val acquirerId: String = "",
    val issuerId: String = "",
    val cardRangeId: String = "",
    val cardRangeName: String = "",
    val localDateTime: String = "",
    val type: TransactionType = TransactionType.ERROR,
    val authNtwkName: String = "",
    val cardType: String = "",
    val cardEntryMethod: String = "",
    val invoiceId: String = "",
    val AID: String = "",
    val TVR: String = "",
    val IAD: String = "",
    val TSI: String = "",
    val AC: String = "",
    val ARC: String = "",
    val ATC: String = "",
    val authCode: String = "",
    val authorizationId: String = "",
    val emvCryptoInformation: String = "",
    val emvCryptogram: String = "",
    val applicationLabel: String = "",
    val cardExpirationDate: String = "",
    val retrievalReferenceNumber: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''")
    val partialApprovalOriginalAmount: String = "",
    val externalReferenceNumber: String = "",
    val applicationName: String = "",
    val folioNumber: String = "",
    val originalTransactionId: String = "",
    var checkStatus: CheckStatus = CheckStatus.Closed,
    var returnStatus: ReturnStatus = ReturnStatus.None,
    val CVM: CVMType = CVMType.None,
    val CVMText: String = "",
    val cardholderName: String = "",
    val tipProcessingInformation: String = "",
    val signatureRequired: Boolean = false,
    val signatureCaptured: Boolean = false,
    // Restaurant-specific fields
    var errorOnCapture: Boolean = false,
    var errMessage: String = "",
    val subTotal: String = "",
    val token: String = "",
    val paymentPlan: String = "",
    val alternateHostResponse: String = "",
    val additionalHostPrintData: String = "",
    val paymentPlanQueryResponse: String = "",

    var orderNo: Int = 1,
    @PrimaryKey(autoGenerate = true) var id:Int = 0
) {
    @Ignore
    var formattedTime: String = ""

    @Ignore
    var formattedVerboseDateTime: String = ""

    /** Host-returned loyalty balance. Balance inquiries are transient and never enter the batch. */
    @Ignore
    var loyaltyBalancePoints: String = ""
}

enum class CVMType {
    None,
    Signature,
    PinVerified,
    PinFailed,
    NotPerformed,
    Error
}

fun CVMType.toPrinterString(): String =
    when (this) {
        CVMType.None -> "No CVM"
        CVMType.PinVerified -> "PIN Verified"
        CVMType.PinFailed -> "PIN Failed"
        CVMType.NotPerformed -> "CVM not performed"
        CVMType.Signature -> "Signature"
        CVMType.Error -> "Error"
    }


fun String.CapitalizeFirstLowerRest(): String {
    if (this.isEmpty()) return this
    return this.substring(0, 1).uppercase(Locale.ENGLISH) + this.substring(1).lowercase(Locale.ENGLISH)
}


fun obfuscatePAN(originalPAN: String?): String? {
    if (originalPAN == null || originalPAN.length < 4) {
        return null
    }
    return "*".repeat(12).plus(originalPAN.takeLast(4))
}
