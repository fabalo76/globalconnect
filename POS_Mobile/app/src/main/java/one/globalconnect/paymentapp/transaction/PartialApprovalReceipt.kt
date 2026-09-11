package one.globalconnect.paymentapp.transaction

import java.math.BigDecimal

internal data class PartialApprovalReceipt(
    val originalAmount: BigDecimal?,
    val approvedAmount: BigDecimal,
)

internal fun Transaction.partialApprovalReceipt(): PartialApprovalReceipt? {
    if (!PartialApprovalContract.isSupported(type) || returnStatus == ReturnStatus.Voided) return null
    val approved = totalAmount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
    val original = partialApprovalOriginalAmount.toBigDecimalOrNull()?.takeIf { it > approved }
    // Older records retained ARC=10, but did not save the requested amount. Never invent it.
    if (original == null && ARC.trim() != PartialApprovalContract.RESPONSE_CODE) return null
    return PartialApprovalReceipt(original, approved)
}
