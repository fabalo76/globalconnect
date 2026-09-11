package one.globalconnect.paymentapp.transaction

internal const val CVM_TEXT_ONLINE_PIN = "Online PIN"
internal const val CVM_TEXT_OFFLINE_PIN = "Offline PIN"
internal const val CVM_TEXT_OFFLINE_PIN_AND_SIGNATURE = "Offline PIN + Signature"
internal const val CVM_TEXT_SIGNATURE = "Signature"
internal const val CVM_TEXT_NO_CVM = "No CVM"
internal const val CVM_TEXT_CDCVM = "CDCVM Verified"
internal const val CVM_TEXT_PIN_FAILED = "PIN Failed"
internal const val CVM_TEXT_NOT_PERFORMED = "CVM not performed"

internal enum class EmvCvmMethod {
    OFFLINE_PIN,
    ONLINE_PIN,
    OFFLINE_PIN_AND_SIGNATURE,
    SIGNATURE,
    NO_CVM,
    UNKNOWN,
}

internal data class EmvCvmResult(
    val method: EmvCvmMethod,
    val resultCode: Int,
) {
    val successful: Boolean
        get() = resultCode == 0x02
}

/** Decodes EMV tag 9F34 (CVM Results). */
internal fun parseEmvCvmResult(value: String?): EmvCvmResult? {
    val normalized = value
        ?.filterNot(Char::isWhitespace)
        ?.takeIf { it.length == 6 && it.all { character -> character.digitToIntOrNull(16) != null } }
        ?: return null

    val cvmCode = normalized.substring(0, 2).toInt(16) and 0x3F
    val resultCode = normalized.substring(4, 6).toInt(16)
    val method = when (cvmCode) {
        0x01, 0x04 -> EmvCvmMethod.OFFLINE_PIN
        0x02 -> EmvCvmMethod.ONLINE_PIN
        0x03, 0x05 -> EmvCvmMethod.OFFLINE_PIN_AND_SIGNATURE
        0x1E -> EmvCvmMethod.SIGNATURE
        0x1F -> EmvCvmMethod.NO_CVM
        else -> EmvCvmMethod.UNKNOWN
    }
    return EmvCvmResult(method = method, resultCode = resultCode)
}

internal fun resolveEmvCvmText(
    cvmResults: String?,
    onlinePinRequested: Boolean,
): String {
    val result = parseEmvCvmResult(cvmResults)
    if (result == null) {
        return if (onlinePinRequested) CVM_TEXT_ONLINE_PIN else CVM_TEXT_NOT_PERFORMED
    }
    // For online PIN, 9F34 commonly reports "unknown" (00): issuer approval verifies the PIN.
    if (result.method == EmvCvmMethod.ONLINE_PIN && result.resultCode != 0x01) {
        return CVM_TEXT_ONLINE_PIN
    }
    // A paper signature cannot be verified by the terminal, so EMV reports result "unknown" (00).
    // Preserve the selected signature CVM unless the kernel explicitly reports it as failed (01).
    if (result.method == EmvCvmMethod.SIGNATURE && result.resultCode != 0x01) {
        return CVM_TEXT_SIGNATURE
    }
    if (!result.successful) {
        return when (result.method) {
            EmvCvmMethod.OFFLINE_PIN,
            EmvCvmMethod.ONLINE_PIN,
            EmvCvmMethod.OFFLINE_PIN_AND_SIGNATURE,
            -> CVM_TEXT_PIN_FAILED

            else -> CVM_TEXT_NOT_PERFORMED
        }
    }
    return when (result.method) {
        EmvCvmMethod.OFFLINE_PIN -> CVM_TEXT_OFFLINE_PIN
        EmvCvmMethod.ONLINE_PIN -> CVM_TEXT_ONLINE_PIN
        EmvCvmMethod.OFFLINE_PIN_AND_SIGNATURE -> CVM_TEXT_OFFLINE_PIN_AND_SIGNATURE
        EmvCvmMethod.SIGNATURE -> CVM_TEXT_SIGNATURE
        EmvCvmMethod.NO_CVM -> CVM_TEXT_NO_CVM
        EmvCvmMethod.UNKNOWN -> CVM_TEXT_NOT_PERFORMED
    }
}

/** Resolves the SDK's contactless CVM outcome, which is more specific than tag 9F34 for CDCVM. */
internal fun resolveKernelCvmText(kernelResult: String?): String? = when (
    kernelResult?.trim()?.uppercase()
) {
    "EMV_CVMR_SIGNATURE" -> CVM_TEXT_SIGNATURE

    "EMV_CVMR_CDCVM",
    "EMV_CVMR_CONFVERIFIED",
    -> CVM_TEXT_CDCVM

    else -> null
}

internal enum class ReceiptPinVerification {
    NONE,
    ONLINE,
    OFFLINE,
}

internal data class ReceiptCvmPresentation(
    val pinVerification: ReceiptPinVerification,
    val noSignatureRequiredEmv: Boolean,
    val noSignatureRequiredCvm: Boolean,
    val noSignatureRequiredCdcvm: Boolean,
    val signatureRequired: Boolean,
)

/** Resolves the mutually exclusive signature/no-signature receipt treatment. */
internal fun resolveReceiptCvmPresentation(
    transaction: Transaction,
    configuredSignatureRequired: Boolean,
): ReceiptCvmPresentation {
    val isContactlessEmv = transaction.cardEntryMethod.equals("EMV_CONTACTLESS", ignoreCase = true)
    val isEmv = transaction.cardEntryMethod.equals("EMV", ignoreCase = true) || isContactlessEmv
    val cvmText = transaction.CVMText.trim()
    val onlinePin = cvmText.equals(CVM_TEXT_ONLINE_PIN, ignoreCase = true)
    val offlinePin = cvmText.equals(CVM_TEXT_OFFLINE_PIN, ignoreCase = true)
    val noCvm = cvmText.equals(CVM_TEXT_NO_CVM, ignoreCase = true)
    val cdcvm = cvmText.equals(CVM_TEXT_CDCVM, ignoreCase = true)
    val cvmNotPerformed = cvmText.equals(CVM_TEXT_NOT_PERFORMED, ignoreCase = true)
    val pinAndSignature = cvmText.equals(CVM_TEXT_OFFLINE_PIN_AND_SIGNATURE, ignoreCase = true)
    val chipNeedsNoSignature = isEmv && (onlinePin || offlinePin || noCvm || cdcvm || cvmNotPerformed)
    val chipNeedsSignature = cvmText.equals(CVM_TEXT_SIGNATURE, ignoreCase = true) || pinAndSignature

    return ReceiptCvmPresentation(
        pinVerification = when {
            offlinePin || pinAndSignature -> ReceiptPinVerification.OFFLINE
            onlinePin -> ReceiptPinVerification.ONLINE
            else -> ReceiptPinVerification.NONE
        },
        noSignatureRequiredEmv = chipNeedsNoSignature,
        noSignatureRequiredCvm = isContactlessEmv && noCvm,
        noSignatureRequiredCdcvm = isContactlessEmv && cdcvm,
        signatureRequired = !chipNeedsNoSignature &&
            (configuredSignatureRequired || transaction.signatureRequired || chipNeedsSignature),
    )
}
