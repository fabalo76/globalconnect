package one.globalconnect.paymentapp.transaction

import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale

object LoyaltyContract {
    fun isLoyalty(type: TransactionType): Boolean =
        type == TransactionType.LOYALTY_SALE || type == TransactionType.LOYALTY_BALANCE

    /** DE4 in a balance response is an integer point count, not a monetary amount. */
    fun parseBalancePoints(field4: String?): String? {
        val digits = field4?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!digits.all(Char::isDigit)) return null
        return digits.toBigIntegerOrNull()?.toString()
    }

    fun formatPoints(points: String): String {
        val value = points.trim().toBigIntegerOrNull() ?: BigInteger.ZERO
        return NumberFormat.getIntegerInstance(Locale.US).format(value)
    }
}
