package one.globalconnect.paymentapp.cardreader.nexgo

internal data class EmvCountryCurrencyTags(
    val countryCode: ByteArray,
    val currencyCode: ByteArray,
)

internal object EmvTransactionTags {
    fun countryAndCurrency(countryCode: String, currencyCode: String): EmvCountryCurrencyTags? {
        val encodedCountry = encodeNumericCode(countryCode) ?: return null
        val encodedCurrency = encodeNumericCode(currencyCode) ?: return null
        return EmvCountryCurrencyTags(
            countryCode = encodedCountry,
            currencyCode = encodedCurrency,
        )
    }

    /** Converts ISO numeric code 188/0188 to the EMV BCD value 01 88. */
    internal fun encodeNumericCode(value: String): ByteArray? {
        val digits = value.trim()
        if (digits.isEmpty() || digits.length > 4 || digits.any { !it.isDigit() }) return null
        val numericCode = digits.toIntOrNull()?.takeIf { it in 1..999 } ?: return null
        val bcd = numericCode.toString().padStart(4, '0')
        return byteArrayOf(
            bcd.substring(0, 2).toInt(16).toByte(),
            bcd.substring(2, 4).toInt(16).toByte(),
        )
    }
}
