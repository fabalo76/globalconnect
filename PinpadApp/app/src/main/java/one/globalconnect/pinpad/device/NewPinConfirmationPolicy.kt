package one.globalconnect.pinpad.device

import java.security.MessageDigest

/**
 * Validates that two encrypted new-PIN captures represent the same PIN without exposing clear PIN data.
 */
internal object NewPinConfirmationPolicy {
    /** Result of comparing the first and confirmation PIN-entry responses. */
    internal sealed interface Result {
        /** Both encrypted captures match and the first response can be returned to the host. */
        data class Confirmed(val responsePayload: String) : Result

        /** Both captures are structurally valid but contain different PIN values. */
        data object Mismatch : Result

        /** One of the captures is malformed or the DUKPT KSN changed between captures. */
        data object Invalid : Result
    }

    /** PIN-key scheme used to produce the encrypted capture. */
    internal enum class Scheme {
        MASTER_SESSION,
        DUKPT,
    }

    /**
     * Compares two protocol response payloads using a constant-time PIN-block comparison.
     *
     * @param firstPayload response from the first new-PIN entry.
     * @param confirmationPayload response from the confirmation entry.
     * @param scheme encryption scheme used for both captures.
     * @return the confirmation outcome; no input payload is modified.
     */
    internal fun compare(
        firstPayload: String,
        confirmationPayload: String,
        scheme: Scheme,
    ): Result {
        val first = parse(firstPayload, scheme) ?: return Result.Invalid
        val confirmation = parse(confirmationPayload, scheme) ?: return Result.Invalid
        if (scheme == Scheme.DUKPT && !first.ksn.equals(confirmation.ksn, ignoreCase = true)) {
            return Result.Invalid
        }
        if (first.pinLength != confirmation.pinLength) return Result.Mismatch
        val firstBlock = first.pinBlock.hexToBytes() ?: return Result.Invalid
        val confirmationBlock = confirmation.pinBlock.hexToBytes() ?: return Result.Invalid
        return try {
            if (MessageDigest.isEqual(firstBlock, confirmationBlock)) {
                Result.Confirmed(firstPayload)
            } else {
                Result.Mismatch
            }
        } finally {
            firstBlock.fill(0)
            confirmationBlock.fill(0)
        }
    }

    /**
     * Checks whether a successful encrypted PIN response has the expected protocol structure.
     *
     * @param payload response payload to inspect.
     * @param scheme encryption scheme that produced the payload.
     * @return `true` when the response contains a usable encrypted PIN capture.
     */
    internal fun isCapture(payload: String, scheme: Scheme): Boolean = parse(payload, scheme) != null

    /**
     * Parses a successful encrypted PIN response into comparison-only fields.
     *
     * @param payload response payload to parse.
     * @param scheme encryption scheme that produced the payload.
     * @return parsed fields or `null` for an invalid payload.
     */
    private fun parse(payload: String, scheme: Scheme): Capture? {
        return when (scheme) {
            Scheme.MASTER_SESSION -> {
                if (payload.length != MASTER_SESSION_RESPONSE_CHARS || !payload.startsWith(".0")) return null
                val pinLength = payload.substring(2, 4).toIntOrNull() ?: return null
                val keyId = payload.substring(4, 6)
                val pinBlock = payload.substring(6)
                if (pinLength !in MIN_PIN_LENGTH..MAX_PIN_LENGTH || keyId != "01" || !pinBlock.isHex()) return null
                Capture(pinLength = pinLength, ksn = "", pinBlock = pinBlock)
            }
            Scheme.DUKPT -> {
                if (!payload.startsWith('0') || payload.length <= PIN_BLOCK_HEX_CHARS + 1) return null
                val body = payload.drop(1)
                val ksn = body.dropLast(PIN_BLOCK_HEX_CHARS)
                val pinBlock = body.takeLast(PIN_BLOCK_HEX_CHARS)
                if (ksn.isBlank() || !ksn.isHex() || !pinBlock.isHex()) return null
                Capture(pinLength = null, ksn = ksn, pinBlock = pinBlock)
            }
        }
    }

    /**
     * Converts an even-length hexadecimal value to bytes.
     *
     * @return decoded bytes, or `null` when the value is not valid hexadecimal.
     */
    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0 || !isHex()) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /** Returns whether every character in this non-empty value is hexadecimal. */
    private fun String.isHex(): Boolean = isNotEmpty() && all { it.digitToIntOrNull(16) != null }

    /** Parsed fields retained only long enough to compare two encrypted captures. */
    private data class Capture(
        val pinLength: Int?,
        val ksn: String,
        val pinBlock: String,
    )

    private const val PIN_BLOCK_HEX_CHARS = 16
    private const val MASTER_SESSION_RESPONSE_CHARS = 6 + PIN_BLOCK_HEX_CHARS
    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 12
}
