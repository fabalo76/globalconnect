package com.uic.uicpaymentapp.util

import com.uic.uicpaymentapp.BuildConfig
import com.uic.uicpaymentapp.uicpos.pos.model.TransLog
import kotlin.math.max
import kotlin.math.min

/**
 * Centralises logic required to remove or obfuscate cardholder information from
 * diagnostic logs. The behaviour is controlled by [BuildConfig.SHOW_RAW_PAN_LOGS].
 */
object LogSanitizer {

    const val REDACTED = "[REDACTED]"

    private const val MASK_CHAR = '•'
    private val PAN_CANDIDATE_REGEX = Regex("(?<!\\d)\\d{12,19}(?!\\d)")
    private val FIELDS_EXEMPT_FROM_PAN_MASKING = setOf(37, 55)
    private val SENSITIVE_XML_TAGS = listOf(
        "CardNbr" to ::maskCardNumber,
        "PAN" to ::maskCardNumber,
        "Track1" to ::maskTrackData,
        "Track2" to ::maskTrackData,
        "Track3" to ::maskTrackData,
        "Field55" to ::maskAlphaNumeric,
        "ExpireMonth" to ::maskAlphaNumeric,
        "ExpireYear" to ::maskAlphaNumeric,
        "CVV" to ::maskAlphaNumeric,
        "Token" to ::maskAlphaNumeric
    )

    /** Returns `true` when sensitive values should be obfuscated in logs. */
    fun shouldMaskSensitiveData(): Boolean = !BuildConfig.SHOW_RAW_PAN_LOGS

    /**
     * Applies masking rules to generic protocol payloads (XML, plain text,
     * etc.) when required by the current build configuration.
     */
    fun sanitizeMessage(value: String): String {
        if (!shouldMaskSensitiveData()) return value

        var sanitized = value
        for ((tag, masker) in SENSITIVE_XML_TAGS) {
            sanitized = maskXmlTag(sanitized, tag, masker)
        }
        return maskPanCandidates(sanitized)
    }

    /**
     * Returns the supplied hexadecimal payload or a redacted marker if
     * sensitive logging is disabled.
     */
    fun sanitizeHexPayload(
        clearPan: String? = null,
        track1: String? = null,
        track2: String? = null,
        track3: String? = null,
        producer: () -> String,
    ): String {
        if (!shouldMaskSensitiveData()) return producer()

        var sanitized = producer()
        if (sanitized.isEmpty()) return sanitized

        sanitized = applyHexMask(sanitized, clearPan, ::maskCardNumberWithZeros)
        sanitized = applyHexMask(sanitized, track1, ::maskTrackDataWithZeros)
        sanitized = applyHexMask(sanitized, track2, ::maskTrackDataWithZeros)
        sanitized = applyHexMask(sanitized, track3, ::maskTrackDataWithZeros)

        return sanitized
    }

    /** Sanitises ISO8583 field values based on their field number. */
    fun sanitizeIsoField(field: Int, value: String): String {
        if (!shouldMaskSensitiveData()) return value

        val sanitized = when (field) {
            2 -> maskCardNumber(value)
            14 -> maskAlphaNumeric(value)
            35, 36, 45, 57 -> maskTrackData(value)
            52, 53 -> maskAlphaNumeric(value)
            else -> value
        }

        return if (field in FIELDS_EXEMPT_FROM_PAN_MASKING) {
            sanitized
        } else {
            maskPanCandidates(sanitized)
        }
    }

    /** Sanitises the contents of [transLog] for logging purposes. */
    fun sanitizeTransLog(transLog: TransLog): String {
        if (!shouldMaskSensitiveData()) return transLog.toString()

        val sanitized = transLog.copy(
            CardNbr = maskCardNumber(transLog.CardNbr),
            ExpireMonth = maskAlphaNumeric(transLog.ExpireMonth),
            ExpireYear = maskAlphaNumeric(transLog.ExpireYear),
            CVV = maskAlphaNumeric(transLog.CVV),
            Token = maskAlphaNumericOptional(transLog.Token),
            PAN = maskCardNumberOptional(transLog.PAN),
            Track1 = maskAlphaNumericOptional(transLog.Track1),
            Track2 = maskAlphaNumericOptional(transLog.Track2),
            Track3 = maskAlphaNumericOptional(transLog.Track3),
            Field55 = maskAlphaNumericOptional(transLog.Field55)
        )
        return sanitized.toString()
    }

