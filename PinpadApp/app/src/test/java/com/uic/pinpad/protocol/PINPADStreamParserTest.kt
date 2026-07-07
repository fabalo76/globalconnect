package com.uic.pinpad.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PINPADStreamParserTest {
    private val codec = PINPADFrameCodec()

    @Test
    fun discardsGarbageUntilValidFrameStart() {
        val parser = PINPADStreamParser(codec)
        val frame = codec.encode(PINPADFrame(PINPADFrameType.Administration, "11"))

        val events = parser.accept(byteArrayOf(0x55, 0x44) + frame)

        assertEquals(1, events.size)
        val event = assertIs<PINPADInbound.Frame>(events.single())
        assertEquals("11", event.frame.commandId)
    }

    @Test
    fun discardsTimedOutPartialFrame() {
        val parser = PINPADStreamParser(codec, partialTimeoutMs = 1_000L)
        parser.accept(byteArrayOf(PINPADControl.SI, '0'.code.toByte()), nowMs = 1_000L)
        val events = parser.accept(codec.encode(PINPADFrame(PINPADFrameType.Administration, "06")), nowMs = 2_100L)

        assertTrue(events[0] is PINPADInbound.FrameTimeout)
        assertEquals("06", assertIs<PINPADInbound.Frame>(events[1]).frame.commandId)
    }

    @Test
    fun emitsControlCharacters() {
        val parser = PINPADStreamParser(codec)

        val events = parser.accept(byteArrayOf(PINPADControl.ACK, PINPADControl.EOT))

        assertTrue(events[0] is PINPADInbound.Control)
        assertTrue(events[1] is PINPADInbound.Control)
    }

    @Test
    fun treatsEotValueAfterFrameEndAsLrc() {
        val parser = PINPADStreamParser(codec)
        val frame = codec.encode(PINPADFrame(PINPADFrameType.Administration, "06", byteArrayOf(0x0C)))

        assertEquals(PINPADControl.EOT, frame.last())
        val events = parser.accept(frame)

        val event = assertIs<PINPADInbound.Frame>(events.single())
        assertEquals("06", event.frame.commandId)
        assertEquals(0x0C.toByte(), event.frame.payload.single())
    }

    @Test
    fun decodesThreeCharacterZCommand() {
        val parser = PINPADStreamParser(codec)
        val frame = codec.encode(PINPADFrame(PINPADFrameType.Transaction, "Z64", "0".toByteArray()))

        val event = assertIs<PINPADInbound.Frame>(parser.accept(frame).single())

        assertEquals("Z64", event.frame.commandId)
        assertEquals("0", event.frame.payloadAscii)
    }
}
