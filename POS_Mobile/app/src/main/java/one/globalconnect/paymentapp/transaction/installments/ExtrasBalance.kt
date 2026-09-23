package one.globalconnect.paymentapp.transaction.installments

import java.math.BigDecimal

/** DE4 is a 12-digit monetary balance with two implied decimal places; zero is valid. */
object ExtrasBalance {
    fun parse(field4: String?): String? = field4
        ?.takeIf { it.length == 12 && it.all { c -> c in '0'..'9' } }
        ?.let { BigDecimal(it).movePointLeft(2).toPlainString() }
}
