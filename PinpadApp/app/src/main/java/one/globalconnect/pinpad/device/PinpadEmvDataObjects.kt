package one.globalconnect.pinpad.device

import java.nio.charset.StandardCharsets
import java.util.Locale

object PinpadEmvDataObjects {
    private const val SUB = '\u001A'
    private const val FS = '\u001C'
    private const val LEGACY_LONG_PRIVATE_TAG_BYTES = 4

    fun parsePacket(
        payload: String,
        hasAidInFirstPacket: Boolean = false,
        inferredPacketNo: Int? = null,
        inferredTotalPackets: Int? = null,
    ): EmvConfigPacket? {
        if (payload.length < 2) return null
        val hasPacketHeader = payload[0].digitToIntOrNull() != null && payload[1].digitToIntOrNull() != null
        val packetNo = if (hasPacketHeader) {
            payload[0].digitToIntOrNull() ?: return null
        } else {
            inferredPacketNo ?: return null
        }
        val totalPackets = if (hasPacketHeader) {
            payload[1].digitToIntOrNull() ?: return null
        } else {
            inferredTotalPackets ?: return null
        }
        val body = if (hasPacketHeader) payload.drop(2).trimStart(SUB) else payload.trimStart(SUB)
        val chunks = splitSub(body)
        val aid = if (hasAidInFirstPacket && packetNo == 1) {
            chunks.firstOrNull()?.takeIf { it.isHexString() }
        } else {
            null
        }
        val objectChunks = if (aid != null) chunks.drop(1) else chunks
        return EmvConfigPacket(
            packetNo = packetNo,
            totalPackets = totalPackets,
            aid = aid?.uppercase(Locale.US),
            tlvHex = encodeDataObjects(objectChunks),
            rawObjects = objectChunks,
        )
    }

    fun parsePcdApplicationPacket(
        payload: String,
        inferredPacketNo: Int? = null,
        inferredTotalPackets: Int? = null,
    ): PcdConfigPacket? {
        if (payload.length < 2) return null
        val hasPacketHeader = payload[0].digitToIntOrNull() != null && payload[1].digitToIntOrNull() != null
        val packetNo = if (hasPacketHeader) {
            payload[0].digitToIntOrNull() ?: return null
        } else {
            inferredPacketNo ?: return null
        }
        val totalPackets = if (hasPacketHeader) {
            payload[1].digitToIntOrNull() ?: return null
        } else {
            inferredTotalPackets ?: return null
        }
        val body = if (hasPacketHeader) payload.drop(2).trimStart(SUB) else payload.trimStart(SUB)
        val chunks = splitSub(body)
        val txn: String?
        val kernelId: String?
        val aid: String?
        val objectChunks: List<String>
        if (packetNo == 1) {
            if (chunks.size < 4) return null
            txn = chunks[0].takeIf { it.isHexString() }?.uppercase(Locale.US) ?: return null
            kernelId = chunks[1].takeIf { it.isHexString() }?.uppercase(Locale.US) ?: return null
            aid = chunks[2].takeIf { it.isHexString() }?.uppercase(Locale.US) ?: return null
            objectChunks = chunks.drop(3)
        } else {
            txn = null
            kernelId = null
            aid = null
            objectChunks = chunks
        }
        return PcdConfigPacket(
            packetNo = packetNo,
            totalPackets = totalPackets,
            txn = txn,
            kernelId = kernelId,
            aid = aid,
            tlvHex = encodeDataObjects(objectChunks),
            rawObjects = objectChunks,
        )
    }

    fun parsePcdDrlPacket(payload: String): PcdDrlPacket? {
        val chunks = splitSub(payload.trimStart(SUB))
        if (chunks.size < 2) return null
        val aid = chunks[0].takeIf { it.isHexString() }?.uppercase(Locale.US) ?: return null
        val tlv = encodeDataObjects(chunks.drop(1)) ?: return null
        return PcdDrlPacket(aid = aid, tlvHex = tlv)
    }

    fun parseRuntimeDataObjects(payload: String): String? {
        val body = payload.trimStart(SUB)
        return encodeDataObjects(splitSub(body))
    }

