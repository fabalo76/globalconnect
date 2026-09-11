package one.globalconnect.paymentapp.transaction

import com.nexgo.oaf.apiv3.SdkResult

/**
 * Determines whether an online-approved transaction was subsequently declined by the EMV card.
 *
 * @param hostApproved whether the issuer approved the online authorization.
 * @param kernelApproved whether the terminal accepted the kernel's final outcome as successful.
 * @param kernelResultCode final Nexgo EMV result code, when available.
 * @param cryptogramInformationData tag 9F27 from the final EMV result, when available.
 * @return `true` only when the issuer approved but the card returned a decline outcome.
 */
internal fun isCardDeclinedAfterOnlineApproval(
    hostApproved: Boolean,
    kernelApproved: Boolean,
    kernelResultCode: Int?,
    cryptogramInformationData: String?,
): Boolean {
    if (!hostApproved || kernelApproved) return false

    val normalizedCid = cryptogramInformationData
        ?.filterNot(Char::isWhitespace)
        ?.uppercase()
    val finalCryptogramIsAac = normalizedCid?.take(2) == "00"
    return kernelResultCode == SdkResult.Emv_Declined ||
        kernelResultCode == SdkResult.Emv_Offline_Declined ||
        finalCryptogramIsAac
}
