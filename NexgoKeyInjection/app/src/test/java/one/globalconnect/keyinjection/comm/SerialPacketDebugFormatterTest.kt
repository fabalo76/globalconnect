package one.globalconnect.keyinjection.comm

import kotlin.text.Charsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialPacketDebugFormatterTest {
    @Test
    fun command02RxTraceRedactsKeyAndKtkPayloads() {
        val key = "FC9FEEC63FBE86B1B956B7E8CF2A94A8"
        val ktk = "4004A21949A2B6796B250746C12686B0"
        val payload = (
            "0201010008028787611FFFFF0121010003400000" +
                "020$key" +
                "020$ktk"
            ).toByteArray(Charsets.US_ASCII)

        val trace = SerialPacketDebugFormatter.format(
            "RX", "STX", 0x02, 0x03, payload, 0x5A
        )

        assertTrue(trace.contains("<KEY_REDACTED:32>"))
        assertTrue(trace.contains("<KTK_REDACTED:32>"))
        assertFalse(trace.contains(key))
        assertFalse(trace.contains(ktk))
        assertTrue(trace.contains("lrc=0x5A"))
    }

    @Test
    fun legacyKeyCommandsAreRedactedOnRx() {
        val masterKey = "0123456789ABCDEF0123456789ABCDEF"
        val masterPayload = "0100$masterKey".toByteArray(Charsets.US_ASCII)

        val trace = SerialPacketDebugFormatter.format(
            "RX", "STX", 0x02, 0x03, masterPayload, 0x00
        )

        assertTrue(trace.contains("<MASTER_KEY_REDACTED:32>"))
        assertFalse(trace.contains(masterKey))
    }

    @Test
    fun responsePayloadIsVisibleOnTx() {
        val response = "0200611F".toByteArray(Charsets.US_ASCII)

        val trace = SerialPacketDebugFormatter.format(
            "TX", "STX", 0x02, 0x03, response, 0x12
        )

        assertTrue(trace.contains("ASCII=\"0200611F\""))
        assertTrue(trace.contains("HEX="))
    }
}
