package one.globalconnect.pinpad.protocol

import one.globalconnect.pinpad.logging.PinpadTraceLog
import java.io.ByteArrayOutputStream

class PINPADStreamParser(
    private val codec: PINPADFrameCodec = PINPADFrameCodec(),
    private val partialTimeoutMs: Long = 1_000L,
) {
    private var buffer = ByteArrayOutputStream()
    private var activeFrameType: PINPADFrameType? = null
    private var awaitingLrc = false
    private var lastByteAtMs = 0L

    fun accept(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): List<PINPADInbound> {
        val events = mutableListOf<PINPADInbound>()
        discardTimedOutPartial(nowMs, events)

        bytes.forEach { byte ->
            discardTimedOutPartial(nowMs, events)
            lastByteAtMs = nowMs
            consume(byte, events)
        }
        return events
    }

    private fun consume(byte: Byte, events: MutableList<PINPADInbound>) {
        if (activeFrameType == null) {
            val frameType = PINPADFrameType.fromStart(byte)
            when {
                frameType != null -> {
                    activeFrameType = frameType
                    buffer.write(byte.toInt())
                    PinpadTraceLog.parser("frame start type=${frameType.name} start=${PinpadTraceLog.controlName(byte)}")
                }
                byte == PINPADControl.ACK || byte == PINPADControl.NAK || byte == PINPADControl.EOT -> {
                    PinpadTraceLog.parser("control ${PinpadTraceLog.controlName(byte)}")
                    events += PINPADInbound.Control(byte)
                }
                else -> PinpadTraceLog.parser("discarded byte before frame start hex=${PinpadTraceLog.bytesToHex(byteArrayOf(byte))}")
            }
            return
        }

        buffer.write(byte.toInt())
        val frameType = activeFrameType ?: return
        if (awaitingLrc) {
            val candidate = buffer.toByteArray()
            events += when (val result = codec.decode(candidate)) {
                is PINPADFrameCodec.DecodeResult.Valid -> {
                    PinpadTraceLog.parser(
                        "decoded frame type=${result.frame.frameType.name} command=${result.frame.commandId} payloadLen=${result.frame.payload.size}",
                    )
                    PINPADInbound.Frame(result.frame)
                }
                PINPADFrameCodec.DecodeResult.BadLrc -> {
                    PinpadTraceLog.parser("invalid frame badLrc len=${candidate.size}")
                    PINPADInbound.InvalidFrame
                }
                PINPADFrameCodec.DecodeResult.Invalid -> {
                    PinpadTraceLog.parser("invalid frame malformed len=${candidate.size}")
                    PINPADInbound.InvalidFrame
                }
                PINPADFrameCodec.DecodeResult.Incomplete -> {
                    PinpadTraceLog.parser("invalid frame incomplete len=${candidate.size}")
                    PINPADInbound.InvalidFrame
                }
            }
            reset()
            return
        }

        awaitingLrc = byte == frameType.end
        if (awaitingLrc) {
            PinpadTraceLog.parser("frame end type=${frameType.name} end=${PinpadTraceLog.controlName(byte)} awaitingLrc len=${buffer.size()}")
        }
    }

    private fun isPartialTimedOut(nowMs: Long): Boolean {
        return activeFrameType != null && lastByteAtMs > 0L && nowMs - lastByteAtMs > partialTimeoutMs
    }

    private fun discardTimedOutPartial(nowMs: Long, events: MutableList<PINPADInbound>) {
        if (!isPartialTimedOut(nowMs)) return
        PinpadTraceLog.parser("partial timeout after ${nowMs - lastByteAtMs}ms discardedBytes=${buffer.size()}")
        reset()
        events += PINPADInbound.FrameTimeout
    }

    private fun reset() {
        buffer = ByteArrayOutputStream()
        activeFrameType = null
        awaitingLrc = false
        lastByteAtMs = 0L
    }
}