    fun parseDataFormatTable(payload: String): Map<String, String>? {
        return parseDataFormatDefinitions(payload)?.mapValues { (_, definition) -> definition.rule }
    }

    fun parseDataFormatDefinitions(payload: String): Map<String, EmvDataFormatDefinition>? {
        val body = payload.drop(1).trimStart(SUB)
        if (body.isBlank()) return emptyMap()
        val result = linkedMapOf<String, EmvDataFormatDefinition>()
        for (row in splitSub(body)) {
            val parsed = parseFormatRule(row) ?: return null
            result[parsed.tag] = parsed
        }
        return result
    }

    fun tlvToDataObjectText(tlvHex: String, requestedTags: List<String>): String {
        val records = parseTlvRecords(tlvHex)
        val selected = if (requestedTags.isEmpty()) {
            records
        } else {
            val recordsByTag = records.associateBy { it.tag }
            requestedTags.mapNotNull { recordsByTag[it.uppercase(Locale.US)] }
        }
        return selected.joinToString(separator = SUB.toString()) { record ->
            "${record.tag}$FS${"%02X".format(record.value.size)}$FS${record.value.toHex()}"
        }
    }

    fun parseRequestedTags(payload: String): List<String> {
        return splitSub(payload).map { it.trim().uppercase(Locale.US) }.filter { it.isHexString() }
    }

    fun encodeTlv(tagHex: String, valueHex: String): String? {
        if (!tagHex.isHexString() || !valueHex.isHexString() || valueHex.length % 2 != 0) return null
        val value = valueHex.hexToBytesOrNull() ?: return null
        return tagHex.uppercase(Locale.US) + encodeLength(value.size).toHex() + value.toHex()
    }

    fun parseTlvRecords(tlvHex: String): List<TlvRecord> {
        val bytes = tlvHex.hexToBytesOrNull() ?: return emptyList()
        val records = mutableListOf<TlvRecord>()
        var offset = 0
        while (offset < bytes.size) {
            val tagStart = offset
            if (isLegacyLongPrivateTag(bytes, offset)) {
                offset += LEGACY_LONG_PRIVATE_TAG_BYTES
            } else {
                offset++
            }
            if (offset > bytes.size) break
            if (offset == tagStart + 1 && (bytes[tagStart].toInt() and 0x1F) == 0x1F) {
                while (offset < bytes.size) {
                    val current = bytes[offset++].toInt() and 0xFF
                    if ((current and 0x80) == 0) break
                }
            }
            val tagEnd = offset
            if (offset >= bytes.size) break
            val lengthFirst = bytes[offset++].toInt() and 0xFF
            val length = if ((lengthFirst and 0x80) == 0) {
                lengthFirst
            } else {
                val count = lengthFirst and 0x7F
                if (count == 0 || offset + count > bytes.size) break
                var value = 0
                repeat(count) {
                    value = (value shl 8) or (bytes[offset++].toInt() and 0xFF)
                }
                value
            }
            if (offset + length > bytes.size) break
            val tag = bytes.copyOfRange(tagStart, tagEnd).toHex()
            records += TlvRecord(tag, bytes.copyOfRange(offset, offset + length))
            offset += length
        }
        return records
    }

    private fun isLegacyLongPrivateTag(bytes: ByteArray, offset: Int): Boolean {
        return offset + LEGACY_LONG_PRIVATE_TAG_BYTES <= bytes.size &&
            bytes[offset] == 0x50.toByte() &&
            bytes[offset + 1] == 0x00.toByte() &&
            bytes[offset + 2] == 0x00.toByte()
    }

    fun filterTlvRecords(tlvHex: String, exclude: (String) -> Boolean): String? {
        val normalized = tlvHex.replace(" ", "").uppercase(Locale.US)
        if (normalized.isBlank()) return ""
        if (!normalized.isHexString()) return null
        val records = parseTlvRecords(normalized)
        if (records.isEmpty()) return null
        return records
            .filterNot { exclude(it.tag) }
            .joinToString(separator = "") { record ->
                encodeTlv(record.tag, record.value.toHex()).orEmpty()
            }
    }

