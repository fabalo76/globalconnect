package one.globalconnect.paymentapp.transaction

import java.util.Locale

/** Display identity only; never used to select an acquirer or route authorization. */
object CardBrandResolver {
    fun fromAid(aid: String?): String {
        val value = aid.orEmpty().trim().uppercase(Locale.ROOT)
        if (value.length !in 10..32 || value.length % 2 != 0 || !value.all { it in "0123456789ABCDEF" }) return ""
        return when {
            value.startsWith("A0000000043060") -> "MAESTRO"
            value.startsWith("A000000003") -> "VISA"
            value.startsWith("A000000004") -> "MASTERCARD"
            value.startsWith("A000000025") -> "AMEX"
            value.startsWith("A000000065") -> "JCB"
            value.startsWith("A000000152") -> "DISCOVER"
            value.startsWith("A000000333") -> "UNIONPAY"
            else -> ""
        }
    }

    fun resolve(aid: String?, panOrBin: String?): String {
        fromAid(aid).takeIf { it.isNotEmpty() }?.let { return it }
        // Never strip masking characters and accidentally classify the last four digits.
        val digits = panOrBin.orEmpty().trim().takeWhile { it in '0'..'9' }
        if (digits.length < 6) return ""
        val bin = digits.take(6).toInt()
        return when {
            bin in 510000..559999 || bin in 222100..272099 -> "MASTERCARD"
            digits.startsWith("4") -> "VISA"
            digits.startsWith("34") || digits.startsWith("37") -> "AMEX"
            digits.startsWith("6011") || bin in 644000..649999 || digits.startsWith("65") -> "DISCOVER"
            // 622126-622925 can be co-badged; require AID rather than guessing a network.
            else -> ""
        }
    }
}

/** Legacy rows may contain an issuer name in cardType: never trust it as card evidence. */
fun Transaction.resolvedCardBrand(): String {
    CardBrandResolver.fromAid(AID).takeIf { it.isNotEmpty() }?.let { return it }
    val pan = runCatching { one.globalconnect.paymentapp.security.EncryptionUtil.decryptData(cardNumber) }.getOrNull()
    return CardBrandResolver.resolve(AID, pan)
}
