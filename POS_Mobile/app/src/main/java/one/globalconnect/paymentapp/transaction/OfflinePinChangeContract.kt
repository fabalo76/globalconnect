package one.globalconnect.paymentapp.transaction

import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import java.util.Locale

internal enum class OfflinePinChangeAvailability {
    AVAILABLE,
    DISABLED_BY_TERMINAL,
    ONLINE_PIN_UNAVAILABLE,
    OFFLINE_PIN_UNAVAILABLE,
    NO_CVM_UNAVAILABLE,
    ACQUIRER_PIN_KEY_UNAVAILABLE,
}

/**
 * Defines the security and host rules that are unique to an offline PIN-change transaction.
 *
 * The application performs two isolated secure PIN-pad entries. For MK/SK they use the selected
 * acquirer's PIN key. For DUKPT they share one reserved KSN, compare encrypted ISO-0 blocks, and
 * consume that KSN once after confirmation.
 */
internal object OfflinePinChangeContract {
    const val PROCESSING_CODE = "920000"
    const val ZERO_AMOUNT = "000000000000"
    const val HOST_SUCCESS_CODE = "85"
    const val PIN_UNBLOCK_PROCESSING_CODE = "910000"
    const val PIN_UNBLOCK_HOST_SUCCESS_CODE = "00"

    fun configurationAvailability(
        terminal: TMS_Terminal?,
        acquirers: List<TMS_Acquirer>,
    ): OfflinePinChangeAvailability = when {
        terminal?.enableOfflinePinChange != true -> OfflinePinChangeAvailability.DISABLED_BY_TERMINAL
        !terminal.onlinePinCap -> OfflinePinChangeAvailability.ONLINE_PIN_UNAVAILABLE
        !terminal.offlineClearPinCap && !terminal.offlineEncrPinCap -> {
            OfflinePinChangeAvailability.OFFLINE_PIN_UNAVAILABLE
        }
        acquirers.none(::supportsAcquirer) -> {
            OfflinePinChangeAvailability.ACQUIRER_PIN_KEY_UNAVAILABLE
        }
        else -> OfflinePinChangeAvailability.AVAILABLE
    }

    fun isConfiguredForMenu(
        terminal: TMS_Terminal?,
        acquirers: List<TMS_Acquirer>,
    ): Boolean {
        return configurationAvailability(terminal, acquirers) == OfflinePinChangeAvailability.AVAILABLE
    }

    fun pinUnblockConfigurationAvailability(
        terminal: TMS_Terminal?,
    ): OfflinePinChangeAvailability = when {
        terminal?.enableOfflinePinUnblock != true -> OfflinePinChangeAvailability.DISABLED_BY_TERMINAL
        !terminal.noCVMCap -> OfflinePinChangeAvailability.NO_CVM_UNAVAILABLE
        else -> OfflinePinChangeAvailability.AVAILABLE
    }

    fun isPinUnblockConfiguredForMenu(terminal: TMS_Terminal?): Boolean =
        pinUnblockConfigurationAvailability(terminal) == OfflinePinChangeAvailability.AVAILABLE

    fun isPinMaintenance(transactionType: TransactionType): Boolean =
        transactionType == TransactionType.OFFLINE_PIN_CHANGE ||
            transactionType == TransactionType.PIN_UNBLOCK

    /** Returns true when [acquirer] defines a usable Nexgo PIN-key slot and scheme. */
    fun supportsAcquirer(acquirer: TMS_Acquirer): Boolean =
        acquirer.supportsOnlinePin && acquirer.nexgoPinKeyIndex != null

    fun isHostApproved(transactionType: TransactionType, responseCode: String?): Boolean =
        responseCode == PIN_UNBLOCK_HOST_SUCCESS_CODE ||
            (transactionType == TransactionType.OFFLINE_PIN_CHANGE &&
                responseCode == HOST_SUCCESS_CODE)

