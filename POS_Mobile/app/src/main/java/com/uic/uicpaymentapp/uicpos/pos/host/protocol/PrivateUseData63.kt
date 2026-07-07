package com.uic.uicpaymentapp.uicpos.pos.host.protocol

import com.uic.pos.iso8583.IsoLengthType
import com.uic.pos.iso8583.util.IsoByteUtils
import com.uic.pos.iso8583.util.IsoHexUtils
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * Helper utilities to encode and decode ISO8583 field 63 (private use data) for
 * the ISSSwitch host protocol. The field is transported as a sequence of
 * Length/Tag/Value elements where the length indicator is a packed BCD value
 * describing the number of bytes that follow (tag identifier plus value).
 */
object PrivateUseData63 {

    data class Tag(val id: String, val value: String)

    /**
     * Encodes the supplied [tags] into a hexadecimal string suitable for
     * ISO8583 field 63. Empty or blank values are ignored.
     */
    fun encode(tags: List<Tag>): String? {
        if (tags.isEmpty()) return null

        val output = ByteArrayOutputStream()
        tags.forEach { tag ->
            val tagId = tag.id.trim()
            val tagValue = tag.value.trim()
            if (tagId.length != 2 || tagValue.isEmpty()) {
                return@forEach
            }

            val tagBytes = tagId.toByteArray(StandardCharsets.US_ASCII)
            val valueBytes = tagValue.toByteArray(StandardCharsets.US_ASCII)
            val length = tagBytes.size + valueBytes.size
            if (length <= tagBytes.size) {
                return@forEach
            }

            val lengthPrefix = IsoByteUtils.intToBytes(length, IsoLengthType.BCD, 2)
            output.write(lengthPrefix)
            output.write(tagBytes)
            output.write(valueBytes)
        }

        val data = output.toByteArray()
        return if (data.isEmpty()) null else IsoHexUtils.encodeHex(data, 0, data.size)
    }

    /**
     * Parses a hexadecimal [payload] that follows the ISSSwitch LTV format and
     * returns a mapping of tag identifiers to their ASCII values. Invalid or
     * truncated elements are ignored.
     */
    fun parse(payload: String?): Map<String, String> {
        if (payload.isNullOrBlank()) return emptyMap()
        val bytes = try {
            IsoHexUtils.decodeHex(payload.trim())
        } catch (error: IllegalArgumentException) {
            return emptyMap()
        }

        val tags = mutableMapOf<String, String>()
        var index = 0
        while (index + 2 <= bytes.size) {
            val lengthBytes = Arrays.copyOfRange(bytes, index, index + 2)
            val declaredLength = IsoByteUtils.bcdToInt(lengthBytes)
            index += 2
            if (declaredLength < 2 || index + declaredLength > bytes.size) {
                break
            }

            val tagBytes = Arrays.copyOfRange(bytes, index, index + 2)
            index += 2
            val valueLength = declaredLength - tagBytes.size
            if (valueLength < 0 || index + valueLength > bytes.size) {
                break
            }

            val valueBytes = Arrays.copyOfRange(bytes, index, index + valueLength)
            index += valueLength

            val tagId = String(tagBytes, StandardCharsets.US_ASCII).trim()
            val value = String(valueBytes, StandardCharsets.US_ASCII)
            if (tagId.isNotEmpty()) {
                tags[tagId] = value
            }
        }

        return tags
    }
}
