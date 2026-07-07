package com.uic.pinpad.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class PINPADFrameCodec {
    fun encode(frame: PINPADFrame): ByteArray {
        val body = frame.commandId.toByteArray(StandardCharsets.US_ASCII) + frame.payload
        val withoutLrc = ByteArrayOutputStream().apply {
            write(frame.frameType.start.toInt())
            write(body)
            write(frame.frameType.end.toInt())
        }.toByteArray()
        return withoutLrc + lrc(withoutLrc, startIndex = 1)
    }

    fun decode(candidate: ByteArray): DecodeResult {
        if (candidate.size < MIN_FRAME_SIZE) return DecodeResult.Incomplete
        val frameType = PINPADFrameType.fromStart(candidate.first()) ?: return DecodeResult.Invalid
        if (candidate[candidate.lastIndex - 1] != frameType.end) return DecodeResult.Invalid
        val actual = candidate.last()
        val expected = lrc(candidate.copyOfRange(0, candidate.lastIndex), startIndex = 1)
        if (actual != expected) return DecodeResult.BadLrc

        val content = candidate.copyOfRange(1, candidate.lastIndex - 1)
        val commandLength = commandLength(content)
        if (content.size < commandLength) return DecodeResult.Invalid

        val command = content.copyOfRange(0, commandLength).toString(StandardCharsets.US_ASCII)
        val payload = content.copyOfRange(commandLength, content.size)
        return DecodeResult.Valid(PINPADFrame(frameType, command, payload))
    }

    private fun lrc(bytes: ByteArray, startIndex: Int): Byte {
        var result = 0
        for (index in startIndex until bytes.size) {
            result = result xor (bytes[index].toInt() and 0xFF)
        }
        return result.toByte()
    }

    sealed class DecodeResult {
        data class Valid(val frame: PINPADFrame) : DecodeResult()
        data object BadLrc : DecodeResult()
        data object Invalid : DecodeResult()
        data object Incomplete : DecodeResult()
    }

    companion object {
        private const val MIN_FRAME_SIZE = 5
        private val THREE_CHARACTER_COMMANDS = setOf(
            "Z42",
            "Z43",
            "Z50",
            "Z51",
            "Z60",
            "Z62",
            "Z64",
            "Z65",
            "Z66",
            "Z67",
        )

        private fun commandLength(content: ByteArray): Int {
            val first = content.firstOrNull()
            if (first == 'T'.code.toByte() || first == 'M'.code.toByte() || first == 'I'.code.toByte()) return 3
            if (content.size >= 3) {
                val prefix = content.copyOfRange(0, 3).toString(StandardCharsets.US_ASCII)
                if (prefix in THREE_CHARACTER_COMMANDS) return 3
            }
            return 2
        }
    }
}
