package com.uic.pinpad.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PINPADFrameCodecTest {
    private val codec = PINPADFrameCodec()

    @Test
    fun encodesAndDecodesAdministrationFrame() {
        val encoded = codec.encode(PINPADFrame(PINPADFrameType.Administration, "06"))

        assertContentEquals(byteArrayOf(0x0F, 0x30, 0x36, 0x0E, 0x08), encoded)

        val decoded = codec.decode(encoded)
        assertIs<PINPADFrameCodec.DecodeResult.Valid>(decoded)
        assertEquals("06", decoded.frame.commandId)
        assertEquals(PINPADFrameType.Administration, decoded.frame.frameType)
    }

    @Test
    fun rejectsBadLrc() {
        val encoded = codec.encode(PINPADFrame(PINPADFrameType.Administration, "06")).copyOf()
        encoded[encoded.lastIndex] = 0x00

        assertEquals(PINPADFrameCodec.DecodeResult.BadLrc, codec.decode(encoded))
    }
}
