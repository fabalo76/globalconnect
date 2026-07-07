package com.uic.uicpaymentapp.cardreader.nexgo

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
) {
    fun clear() {
        status = PinStatus.NA
        pinBlock = ""
        ksn = ""
    }
}
