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
) {
    fun clear() {
        status = PinStatus.NA
        pinBlock = ""
        ksn = ""
        onlinePinRequested = false
        errorMessage = ""
    }
}
