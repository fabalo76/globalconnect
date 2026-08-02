package one.globalconnect.pinpad.logging

import android.util.Log
import one.globalconnect.pinpad.BuildConfig
import one.globalconnect.pinpad.protocol.PINPADControl
import one.globalconnect.pinpad.protocol.PINPADFrameType
import java.nio.charset.StandardCharsets

object PinpadTraceLog {
    private const val TAG = "PINPADTrace"
    private const val MAX_BYTES = 512
    private val THREE_CHARACTER_COMMANDS = setOf(
        "PH1",
        "PH2",
        "QR1",
        "QR2",
        "QR3",
        "QR4",
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

    fun serialRx(source: String, bytes: ByteArray) {
        serialBytes("RX", source, bytes)
    }

    fun serialTx(source: String, bytes: ByteArray) {
        serialBytes("TX", source, bytes)
    }

    fun transport(message: String) {
        detail("TRANSPORT $message")
    }

    fun service(message: String) {
        detail("SERVICE $message")
    }

    fun parser(message: String) {
        detail("PARSER $message")
    }

    fun protocol(message: String) {
        detail("PROTOCOL $message")
    }

    fun command(commandId: String, message: String) {
        detail("COMMAND $commandId $message")
    }

    fun device(message: String) {
        log("DEVICE $message")
    }

    fun controlName(value: Byte): String {
        return when (value) {
            PINPADControl.STX -> "STX"
            PINPADControl.ETX -> "ETX"
            PINPADControl.ACK -> "ACK"
            PINPADControl.SO -> "SO"
            PINPADControl.SI -> "SI"
            PINPADControl.NAK -> "NAK"
            PINPADControl.SUB -> "SUB"
            PINPADControl.FS -> "FS"
            PINPADControl.GS -> "GS"
            PINPADControl.EOT -> "EOT"
            else -> "0x${byteToHex(value)}"
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        val shown = bytes.take(MAX_BYTES).joinToString(" ") { byteToHex(it) }
        return if (bytes.size > MAX_BYTES) {
            "$shown ...(+${bytes.size - MAX_BYTES} bytes)"
        } else {
            shown
        }
    }

    fun diagnosticWireSummary(direction: String, bytes: ByteArray): String =
        wireSummary(direction, bytes)

    private fun serialBytes(direction: String, source: String, bytes: ByteArray) {
        log("$direction[$source] ${wireSummary(direction, bytes)} len=${bytes.size} hex=${bytesToHex(bytes)} ascii=${bytesToAscii(bytes)}")
    }

    private fun wireSummary(direction: String, bytes: ByteArray): String {
        if (bytes.size == 1) {
            wireControlName(bytes.first())?.let { return it }
        }
        val controls = bytes.asSequence().mapNotNull(::wireControlName).toList()
        if (controls.size == bytes.size && controls.isNotEmpty()) {
            return controls.joinToString(separator = " ")
        }

        val commands = frameCommands(bytes)
        if (commands.isNotEmpty()) {
            val prefix = if (direction == "RX") "RECEIVED" else "TRANSMITTED"
            return "$prefix ${commands.joinToString(", ") { "command=$it" }}"
        }

        return if (direction == "RX") "RECEIVED MESSAGE" else "TRANSMITTED RESPONSE"
    }

    private fun wireControlName(value: Byte): String? {
        return when (value) {
            PINPADControl.ACK -> "ACK"
            PINPADControl.NAK -> "NACK"
            PINPADControl.EOT -> "EOT"
            else -> null
        }
    }

    private fun frameCommands(bytes: ByteArray): List<String> {
        val commands = mutableListOf<String>()
        var index = 0
        while (index < bytes.size) {
            val frameType = PINPADFrameType.fromStart(bytes[index])
            if (frameType == null) {
                index += 1
                continue
            }

            val endIndex = bytes.indexOf(frameType.end, startIndex = index + 1)
            if (endIndex < 0 || endIndex + 1 >= bytes.size) break

            val content = bytes.copyOfRange(index + 1, endIndex)
            val commandLength = commandLength(content)
            if (content.size >= commandLength) {
                commands += content.copyOfRange(0, commandLength).toString(StandardCharsets.US_ASCII)
            }
            index = endIndex + 2
        }
        return commands
    }

    private fun ByteArray.indexOf(value: Byte, startIndex: Int): Int {
        for (index in startIndex until size) {
            if (this[index] == value) return index
        }
        return -1
    }

    private fun commandLength(content: ByteArray): Int {
        val first = content.firstOrNull()
        if (first == 'T'.code.toByte() || first == 'M'.code.toByte()) return 3
        if (content.size >= 3) {
            val prefix = content.copyOfRange(0, 3).toString(StandardCharsets.US_ASCII)
            if (prefix in THREE_CHARACTER_COMMANDS) return 3
        }
        return 2
    }

    private fun bytesToAscii(bytes: ByteArray): String {
        val shown = bytes.take(MAX_BYTES).joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) value.toChar().toString() else "."
        }
        return if (bytes.size > MAX_BYTES) {
            "$shown...(+${bytes.size - MAX_BYTES} bytes)"
        } else {
            shown
        }
    }

    private fun byteToHex(value: Byte): String {
        return "%02X".format(value.toInt() and 0xFF)
    }

    private fun log(message: String) {
        if (BuildConfig.SERIAL_TRACE_ENABLED) {
            Log.d(TAG, message)
        }
    }

    private fun detail(message: String) {
        if (BuildConfig.SERIAL_DETAIL_TRACE_ENABLED) {
            Log.d(TAG, message)
        }
    }
}
