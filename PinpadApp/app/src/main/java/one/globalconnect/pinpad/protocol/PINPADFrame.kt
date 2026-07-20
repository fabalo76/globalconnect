package one.globalconnect.pinpad.protocol

import java.nio.charset.StandardCharsets

data class PINPADFrame(
    val frameType: PINPADFrameType,
    val commandId: String,
    val payload: ByteArray = ByteArray(0),
) {
    val payloadAscii: String
        get() = payload.toString(StandardCharsets.US_ASCII)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PINPADFrame) return false
        return frameType == other.frameType &&
            commandId == other.commandId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = frameType.hashCode()
        result = 31 * result + commandId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

enum class PINPADFrameType(val start: Byte, val end: Byte) {
    Transaction(PINPADControl.STX, PINPADControl.ETX),
    Administration(PINPADControl.SI, PINPADControl.SO);

    companion object {
        fun fromStart(start: Byte): PINPADFrameType? = entries.firstOrNull { it.start == start }
    }
}

sealed class PINPADInbound {
    data class Frame(val frame: PINPADFrame) : PINPADInbound()
    data class Control(val value: Byte) : PINPADInbound()
    data object InvalidFrame : PINPADInbound()
    data object FrameTimeout : PINPADInbound()
}