    private fun maskXmlTag(payload: String, tag: String, masker: (String) -> String): String {
        val regex = Regex("(<$tag>)(.*?)(</$tag>)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.replace(payload) { match ->
            val value = match.groupValues[2]
            match.groupValues[1] + masker(value) + match.groupValues[3]
        }
    }

    private fun maskPanCandidates(value: String): String {
        return PAN_CANDIDATE_REGEX.replace(value) { match -> maskCardNumber(match.value) }
    }

    private fun maskCardNumber(value: String): String {
        if (value.isEmpty()) return value
        return maskDigits(value, keepStart = 6, keepEnd = 4)
    }

    private fun maskCardNumberOptional(value: String?): String? {
        return value?.let { maskCardNumber(it) }
    }

    private fun maskTrackData(value: String): String {
        if (value.isEmpty()) return value
        return maskDigits(value, keepStart = 6, keepEnd = 2)
    }

    private fun maskCardNumberWithZeros(value: String): String {
        if (value.isEmpty()) return value
        return maskDigits(value, keepStart = 6, keepEnd = 4, maskChar = '0')
    }

    private fun maskTrackDataWithZeros(value: String): String {
        if (value.isEmpty()) return value
        return maskDigits(value, keepStart = 6, keepEnd = 2, maskChar = '0')
    }

    private fun maskAlphaNumeric(value: String): String {
        if (value.isEmpty()) return value
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            builder.append(if (char.isLetterOrDigit()) MASK_CHAR else char)
        }
        return builder.toString()
    }

    private fun maskAlphaNumericOptional(value: String?): String? {
        return value?.let { maskAlphaNumeric(it) }
    }

    private fun maskDigits(value: String, keepStart: Int, keepEnd: Int, maskChar: Char = MASK_CHAR): String {
        val totalDigits = value.count { it.isDigit() }
        if (totalDigits == 0) return value

        val visibleStart = min(keepStart, totalDigits)
        val visibleEnd = min(keepEnd, max(totalDigits - visibleStart, 0))
        val maskStart = when {
            totalDigits <= 6 -> 1
            totalDigits <= visibleStart + visibleEnd -> max(1, totalDigits - 5)
            else -> visibleStart
        }
        val maskEnd = when {
            totalDigits <= 6 -> totalDigits
            totalDigits <= visibleStart + visibleEnd -> totalDigits - 1
            else -> totalDigits - visibleEnd
        }

        var digitIndex = 0
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            if (!char.isDigit()) {
                builder.append(char)
                return@forEach
            }

            val shouldMask = digitIndex in maskStart until maskEnd
            builder.append(if (shouldMask) maskChar else char)
            digitIndex++
        }
        return builder.toString()
    }

    private fun applyHexMask(
        hexPayload: String,
        value: String?,
        masker: (String) -> String,
    ): String {
        val rawValue = value?.takeIf { it.isNotEmpty() } ?: return hexPayload
        val maskedValue = masker(rawValue)
        if (maskedValue == rawValue) return hexPayload

        var result = hexPayload

        val rawHex = rawValue.toByteArray(Charsets.UTF_8).toHexString()
        val maskedHex = maskedValue.toByteArray(Charsets.UTF_8).toHexString()
        if (rawHex.isNotEmpty() && rawHex != maskedHex) {
            result = result.replace(rawHex, maskedHex)
        }

        if (rawValue.all { it.isDigit() }) {
            val rawBcd = rawValue.toPackedBcdOrNull()
            val maskedBcd = maskedValue.toPackedBcdOrNull()
            if (rawBcd != null && maskedBcd != null) {
                val rawBcdHex = rawBcd.toHexString()
                val maskedBcdHex = maskedBcd.toHexString()
                if (rawBcdHex.isNotEmpty() && rawBcdHex != maskedBcdHex) {
                    result = result.replace(rawBcdHex, maskedBcdHex)
                }
            }
        }

        return result
    }

    private fun ByteArray.toHexString(): String {
        if (isEmpty()) return ""

        val builder = StringBuilder(size * 2)
        for (index in indices) {
            builder.append(String.format("%02X", this[index]))
        }
        return builder.toString()
    }

    private fun String.toPackedBcdOrNull(): ByteArray? {
        if (isEmpty()) return null
        val normalized = uppercase()
        if (normalized.any { !it.isDigit() }) return null

        val padded = if (normalized.length % 2 == 0) normalized else normalized + 'F'
        val result = ByteArray(padded.length / 2)
        var index = 0
        while (index < padded.length) {
            val high = padded[index].digitToInt(16)
            val low = padded[index + 1].digitToInt(16)
            result[index / 2] = ((high shl 4) or low).toByte()
            index += 2
        }
        return result
    }

}
