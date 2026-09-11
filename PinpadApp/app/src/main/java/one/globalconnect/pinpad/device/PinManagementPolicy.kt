package one.globalconnect.pinpad.device

import java.util.Locale

/**
 * Defines the A10 T37 operation format and the EMV rules for an offline PIN change.
 *
 * The change is a zero-amount contact transaction. The current offline PIN must be
 * verified before the ARQC is exposed to the host, and the final deliberate AAC is
 * accepted only when issuer-script processing completed without a TVR script error.
 */
internal object PinManagementPolicy {
    /** Identifies the operation requested after the T37 SUB delimiter. */
    enum class Operation(val code: Char) {
        CHANGE('1'),
        UNBLOCK('2'),
        VERIFY('3'),
    }

    /**
     * Parses an exact T37 payload.
     *
     * @param payload A10 payload in the form `SUB + operation`.
     * @return the requested operation, or null when the payload is malformed.
     */
    fun parseOperation(payload: String): Operation? {
        if (payload.length != T37_PAYLOAD_LENGTH || payload.firstOrNull() != SUB) return null
        return Operation.entries.firstOrNull { operation -> operation.code == payload[1] }
    }

    /**
     * Restricts terminal capabilities to configured plaintext or enciphered offline PIN.
     *
     * @param configured9F33 the three-byte terminal-capabilities value selected for the AID.
     * @return a restricted copy, or null when no offline-PIN capability is available.
     */
    fun restrictTerminalCapabilities(configured9F33: ByteArray?): ByteArray? {
        val source = configured9F33?.takeIf { value -> value.size >= TERMINAL_CAPABILITIES_BYTES } ?: return null
        val result = source.copyOf(TERMINAL_CAPABILITIES_BYTES)
        val configuredCvm = result[CVM_CAPABILITIES_INDEX].toInt() and BYTE_MASK
        val offlinePinCapabilities = configuredCvm and OFFLINE_PIN_CAPABILITIES_MASK
        if (offlinePinCapabilities == 0) return null
        result[CVM_CAPABILITIES_INDEX] =
            ((configuredCvm and STANDARD_CVM_CAPABILITIES_MASK.inv()) or offlinePinCapabilities).toByte()
        return result
    }

    /**
     * Restricts CVM capabilities to No CVM for a host-script PIN-unblock operation.
     * A blocked PIN must not be requested while the terminal is obtaining the issuer script.
     */
    fun restrictToNoCvmTerminalCapabilities(configured9F33: ByteArray?): ByteArray? {
        val source = configured9F33?.takeIf { value -> value.size >= TERMINAL_CAPABILITIES_BYTES } ?: return null
        val result = source.copyOf(TERMINAL_CAPABILITIES_BYTES)
        val configuredCvm = result[CVM_CAPABILITIES_INDEX].toInt() and BYTE_MASK
        result[CVM_CAPABILITIES_INDEX] =
            ((configuredCvm and STANDARD_CVM_CAPABILITIES_MASK.inv()) or NO_CVM_CAPABILITY).toByte()
        return result
    }

    /**
     * Validates that the first cryptogram belongs to a zero-amount offline-PIN change.
     *
     * @param tags EMV tags collected when the kernel requests online processing.
     * @return true only for an ARQC following a successful offline-PIN CVM.
     */
    fun isValidChangeArqc(tags: Map<String, String>): Boolean {
        val normalized = normalizeTags(tags)
        return REQUIRED_ARQC_TAGS.all(normalized::containsKey) &&
            normalized[AMOUNT_AUTHORIZED_TAG] == ZERO_AMOUNT &&
            normalized[CID_TAG] == ARQC_CID &&
            isSuccessfulOfflinePinCvm(normalized[CVM_RESULTS_TAG])
    }

    /**
     * Validates that a PIN-unblock request is a zero-amount No-CVM ARQC.
     *
     * @param tags EMV tags collected when the kernel requests online processing.
     * @return true only when the card did not request a PIN and produced an ARQC.
     */
    fun isValidUnblockArqc(tags: Map<String, String>): Boolean {
        val normalized = normalizeTags(tags)
        return REQUIRED_ARQC_TAGS.all(normalized::containsKey) &&
            normalized[AMOUNT_AUTHORIZED_TAG] == ZERO_AMOUNT &&
            normalized[CID_TAG] == ARQC_CID &&
            isSuccessfulNoCvm(normalized[CVM_RESULTS_TAG])
    }

    /**
     * Validates the expected final AAC used after a PIN-change or PIN-unblock issuer script.
     *
     * @param responseCode issuer response code passed to the kernel.
     * @param tags completion tags collected after second Generate AC.
     * @return true when the operation-specific response produced the expected cryptogram and successful script evidence.
     */
    fun isSuccessfulMaintenanceCompletion(
        operation: Operation?,
        responseCode: String?,
        tags: Map<String, String>,
    ): Boolean {
        val (expectedResponseCode, expectedCryptogramType) = when (operation) {
            Operation.CHANGE -> PIN_CHANGE_SUCCESS_CODE to CID_AAC
            Operation.UNBLOCK -> PIN_UNBLOCK_SUCCESS_CODE to CID_TC
            else -> return false
        }
        if (responseCode != expectedResponseCode) return false
        val normalized = normalizeTags(tags)
        val cid = normalized[CID_TAG].validHex(HEX_BYTE_LENGTH)?.toInt(HEX_RADIX) ?: return false
        val tvr = normalized[TVR_TAG].validHex(TVR_HEX_LENGTH) ?: return false
        val tsi = normalized[TSI_TAG].validHex(TSI_HEX_LENGTH) ?: return false
        val issuerScriptProcessingPerformed =
            tsi.substring(0, HEX_BYTE_LENGTH).toInt(HEX_RADIX) and TSI_ISSUER_SCRIPT_PROCESSING_MASK != 0
        val issuerScriptFailed =
            tvr.substring(TVR_LAST_BYTE_OFFSET).toInt(HEX_RADIX) and TVR_ISSUER_SCRIPT_FAILURE_MASK != 0
        return cid and CID_CRYPTOGRAM_TYPE_MASK == expectedCryptogramType &&
            issuerScriptProcessingPerformed &&
            !issuerScriptFailed
    }