    fun tlvRecordValueMap(tlvHex: String, include: (String) -> Boolean): Map<String, String>? {
        val normalized = tlvHex.replace(" ", "").uppercase(Locale.US)
        if (normalized.isBlank()) return emptyMap()
        if (!normalized.isHexString()) return null
        val records = parseTlvRecords(normalized)
        if (records.isEmpty()) return null
        return records
            .filter { include(it.tag) }
            .associate { record -> record.tag to record.value.toHex() }
    }

    fun findEncodedTlvValue(tlvHex: String, tagHex: String): ByteArray? {
        val normalizedTlv = tlvHex.replace(" ", "").uppercase(Locale.US)
        val normalizedTag = tagHex.replace(" ", "").uppercase(Locale.US)
        if (!normalizedTlv.isHexString() || !normalizedTag.isHexString()) return null
        var searchFrom = 0
        while (searchFrom <= normalizedTlv.length - normalizedTag.length - 2) {
            val tagIndex = normalizedTlv.indexOf(normalizedTag, searchFrom)
            if (tagIndex < 0) return null
            val lengthOffset = tagIndex + normalizedTag.length
            val decodedLength = decodeHexLength(normalizedTlv, lengthOffset)
            if (decodedLength != null) {
                val valueOffset = decodedLength.nextOffset
                val valueEnd = valueOffset + decodedLength.length * 2
                if (valueEnd <= normalizedTlv.length) {
                    return normalizedTlv.substring(valueOffset, valueEnd).hexToBytesOrNull()
                }
            }
            searchFrom = tagIndex + 2
        }
        return null
    }

    private fun encodeDataObjects(rows: List<String>): String? {
        val builder = StringBuilder()
        for (row in rows) {
            val dataObject = parseDataObject(row) ?: return null
            builder.append(encodeTlv(dataObject.tag, dataObject.valueHex) ?: return null)
        }
        return builder.toString()
    }

    private fun parseDataObject(row: String): DataObject? {
        val fields = if (row.contains(FS)) {
            row.split(FS)
        } else {
            row.trim().split(Regex("\\s+"), limit = 3)
        }
        if (fields.size < 3) return null
        val tag = fields[0].trim().uppercase(Locale.US)
        val format = fields[1].trim().uppercase(Locale.US)
        val value = fields[2].trim()
        if (!tag.isHexString()) return null
        val valueHex = when (normalizeFormat(format)) {
            DataFormat.Binary,
            DataFormat.CompressedNumeric,
            DataFormat.Numeric,
            DataFormat.Variable,
            -> value.normalizeHexValue()
            DataFormat.Ascii -> value.toByteArray(StandardCharsets.ISO_8859_1).toHex()
        }
        return valueHex?.let { DataObject(tag, it) }
    }

    fun parseFormatRule(row: String): EmvDataFormatDefinition? {
        val fields = if (row.contains(FS)) {
            row.split(FS)
        } else {
            row.trim().split(Regex("\\s+"))
        }
        if (fields.size < 2) return null
        val tag = fields[0].trim().uppercase(Locale.US)
        if (!tag.isHexString()) return null
        val rule = if (fields.size >= 5) {
            val format = formatCode(fields[1].trim())
            val min = fields[2].trim().padStart(2, '0')
            val max = fields[3].trim().padStart(2, '0')
            val flag = fields[4].trim()
            "$format$min$max$flag"
        } else {
            fields[1].trim().replace(" ", "").uppercase(Locale.US)
        }
        return parseDataFormatDefinition(tag, rule)
    }

