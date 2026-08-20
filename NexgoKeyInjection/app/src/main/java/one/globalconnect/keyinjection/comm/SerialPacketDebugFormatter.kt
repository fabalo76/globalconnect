package one.globalconnect.keyinjection.comm

import kotlin.text.Charsets

/** Produces debug-only serial traces without exposing injected key material. */
internal object SerialPacketDebugFormatter {
    fun format(
        direction: String,
        framing: String,
        start: Byte,
        end: Byte,
        payload: ByteArray,
        lrc: Byte
    ): String {
        val payloadDescription = describePayload(direction, payload)
        return "$direction $framing frame: start=${hex(start)} payload=$payloadDescription " +
            "end=${hex(end)} lrc=${hex(lrc)} (${payload.size} payload bytes)"
    }

    private fun describePayload(direction: String, payload: ByteArray): String {
        val text = payload.toString(Charsets.US_ASCII)
        if (direction == "RX") {
            when (text.take(2)) {
                "00" -> return redactLegacyKey(text, metadataLength = 24, label = "DUKPT_KEY")
                "01" -> return redactLegacyKey(text, metadataLength = 4, label = "MASTER_KEY")
                "02" -> return redactCommand02(text)
            }
        }

        return if (text.all { it.code in 0x20..0x7E }) {
            "ASCII=\"$text\" HEX=${payload.toHex()}"
        } else {
            "HEX=${payload.toHex()}"
        }
    }

    private fun redactLegacyKey(text: String, metadataLength: Int, label: String): String {
        if (text.length < metadataLength)
            return "ASCII=\"<MALFORMED_${label}_COMMAND_REDACTED:${text.length}>\""
        val secretLength = text.length - metadataLength
        return "ASCII=\"${text.take(metadataLength)}<${label}_REDACTED:$secretLength>\""
    }

    private fun redactCommand02(text: String): String {
        val fixedLength = 43
        val lengthFieldSize = 3
        if (text.length < fixedLength + lengthFieldSize)
            return "ASCII=\"<MALFORMED_COMMAND_02_REDACTED:${text.length}>\""

        val keyLength = text.substring(40, 43).toIntOrNull(16)
            ?: return "ASCII=\"<MALFORMED_COMMAND_02_REDACTED:${text.length}>\""
        val keyEnd = fixedLength + keyLength
        if (keyEnd + lengthFieldSize > text.length)
            return "ASCII=\"${text.take(12)}<COMMAND_02_PAYLOAD_REDACTED:${text.length - 12}>\""

        val ktkLengthField = text.substring(keyEnd, keyEnd + lengthFieldSize)
        val ktkLength = ktkLengthField.toIntOrNull(16)
            ?: return "ASCII=\"${text.take(12)}<COMMAND_02_PAYLOAD_REDACTED:${text.length - 12}>\""
        if (keyEnd + lengthFieldSize + ktkLength != text.length)
            return "ASCII=\"${text.take(12)}<COMMAND_02_PAYLOAD_REDACTED:${text.length - 12}>\""

        return buildString {
            append("ASCII=\"")
            append(text.take(fixedLength))
            append("<KEY_REDACTED:")
            append(keyLength)
            append('>')
            append(ktkLengthField)
            if (ktkLength > 0) {
                append("<KTK_REDACTED:")
                append(ktkLength)
                append('>')
            }
            append('"')
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { hex(it) }

    private fun hex(value: Byte): String = "0x%02X".format(value.toInt() and 0xFF)
}
