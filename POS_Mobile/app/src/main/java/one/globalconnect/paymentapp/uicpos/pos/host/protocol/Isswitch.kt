package one.globalconnect.paymentapp.uicpos.pos.host.protocol

import android.util.Log
import com.uic.pos.iso8583.IsoMessageFactory
import com.uic.pos.iso8583.exception.Iso8583Exception
import com.uic.pos.iso8583.util.IsoHexUtils
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.paymentapp.BuildConfig
import one.globalconnect.paymentapp.uicpos.pos.host.BatchNumberProvider
import one.globalconnect.paymentapp.uicpos.pos.host.EntryModeMapper
import one.globalconnect.paymentapp.uicpos.pos.host.HostProtocol
import one.globalconnect.paymentapp.uicpos.pos.host.HostProtocolContext
import one.globalconnect.paymentapp.uicpos.pos.host.HostProtocolException
import one.globalconnect.paymentapp.uicpos.pos.host.IsoFieldFormatter
import one.globalconnect.paymentapp.uicpos.pos.host.IsoMessageDebugLogger
import one.globalconnect.paymentapp.uicpos.pos.host.IsoMessageFactoryProvider
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.paymentapp.uicpos.pos.host.protocol.PrivateUseData63.Tag
import one.globalconnect.paymentapp.uicpos.pos.model.TransLog
import one.globalconnect.paymentapp.util.LogSanitizer
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.text.Regex

/**
 * Kotlin counterpart of the legacy `host_isswitch` module.
 */