    /**
     * Returns true when an offline PIN-change completed with the deliberate final AAC used by
     * M-TIP28 after the issuer script ran successfully.
     *
     * The final AAC is not a decline of the PIN-change service. The service uses response 85 and
     * requests AAC so the zero-amount maintenance transaction cannot become a financial approval.
     * Script execution is considered successful only when TSI confirms issuer-script processing
     * and neither of the TVR issuer-script-failure bits is set.
     */
    fun isExpectedSuccessfulAacCompletion(
        transactionType: TransactionType,
        responseCode: String?,
        tags: Map<String, String>,
    ): Boolean {
        val expectedResponseCode = when (transactionType) {
            TransactionType.OFFLINE_PIN_CHANGE -> HOST_SUCCESS_CODE
            TransactionType.PIN_UNBLOCK -> PIN_UNBLOCK_HOST_SUCCESS_CODE
            else -> return false
        }
        if (responseCode != expectedResponseCode) {
            return false
        }
        val normalizedTags = tags.entries.associate { (tag, value) ->
            tag.uppercase(Locale.US) to value.filterNot(Char::isWhitespace).uppercase(Locale.US)
        }
        val cid = normalizedTags["9F27"]
            ?.takeIf { value -> value.length == HEX_BYTE_LENGTH && value.all { it.digitToIntOrNull(16) != null } }
            ?.toInt(16)
            ?: return false
        val tvr = normalizedTags["95"]
            ?.takeIf { value -> value.length == TVR_HEX_LENGTH && value.all { it.digitToIntOrNull(16) != null } }
            ?: return false
        val tsi = normalizedTags["9B"]
            ?.takeIf { value -> value.length == TSI_HEX_LENGTH && value.all { it.digitToIntOrNull(16) != null } }
            ?: return false
        val issuerScriptProcessingPerformed =
            tsi.substring(0, HEX_BYTE_LENGTH).toInt(16) and TSI_ISSUER_SCRIPT_PROCESSING_MASK != 0
        val issuerScriptFailed =
            tvr.substring(TVR_LAST_BYTE_OFFSET).toInt(16) and TVR_ISSUER_SCRIPT_FAILURE_MASK != 0
        return cid and CID_CRYPTOGRAM_TYPE_MASK == CID_AAC &&
            issuerScriptProcessingPerformed &&
            !issuerScriptFailed
    }

    fun isSuccessfulOfflinePinCvm(tag9F34: String?): Boolean {
        val normalized = tag9F34
            ?.filterNot(Char::isWhitespace)
            ?.uppercase(Locale.US)
            ?.takeIf { value -> value.length == 6 && value.all { it.digitToIntOrNull(16) != null } }
            ?: return false
        val cvmCode = normalized.substring(0, 2).toInt(16) and 0x3F
        val result = normalized.substring(4, 6).toInt(16)
        return cvmCode in OFFLINE_PIN_CVM_CODES && result == CVM_RESULT_SUCCESSFUL
    }

    /**
     * Returns true when tag 9F34 reports a successful EMV "No CVM Required" rule.
     *
     * EMV code 1F identifies the No CVM Required method. Code 3F means that no CVM was
     * performed and must not be treated as a successful CVM-list rule for PIN unblock.
     */
    fun isSuccessfulNoCvm(tag9F34: String?): Boolean {
        val normalized = tag9F34
            ?.filterNot(Char::isWhitespace)
            ?.uppercase(Locale.US)
            ?.takeIf { value -> value.length == 6 && value.all { it.digitToIntOrNull(16) != null } }
            ?: return false
        val cvmCode = normalized.substring(0, 2).toInt(16) and 0x3F
        val result = normalized.substring(4, 6).toInt(16)
        return cvmCode == NO_CVM_REQUIRED_CODE && result == CVM_RESULT_SUCCESSFUL
    }

    fun isValidArqcRequest(tags: Map<String, String>): Boolean {
        val normalizedTags = tags.entries.associate { (tag, value) ->
            tag.uppercase(Locale.US) to value.filterNot(Char::isWhitespace).uppercase(Locale.US)
        }
        return REQUIRED_ARQC_TAGS.all(normalizedTags::containsKey) &&
            normalizedTags["9F02"] == ZERO_AMOUNT &&
            normalizedTags["9F27"] == CRYPTOGRAM_INFORMATION_ARQC &&
            isSuccessfulOfflinePinCvm(normalizedTags["9F34"])
    }

    fun isValidPinUnblockArqcRequest(tags: Map<String, String>): Boolean {
        val normalizedTags = tags.entries.associate { (tag, value) ->
            tag.uppercase(Locale.US) to value.filterNot(Char::isWhitespace).uppercase(Locale.US)
        }
        return REQUIRED_ARQC_TAGS.all(normalizedTags::containsKey) &&
            normalizedTags["9F02"] == ZERO_AMOUNT &&
            normalizedTags["9F27"] == CRYPTOGRAM_INFORMATION_ARQC &&
            isSuccessfulNoCvm(normalizedTags["9F34"])
    }

    fun hasRequiredIssuerResponse(field55: String?): Boolean {
        val tags = parseTopLevelTags(field55) ?: return false
        return tags.contains(TAG_ISSUER_AUTHENTICATION_DATA) &&
            tags.contains(TAG_ISSUER_SCRIPT_TEMPLATE_1)
    }