    fun parseDataFormatDefinition(tag: String, rule: String): EmvDataFormatDefinition? {
        val normalizedTag = tag.trim().uppercase(Locale.US)
        val normalizedRule = rule.trim().replace(" ", "").uppercase(Locale.US)
        if (!normalizedTag.isHexString() || normalizedRule.length < 6) return null
        val formatCode = formatCode(normalizedRule.take(1))
        val minLength = normalizedRule.substring(1, 3).toIntOrNull() ?: return null
        val maxLength = normalizedRule.substring(3, 5).toIntOrNull() ?: return null
        val lengthFlag = normalizedRule.substring(5, 6)
        if (minLength > maxLength) return null
        return EmvDataFormatDefinition(
            tag = normalizedTag,
            formatCode = formatCode,
            format = formatName(formatCode),
            minLength = minLength,
            maxLength = maxLength,
            lengthFlag = lengthFlag,
            isVariableLength = lengthFlag != "0" || minLength != maxLength,
            rule = "$formatCode${"%02d".format(minLength)}${"%02d".format(maxLength)}$lengthFlag",
        )
    }

    private fun normalizeFormat(value: String): DataFormat {
        return when (formatCode(value)) {
            "2", "B" -> DataFormat.Binary
            "5", "CN" -> DataFormat.CompressedNumeric
            "6", "N" -> DataFormat.Numeric
            "7", "VAR" -> DataFormat.Variable
            else -> DataFormat.Ascii
        }
    }

    private fun String.normalizeHexValue(): String? {
        val normalized = replace(" ", "").uppercase(Locale.US)
        if (!normalized.isHexString()) return null
        return if (normalized.length % 2 == 0) normalized else "0$normalized"
    }

    private fun splitSub(value: String): List<String> {
        return value.split(SUB).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("NOTE:", true) }
    }

    private fun String.isHexString(): Boolean {
        return isNotEmpty() && all { it.digitToIntOrNull(16) != null }
    }

    fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || !isHexString()) return null
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it) }

    private fun encodeLength(length: Int): ByteArray {
        return when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        }
    }

    private fun decodeHexLength(hex: String, offset: Int): DecodedLength? {
        if (offset + 2 > hex.length) return null
        val first = hex.substring(offset, offset + 2).toIntOrNull(16) ?: return null
        if ((first and 0x80) == 0) return DecodedLength(first, offset + 2)
        val count = first and 0x7F
        if (count == 0 || count > 2 || offset + 2 + count * 2 > hex.length) return null
        var length = 0
        var cursor = offset + 2
        repeat(count) {
            val next = hex.substring(cursor, cursor + 2).toIntOrNull(16) ?: return null
            length = (length shl 8) or next
            cursor += 2
        }
        return DecodedLength(length, cursor)
    }

    data class EmvConfigPacket(
        val packetNo: Int,
        val totalPackets: Int,
        val aid: String?,
        val tlvHex: String?,
        val rawObjects: List<String>,
    ) {
        val isFinal: Boolean = packetNo == totalPackets
    }

    data class PcdConfigPacket(
        val packetNo: Int,
        val totalPackets: Int,
        val txn: String?,
        val kernelId: String?,
        val aid: String?,
        val tlvHex: String?,
        val rawObjects: List<String>,
    ) {
        val isFinal: Boolean = packetNo == totalPackets
    }

    data class PcdDrlPacket(
        val aid: String,
        val tlvHex: String,
    )

    data class TlvRecord(val tag: String, val value: ByteArray)

    data class EmvDataFormatDefinition(
        val tag: String,
        val formatCode: String,
        val format: String,
        val minLength: Int,
        val maxLength: Int,
        val lengthFlag: String,
        val isVariableLength: Boolean,
        val rule: String,
    )

    private data class DataObject(val tag: String, val valueHex: String)

    private data class DecodedLength(val length: Int, val nextOffset: Int)

    private fun formatCode(value: String): String {
        return when (value.trim().uppercase(Locale.US)) {
            "B" -> "2"
            "A" -> "4"
            "AN" -> "4"
            "CN" -> "5"
            "N" -> "6"
            "VAR" -> "7"
            else -> value.trim().uppercase(Locale.US).take(1)
        }
    }

    private fun formatName(formatCode: String): String {
        return when (formatCode) {
            "2" -> "B"
            "4" -> "A"
            "5" -> "CN"
            "6" -> "N"
            "7" -> "VAR"
            else -> formatCode
        }
    }

    private enum class DataFormat {
        Ascii,
        Binary,
        CompressedNumeric,
        Numeric,
        Variable,
    }
}