class Isswitch(
    private val configRegistry: TransactionConfigRegistry = TransactionConfigRegistry,
    private val isoFactoryProvider: (String) -> IsoMessageFactory = IsoMessageFactoryProvider::factoryFor
) : HostProtocol {

    override fun buildIsoMessage(context: HostProtocolContext) = try {
        val transLog = context.procInfo.TransLog
        val transactionConfig = configRegistry.configFor(transLog.TxnType)
            ?: throw HostProtocolException("Unsupported transaction type: ${transLog.TxnType}")

        Log.d(TAG, "Building ISSWITCH ISO message for transaction type=${transLog.TxnType}")
        if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
            Log.d(TAG, "Source transaction log: ${LogSanitizer.sanitizeTransLog(transLog)}")
            Log.d(TAG, "Resolved transaction config: $transactionConfig")
        }

        val timestamp = context.timestampSupplier()
        val isoFactory = context.isoFactory ?: isoFactoryProvider(CONFIG_ASSET)
        val message = isoFactory.newMessage()

        val fieldValues = linkedMapOf<Int, String>()

        val overrideMessageType = transLog.MessageTypeOverride?.trim()?.takeIf { it.isNotEmpty() }
        val messageType = (overrideMessageType ?: transactionConfig.messageType)
            .padStart(4, '0')
            .takeLast(4)
        val isSettlementMessage = messageType == SETTLEMENT_MESSAGE_TYPE
        val isBatchUploadMessage = messageType == BATCH_UPLOAD_MESSAGE_TYPE
        message.setMessageType(messageType)
        if (overrideMessageType != null && overrideMessageType != transactionConfig.messageType) {
            Log.d(
                TAG,
                "Using overridden message type $messageType (configured=${transactionConfig.messageType})"
            )
        } else {
            Log.d(TAG, "Using message type $messageType")
        }
        val processingCode = transLog.ProcessingCodeOverride
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: transactionConfig.processingCode
        if (!PROCESSING_CODE_PATTERN.matches(processingCode)) {
            throw HostProtocolException("Invalid processing code")
        }
        message.setFieldValue(3, processingCode)
        fieldValues[3] = processingCode

        transLog.TxnAmt.takeIf { it.isNotBlank() && !isSettlementMessage }?.let {
            val amount = IsoFieldFormatter.amount(it)
            fieldValues[4] = amount
            Log.d(TAG, "Formatting amount '$it' as '$amount'")
            message.setFieldValue(4, amount)
        }

        val transmissionDateTime = IsoFieldFormatter.transmissionDateTime(timestamp)
        val localTime = IsoFieldFormatter.localTime(timestamp)
        val localDate = IsoFieldFormatter.localDate(timestamp)
        val stan = context.stanSupplier()
        val entryMode = EntryModeMapper.from(
            transLog = transLog,
            onlinePinCap = context.terminal.onlinePinCap,
        )
        val nii = IsoFieldFormatter.numeric(context.acquirer.NII, 3)
        val terminalId = IsoFieldFormatter.alphaNumeric(context.acquirer.AcqTermID, 8)
        val merchantId = IsoFieldFormatter.alphaNumeric(context.acquirer.MerchID, 15)
        val currencyCode = IsoFieldFormatter.numeric(context.acquirer.CurrencyCode, 3)

        message.setFieldValue(7, transmissionDateTime)
        message.setFieldValue(11, stan)
        message.setFieldValue(12, localTime)
        message.setFieldValue(13, localDate)
        message.setFieldValue(24, nii)
        message.setFieldValue(41, terminalId)
        message.setFieldValue(42, merchantId)

        fieldValues[7] = transmissionDateTime
        fieldValues[11] = stan
        fieldValues[12] = localTime
        fieldValues[13] = localDate
        fieldValues[24] = nii
        fieldValues[41] = terminalId
        fieldValues[42] = merchantId

        if (!isSettlementMessage) {
            message.setFieldValue(22, entryMode)
            message.setFieldValue(25, DEFAULT_CONDITION_CODE)
            message.setFieldValue(49, currencyCode)

            fieldValues[22] = entryMode
            fieldValues[25] = DEFAULT_CONDITION_CODE
            fieldValues[49] = currencyCode

            val invoiceNumber = IsoFieldFormatter.alphaNumeric(transLog.InvoiceId, 6)
            message.setFieldValue(62, invoiceNumber)
            fieldValues[62] = invoiceNumber

            if (isBatchUploadMessage) {
                buildOriginalMessageData(transLog)?.let { originalData ->
                    if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                        Log.d(
                            TAG,
                            "Resolved original message data for field 060: " +
                                LogSanitizer.sanitizeIsoField(60, originalData)
                        )
                    }
                    message.setFieldValue(60, originalData)
                    fieldValues[60] = originalData
                } ?: run {
                    if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                        Log.d(TAG, "Original message data not available; field 060 will be omitted")
                    }
                }
            }
        } else {
            buildBatchNumber(transLog, context.acquirer)?.let { batchNumber ->
                if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                    Log.d(TAG, "Resolved batch number for field 060: $batchNumber")
                }
                message.setFieldValue(60, batchNumber)
                fieldValues[60] = batchNumber
            } ?: run {
                if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                    Log.d(TAG, "Batch number not available; field 060 will be omitted")
                }
            }
        }

        buildPan(transLog)?.let {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Resolved PAN for field 002: ${LogSanitizer.sanitizeIsoField(2, it)}")
            }
            fieldValues[2] = it
            message.setFieldValue(2, it)
        } ?: run {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "PAN not available; field 002 will be omitted")
            }
        }

        buildExpiry(transLog)?.let {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Resolved expiry date for field 014: ${LogSanitizer.sanitizeIsoField(14, it)}")
            }
            fieldValues[14] = it
            message.setFieldValue(14, it)
        } ?: run {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Expiry date not available; field 014 will be omitted")
            }
        }

        transLog.RefNbr?.trim()?.takeIf { it.isNotEmpty() }?.let { referenceNumber ->
            message.setFieldValue(37, referenceNumber.takeLast(12))
            fieldValues[37] = referenceNumber.takeLast(12)
        }

        transLog.AuthCode?.trim()?.takeIf { it.isNotEmpty() }?.let { authorizationCode ->
            message.setFieldValue(38, authorizationCode.takeLast(6))
            fieldValues[38] = authorizationCode.takeLast(6)
        }

        val trackData = buildTrackData(transLog)
        trackData?.let {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Resolved track data for field 035: ${LogSanitizer.sanitizeIsoField(35, it)}")
            }
            fieldValues[35] = it
            message.setFieldValue(35, it)
        } ?: run {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Track data not available; field 035 will be omitted")
            }
        }

        buildPinBlock(transLog)?.let { pinBlock ->
            fieldValues[52] = pinBlock
            message.setFieldValue(52, pinBlock)
        }

        buildField55(transLog, hasTrackData = trackData != null)?.let { emvData ->
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Resolved EMV data for field 055: ${LogSanitizer.sanitizeIsoField(55, emvData)}")
            }
            fieldValues[55] = emvData
            message.setFieldValue(55, emvData)
        } ?: run {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "EMV data not available; field 055 will be omitted")
            }
        }

        buildField63(transLog, context.acquirer, messageType)?.let { privateData ->
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Resolved private data for field 063: ${LogSanitizer.sanitizeIsoField(63, privateData)}")
            }
            fieldValues[63] = privateData
            message.setFieldValue(63, privateData)
        } ?: run {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Private use data not available; field 063 will be omitted")
            }
        }

        IsoMessageDebugLogger.logConfiguredFields(TAG, "ISSWITCH request", fieldValues)
        IsoMessageDebugLogger.logMessage(TAG, "ISSWITCH request", message)

        message
    } catch (error: Iso8583Exception) {
        Log.e(TAG, "Unable to build ISSWITCH ISO8583 message", error)
        throw HostProtocolException("Unable to build ISSWITCH ISO8583 message", error)
    }

    private fun buildPan(transLog: TransLog): String? {
        val pan = when {
            !transLog.PAN.isNullOrBlank() -> transLog.PAN
            transLog.CardNbr.isNotBlank() -> transLog.CardNbr
            else -> null
        }
        val filtered = pan?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        if (filtered == null && BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
            Log.d(TAG, "PAN value discarded because it does not contain digits")
        }
        return filtered
    }

    private fun buildExpiry(transLog: TransLog): String? {
        val year = transLog.ExpireYear.takeIf { !it.isNullOrBlank() }?.takeLast(2)
        val month = transLog.ExpireMonth.takeIf { it.isNotBlank() }?.padStart(2, '0')
        return if (!year.isNullOrBlank() && !month.isNullOrBlank()) {
            year + month
        } else {
            Log.d(TAG, "Unable to derive expiry date from month='${transLog.ExpireMonth}' year='${transLog.ExpireYear}'")
            null
        }
    }

    private fun buildTrackData(transLog: TransLog): String? {
        val rawTrack = sequenceOf(transLog.Track2, transLog.Track1, transLog.Track3)
            .mapNotNull { value -> value?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?: return null

        val trimmed = rawTrack.trim()
        val withoutSentinels = trimmed
            .trimStart(';', '%', 'B')
            .trimEnd('?', ';')
        val normalized = WHITESPACE_PATTERN.replace(withoutSentinels, "")
        if (normalized.isEmpty()) {
            return null
        }

        val hexadecimal = normalized
            .uppercase(Locale.US)
            .replace('=', 'D')

        return hexadecimal.takeIf { it.isNotEmpty() }
    }

    private fun buildField55(transLog: TransLog, hasTrackData: Boolean): String? {
        val raw = transLog.Field55?.takeIf { !it.isNullOrBlank() } ?: return null
        val normalized = WHITESPACE_PATTERN.replace(raw, "").uppercase(Locale.US)
        if (normalized.length % 2 != 0) {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Discarding EMV data for field 055 due to odd length: ${normalized.length}")
            }
            return null
        }

        if (!HEX_PATTERN.matches(normalized)) {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Discarding EMV data for field 055 due to non-hex characters")
            }
            return null
        }

        val sanitized = if (hasTrackData) removeTlvTag(normalized, "57") else normalized
        return sanitized.takeIf { it.isNotEmpty() }
    }

    private fun buildPinBlock(transLog: TransLog): String? {
        val normalized = transLog.PINBlock
            ?.filterNot(Char::isWhitespace)
            ?.uppercase(Locale.US)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (!PIN_BLOCK_PATTERN.matches(normalized)) {
            throw HostProtocolException("Invalid encrypted PIN block")
        }
        return normalized
    }

    private fun removeTlvTag(tlv: String, targetTag: String): String {
        if (tlv.isEmpty()) return tlv

        val normalizedTarget = targetTag.uppercase(Locale.US)
        val builder = StringBuilder(tlv.length)
        var index = 0

        while (index < tlv.length) {
            val tagStart = index
            if (index + 2 > tlv.length) return tlv

            var tagEnd = index + 2
            var tag = tlv.substring(index, tagEnd)
            val firstByte = tag.toIntOrNull(16) ?: return tlv

            if ((firstByte and 0x1F) == 0x1F) {
                while (true) {
                    if (tagEnd + 2 > tlv.length) return tlv
                    val nextByteText = tlv.substring(tagEnd, tagEnd + 2)
                    val nextByte = nextByteText.toIntOrNull(16) ?: return tlv
                    tag += nextByteText
                    tagEnd += 2
                    if ((nextByte and 0x80) != 0x80) {
                        break
                    }
                }
            }

            index = tagEnd
            if (index + 2 > tlv.length) return tlv

            var lengthByteValue = tlv.substring(index, index + 2).toIntOrNull(16) ?: return tlv
            index += 2

            var lengthValue = lengthByteValue
            if ((lengthByteValue and 0x80) == 0x80) {
                val numberOfBytes = lengthByteValue and 0x7F
                if (numberOfBytes <= 0 || index + numberOfBytes * 2 > tlv.length) {
                    return tlv
                }

                lengthValue = 0
                repeat(numberOfBytes) {
                    val component = tlv.substring(index, index + 2).toIntOrNull(16) ?: return tlv
                    index += 2
                    lengthValue = (lengthValue shl 8) or component
                }
            }

            val valueLength = lengthValue * 2
            if (valueLength < 0 || index + valueLength > tlv.length) return tlv

            index += valueLength

            if (!tag.equals(normalizedTarget, ignoreCase = true)) {
                builder.append(tlv, tagStart, index)
            }
        }

        return builder.toString()
    }

    private fun buildField63(transLog: TransLog, acquirer: TMS_Acquirer, messageType: String): String? {
        return when (messageType) {
            SETTLEMENT_MESSAGE_TYPE -> buildSettlementField63(transLog)
            else -> buildStandardField63(transLog, acquirer)
        }
    }

    private fun buildBatchNumber(transLog: TransLog, acquirer: TMS_Acquirer): String? {
        val candidate = sequenceOf(
            transLog.BatchSeqNbr,
            transLog.BatchId,
            acquirer.InitBatchNo.takeIf { it > 0 }?.toString()
        ).mapNotNull(::parseBatchNumberCandidate).firstOrNull()

        val acquirerIdentifier = resolveAcquirerIdentifier(transLog, acquirer)

        if (acquirerIdentifier != null) {
            val batchNumber = BatchNumberProvider.currentBatchNumber(
                acquirerIdentifier,
                candidate?.numeric
            )
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(
                    TAG,
                    "Using persisted batch number for acquirer=$acquirerIdentifier: $batchNumber"
                )
            }
            return batchNumber
        }

        return candidate?.formatted
    }

    private fun resolveAcquirerIdentifier(transLog: TransLog, acquirer: TMS_Acquirer): String? {
        if (transLog.AcquirerId.isNotBlank()) {
            return transLog.AcquirerId
        }
        val acquirerId: String? = acquirer.AcqID
        return acquirerId?.takeIf { it.isNotBlank() }
    }

    private fun parseBatchNumberCandidate(value: String?): BatchNumberCandidate? {
        val digits = value?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = digits.takeLast(6)
        val formatted = normalized.padStart(6, '0')
        val numeric = formatted.toIntOrNull() ?: return null
        return BatchNumberCandidate(formatted, numeric)
    }

    private data class BatchNumberCandidate(val formatted: String, val numeric: Int)

    private fun buildOriginalMessageData(transLog: TransLog): String? {
        val originalMessageType = transLog.OriginalMessageType
            ?.filter { it.isDigit() }
            ?.takeIf { it.isNotEmpty() }
        if (originalMessageType == null) {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Original message type not available for field 060")
            }
            return null
        }

        val originalStan = transLog.OriginalStan
            ?.filter { it.isDigit() }
            ?.takeIf { it.isNotEmpty() }
        if (originalStan == null) {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Original STAN not available for field 060")
            }
            return null
        }

        val normalizedMti = originalMessageType.padStart(4, '0').takeLast(4)
        val normalizedStan = originalStan.padStart(6, '0').takeLast(6)

        return (normalizedMti + normalizedStan).takeIf { it.isNotBlank() }
    }

    private fun buildSettlementField63(transLog: TransLog): String? {
        val totals = transLog.SettlementTotals
        if (totals == null) {
            if (BuildConfig.ENABLE_ISO8583_DEBUG_LOGS) {
                Log.d(TAG, "Settlement totals not available; field 063 will be omitted")
            }
            return null
        }
        val payload = totals.toAsciiPayload()
        if (payload.isEmpty()) {
            return null
        }
        val bytes = payload.toByteArray(StandardCharsets.US_ASCII)
        return IsoHexUtils.encodeHex(bytes, 0, bytes.size)
    }

    private fun buildStandardField63(transLog: TransLog, acquirer: TMS_Acquirer): String? {
        val tags = mutableListOf<Tag>()

        transLog.FolioNumber?.takeIf { it.isNotBlank() }?.let { tags += Tag("14", it.trim()) }
        transLog.CVV.takeIf { it.isNotBlank() }?.let { tags += Tag("16", it.trim()) }
        tags.addAmountTag("39", transLog.Tax1Amt)
        tags.addAmountTag("40", transLog.Tax2Amt)
        tags.addAmountTag("41", transLog.CashbackAmt)
        transLog.PaymentPlan.takeIf { !it.isNullOrBlank() }?.let { tags += Tag("45", it.trim()) }
        if (!transLog.PINBlock.isNullOrBlank()) {
            transLog.KSN
                ?.filterNot(Char::isWhitespace)
                ?.uppercase(Locale.US)
                ?.takeIf(KSN_PATTERN::matches)
                ?.let { tags += Tag("33", it) }
        }
        transLog.OriginalTax1Amt.takeIf { it.isNotBlank() }?.let { tags.addAmountTag("82", it) }
        if (acquirer.SendAqEntryCap && acquirer.Acq_Entry_Cap.isNotBlank()) {
            tags += Tag("1C", acquirer.Acq_Entry_Cap.trim())
        }

        return PrivateUseData63.encode(tags)
    }

    private fun MutableList<Tag>.addAmountTag(tagId: String, amount: String) {
        val digits = IsoFieldFormatter.amount(amount)
        if (digits.any { it != '0' }) {
            add(Tag(tagId, digits))
        }
    }

    companion object {
        private const val TAG = "IsswitchProtocol"
        private const val CONFIG_ASSET = "iso8583_ISSWITCH_config.xml"
        private const val DEFAULT_CONDITION_CODE = "00"
        private const val SETTLEMENT_MESSAGE_TYPE = "0500"
        private const val BATCH_UPLOAD_MESSAGE_TYPE = "0320"
        private val WHITESPACE_PATTERN = Regex("\\s+")
        private val HEX_PATTERN = Regex("[0-9A-F]+")
        private val PIN_BLOCK_PATTERN = Regex("[0-9A-F]{16}")
        private val KSN_PATTERN = Regex("[0-9A-F]{12,20}")
        private val PROCESSING_CODE_PATTERN = Regex("[0-9]{6}")
    }
}
