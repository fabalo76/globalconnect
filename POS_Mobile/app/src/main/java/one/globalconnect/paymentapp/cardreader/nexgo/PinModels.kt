package one.globalconnect.paymentapp.cardreader.nexgo

/**
 * Holds the results of a PIN entry operation.
 */
internal enum class PinStatus {
    NA,
    ENTERED,
    DUMMY,
    BYPASSED,
    CANCELED,
    ERROR,
}

internal data class PinData(
    var status: PinStatus = PinStatus.NA,
    var pinBlock: String = "",
    var ksn: String = "",
    var onlinePinRequested: Boolean = false,
    var errorMessage: String = "",
    var scheme: OnlinePinScheme? = null,
    var keyIndex: Int = 0,
    var compatibleAcquirerIds: Set<String> = emptySet(),
) {
    fun clear() {
        status = PinStatus.NA
        pinBlock = ""
        ksn = ""
        onlinePinRequested = false
        errorMessage = ""
        scheme = null
        keyIndex = 0
        compatibleAcquirerIds = emptySet()
    }
}
