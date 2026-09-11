package one.globalconnect.paymentapp.transaction

/**
 * Resolves the card application name for display and receipt printing.
 *
 * EMV Application Preferred Name (9F12) takes precedence over Application Label (50). Both
 * values arrive from the kernel as hexadecimal text and must be decoded before presentation.
 */
internal fun resolveEmvApplicationName(
    preferredNameHex: String?,
    applicationLabelHex: String?,
    issuerFallback: String,
): String = decodeEmvText(preferredNameHex)
    ?: decodeEmvText(applicationLabelHex)
    ?: issuerFallback

internal fun resolveEmvCardholderName(
    cardholderNameHex: String?,
    track1: String?,
): String = decodeEmvText(cardholderNameHex)
    ?: track1
        ?.trim()
        ?.removePrefix("%")
        ?.split('^')
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    ?: ""

private fun decodeEmvText(value: String?): String? {
    val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (normalized.length % 2 != 0 || normalized.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
        return null
    }

    val bytes = ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return String(bytes, Charsets.ISO_8859_1)
        .trim { it.isWhitespace() || it == '\u0000' || it == '\u00FF' }
        .takeIf { it.isNotEmpty() }
}
