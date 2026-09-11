package one.globalconnect.paymentapp.transaction

/** EMV TVR byte 2 bit 7 indicates that the selected application is expired. */
internal fun hasExpiredApplicationTvr(tvr: String?): Boolean {
    val normalized = tvr
        ?.filterNot(Char::isWhitespace)
        ?.takeIf { it.length == 10 && it.all { character -> character.digitToIntOrNull(16) != null } }
        ?: return false
    val byte2 = normalized.substring(2, 4).toInt(16)
    return byte2 and 0x40 != 0
}
