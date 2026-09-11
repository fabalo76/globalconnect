package one.globalconnect.paymentapp.transaction

/** Optional reference values shown together on transaction receipts. */
internal data class ReceiptReferenceValues(
    val folioNumber: String?,
    val externalReferenceNumber: String?,
)

internal fun resolveReceiptReferenceValues(
    folioNumber: String,
    externalReferenceNumber: String,
): ReceiptReferenceValues = ReceiptReferenceValues(
    folioNumber = folioNumber.trim().takeIf(String::isNotEmpty),
    externalReferenceNumber = externalReferenceNumber.trim().takeIf(String::isNotEmpty),
)