    /**
     * Determines whether tag 9F34 records successful plaintext or enciphered offline PIN.
     *
     * @param tag9F34 cardholder-verification-method results.
     * @return true only for a successful offline-PIN CVM result.
     */
    fun isSuccessfulOfflinePinCvm(tag9F34: String?): Boolean {
        val normalized = tag9F34.validHex(CVM_RESULTS_HEX_LENGTH) ?: return false
        val cvmCode = normalized.substring(0, HEX_BYTE_LENGTH).toInt(HEX_RADIX) and CVM_CODE_MASK
        val result = normalized.substring(CVM_RESULT_OFFSET).toInt(HEX_RADIX)
        return cvmCode in OFFLINE_PIN_CVM_CODES && result == CVM_RESULT_SUCCESSFUL
    }

    /**
     * Determines whether tag 9F34 records a successful No-CVM outcome.
     *
     * EMV kernels may report either `1F` (No CVM required) or `3F` (No CVM
     * performed). Both are valid for PIN unblock when the result byte is successful.
     *
     * @param tag9F34 cardholder-verification-method results.
     * @return true only for a successful No-CVM result.
     */
    private fun isSuccessfulNoCvm(tag9F34: String?): Boolean {
        val normalized = tag9F34.validHex(CVM_RESULTS_HEX_LENGTH) ?: return false
        val cvmCode = normalized.substring(0, HEX_BYTE_LENGTH).toInt(HEX_RADIX) and CVM_CODE_MASK
        val result = normalized.substring(CVM_RESULT_OFFSET).toInt(HEX_RADIX)
        return cvmCode in NO_CVM_CODES && result == CVM_RESULT_SUCCESSFUL
    }

    /**
     * Normalizes tag identifiers and values for deterministic comparisons.
     *
     * @param tags untrusted EMV tag map.
     * @return an uppercase map with whitespace removed from values.
     */
    private fun normalizeTags(tags: Map<String, String>): Map<String, String> =
        tags.entries.associate { (tag, value) ->
            tag.uppercase(Locale.US) to value.filterNot(Char::isWhitespace).uppercase(Locale.US)
        }

    /**
     * Returns an uppercase hexadecimal value only when it has the expected length.
     *
     * @param expectedLength required character count.
     * @return the normalized value, or null when malformed.
     */
    private fun String?.validHex(expectedLength: Int): String? = this
        ?.filterNot(Char::isWhitespace)
        ?.uppercase(Locale.US)
        ?.takeIf { value -> value.length == expectedLength && value.all { it.digitToIntOrNull(HEX_RADIX) != null } }

    const val ZERO_AMOUNT = "000000000000"
    const val PIN_CHANGE_SUCCESS_CODE = "85"
    const val PIN_UNBLOCK_SUCCESS_CODE = "00"

    private const val SUB = '\u001A'
    private const val T37_PAYLOAD_LENGTH = 2
    private const val TERMINAL_CAPABILITIES_BYTES = 3
    private const val CVM_CAPABILITIES_INDEX = 1
    private const val BYTE_MASK = 0xFF
    private const val PLAINTEXT_OFFLINE_PIN_CAPABILITY = 0x80
    private const val ENCIPHERED_OFFLINE_PIN_CAPABILITY = 0x10
    private const val NO_CVM_CAPABILITY = 0x08
    private const val OFFLINE_PIN_CAPABILITIES_MASK =
        PLAINTEXT_OFFLINE_PIN_CAPABILITY or ENCIPHERED_OFFLINE_PIN_CAPABILITY
    private const val STANDARD_CVM_CAPABILITIES_MASK = 0xF8
    private const val AMOUNT_AUTHORIZED_TAG = "9F02"
    private const val CID_TAG = "9F27"
    private const val CVM_RESULTS_TAG = "9F34"
    private const val TVR_TAG = "95"
    private const val TSI_TAG = "9B"
    private const val ARQC_CID = "80"
    private const val HEX_RADIX = 16
    private const val HEX_BYTE_LENGTH = 2
    private const val CVM_RESULTS_HEX_LENGTH = 6
    private const val CVM_CODE_MASK = 0x3F
    private const val CVM_RESULT_OFFSET = 4
    private const val CVM_RESULT_SUCCESSFUL = 0x02
    private const val TVR_HEX_LENGTH = 10
    private const val TVR_LAST_BYTE_OFFSET = 8
    private const val TVR_ISSUER_SCRIPT_FAILURE_MASK = 0x30
    private const val TSI_HEX_LENGTH = 4
    private const val TSI_ISSUER_SCRIPT_PROCESSING_MASK = 0x04
    private const val CID_CRYPTOGRAM_TYPE_MASK = 0xC0
    private const val CID_AAC = 0x00
    private const val CID_TC = 0x40
    private val OFFLINE_PIN_CVM_CODES = setOf(0x01, 0x04)
    private val NO_CVM_CODES = setOf(0x1F, 0x3F)
    private val REQUIRED_ARQC_TAGS = setOf(
        "9F02", "9F03", "9F1A", "95", "5F2A", "9A", "9C", "9F37", "82",
        "9F36", "9F10", "9F26", "9F27", "9F34",
    )
}