    fun hasRequiredPinUnblockIssuerResponse(field55: String?): Boolean {
        val tags = parseTopLevelTagValues(field55) ?: return false
        val issuerAuthenticationData = tags[TAG_ISSUER_AUTHENTICATION_DATA] ?: return false
        val issuerScript = tags[TAG_ISSUER_SCRIPT_TEMPLATE_1] ?: return false
        return issuerAuthenticationData.endsWith(PIN_UNBLOCK_ARPC_RESPONSE_DATA) &&
            issuerScript.startsWith(PIN_UNBLOCK_SCRIPT_PREFIX)
    }

    private fun parseTopLevelTags(tlv: String?): Set<String>? {
        return parseTopLevelTagValues(tlv)?.keys
    }

    private fun parseTopLevelTagValues(tlv: String?): Map<String, String>? {
        val normalized = tlv
            ?.filterNot(Char::isWhitespace)
            ?.uppercase(Locale.US)
            ?.takeIf { value ->
                value.isNotEmpty() && value.length % 2 == 0 &&
                    value.all { it.digitToIntOrNull(16) != null }
            }
            ?: return null
        val tags = linkedMapOf<String, String>()
        var index = 0
        while (index < normalized.length) {
            val tagStart = index
            val firstTagByte = normalized.readByte(index) ?: return null
            index += HEX_BYTE_LENGTH
            if (firstTagByte and MULTI_BYTE_TAG_MASK == MULTI_BYTE_TAG_VALUE) {
                var nextTagByte: Int
                do {
                    nextTagByte = normalized.readByte(index) ?: return null
                    index += HEX_BYTE_LENGTH
                } while (nextTagByte and CONTINUATION_BIT != 0)
            }
            val tag = normalized.substring(tagStart, index)
            val firstLengthByte = normalized.readByte(index) ?: return null
            index += HEX_BYTE_LENGTH
            val valueLength = if (firstLengthByte and LONG_FORM_LENGTH_BIT == 0) {
                firstLengthByte
            } else {
                val lengthByteCount = firstLengthByte and LONG_FORM_LENGTH_MASK
                if (lengthByteCount == 0 || lengthByteCount > MAX_LENGTH_BYTES) return null
                var accumulatedLength = 0
                repeat(lengthByteCount) {
                    val component = normalized.readByte(index) ?: return null
                    index += HEX_BYTE_LENGTH
                    accumulatedLength = (accumulatedLength shl BITS_PER_BYTE) or component
                }
                accumulatedLength
            }
            val valueCharacters = valueLength * HEX_BYTE_LENGTH
            if (valueCharacters < 0 || index + valueCharacters > normalized.length) return null
            tags[tag] = normalized.substring(index, index + valueCharacters)
            index += valueCharacters
        }
        return tags
    }

    private fun String.readByte(index: Int): Int? {
        if (index < 0 || index + HEX_BYTE_LENGTH > length) return null
        return substring(index, index + HEX_BYTE_LENGTH).toIntOrNull(16)
    }

    private val OFFLINE_PIN_CVM_CODES = setOf(0x01, 0x04)
    private val REQUIRED_ARQC_TAGS = setOf(
        "9F02",
        "9F03",
        "9F1A",
        "95",
        "5F2A",
        "9A",
        "9C",
        "9F37",
        "82",
        "9F36",
        "9F10",
        "9F26",
        "9F27",
        "9F34",
    )
    private const val CVM_RESULT_SUCCESSFUL = 0x02
    private const val NO_CVM_REQUIRED_CODE = 0x1F
    private const val CRYPTOGRAM_INFORMATION_ARQC = "80"
    private const val CID_CRYPTOGRAM_TYPE_MASK = 0xC0
    private const val CID_AAC = 0x00
    private const val TVR_HEX_LENGTH = 10
    private const val TVR_LAST_BYTE_OFFSET = 8
    private const val TVR_ISSUER_SCRIPT_FAILURE_MASK = 0x30
    private const val TSI_HEX_LENGTH = 4
    private const val TSI_ISSUER_SCRIPT_PROCESSING_MASK = 0x04
    private const val TAG_ISSUER_AUTHENTICATION_DATA = "91"
    private const val TAG_ISSUER_SCRIPT_TEMPLATE_1 = "71"
    private const val PIN_UNBLOCK_ARPC_RESPONSE_DATA = "0012"
    private const val PIN_UNBLOCK_SCRIPT_PREFIX = "860D8424000008"
    private const val HEX_BYTE_LENGTH = 2
    private const val BITS_PER_BYTE = 8
    private const val CONTINUATION_BIT = 0x80
    private const val MULTI_BYTE_TAG_MASK = 0x1F
    private const val MULTI_BYTE_TAG_VALUE = 0x1F
    private const val LONG_FORM_LENGTH_BIT = 0x80
    private const val LONG_FORM_LENGTH_MASK = 0x7F
    private const val MAX_LENGTH_BYTES = 3
}
