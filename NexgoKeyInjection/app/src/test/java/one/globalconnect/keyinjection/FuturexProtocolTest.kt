package one.globalconnect.keyinjection

import kotlin.text.Charsets
import one.globalconnect.keyinjection.protocol.FuturexProtocol
import one.globalconnect.keyinjection.protocol.KeyInjectionCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FuturexProtocolTest {
    private val protocol = FuturexProtocol()

    @Test
    fun mkInjectCalculatesKcv() {
        val key = "0123456789ABCDEF0123456789ABCDEF"
        val parsed = protocol.parseMKInject(ascii("0100$key"))

        assertNotNull(parsed)
        assertEquals("D5D44FF7", parsed!!.calculatedKCV)
    }

    @Test
    fun dukptInjectCalculatesKcv() {
        val ksn = "FFFF0121010002000000"
        val ipek = "A43A88EFB570513B6F3791FF94184AB0"
        val parsed = protocol.parseDUKPTInject(ascii("0000$ksn$ipek"))

        assertNotNull(parsed)
        assertEquals("D766A145", parsed!!.calculatedKCV)
    }

    @Test
    fun command02ParsesKeyAndSuppliedClearKtk() {
        val body = "0201010008028787611F" +
            "FFFF0121010003400000" +
            "020FC9FEEC63FBE86B1B956B7E8CF2A94A8" +
            "0204004A21949A2B6796B250746C12686B0"

        val parsed = protocol.parseKTKEncryptedKeyInject(ascii(body))

        assertNotNull(parsed)
        assertEquals("08", parsed!!.keyType)
        assertEquals("02", parsed.keyEncryption)
        assertEquals("01", parsed.keySlot)
        assertEquals("FFFF0121010003400000", parsed.ksn)
        assertEquals(32, parsed.keyPayload.length)
        assertEquals(32, parsed.ktkPayload.length)
    }

    @Test
    fun command02RejectsTrailingOrMissingPayloadData() {
        val validBody = "020101000101D5D4611F" +
            "00000000000000000000" +
            "0204E4EF7BE4F199B144E4EF7BE4F199B14" +
            "000"

        assertNotNull(protocol.parseKTKEncryptedKeyInject(ascii(validBody)))
        assertNull(protocol.parseKTKEncryptedKeyInject(ascii(validBody + "00")))
        assertNull(protocol.parseKTKEncryptedKeyInject(ascii(validBody.dropLast(1))))
    }

    @Test
    fun malformedLegacyKeysAreRejectedWithoutThrowing() {
        assertNull(protocol.parseMKInject(ascii("0100NOT-A-KEY")))
        assertNull(protocol.parseDUKPTInject(ascii("0000SHORT")))
        assertEquals("", protocol.calculateKCV("XYZ"))
    }

    @Test
    fun commandCodesMapToFuturexOperations() {
        assertEquals(KeyInjectionCommand.DUKPT_INJECT, protocol.getCommand(ascii("00")))
        assertEquals(KeyInjectionCommand.MK_INJECT, protocol.getCommand(ascii("01")))
        assertEquals(KeyInjectionCommand.KTK_ENCRYPTED_KEY_INJECT, protocol.getCommand(ascii("02")))
        assertEquals(KeyInjectionCommand.SERIAL_NUM_REQUEST, protocol.getCommand(ascii("03")))
        assertEquals(KeyInjectionCommand.SERIAL_NUM_WRITE, protocol.getCommand(ascii("04")))
        assertEquals(KeyInjectionCommand.ERASE_KEYS, protocol.getCommand(ascii("05")))
        assertNull(protocol.getCommand(ascii("99")))
    }

    @Test
    fun responseBodiesMatchFuturexProtocol() {
        assertArrayEquals(ascii("0100D5D4"), protocol.buildMKInjectResponse("00", "D5D44FF7"))
        assertArrayEquals(
            ascii("000000D766"),
            protocol.buildDUKPTInjectResponse("00", "00", "D766A145")
        )
        assertArrayEquals(
            ascii("0300ABCDEFGHIJKLMNOP"),
            protocol.buildSerialNumReadResponse("00", "ABCDEFGHIJKLMNOP")
        )
        assertArrayEquals(ascii("0200611F"), protocol.buildKTKEncryptedKeyInjectResponse("00", "611F51"))
        assertArrayEquals(ascii("0500"), protocol.buildEraseAllKeys("00"))
    }

    @Test
    fun serialCommandsRequireFuturexFieldLengths() {
        assertNotNull(protocol.parseSerialNumRequest(ascii("0301")))
        assertNull(protocol.parseSerialNumRequest(ascii("03010")))
        assertNotNull(protocol.parseSerialNumWrite(ascii("0401ABCDEFGHIJKLMNOP")))
        assertNull(protocol.parseSerialNumWrite(ascii("0401TOO-SHORT")))
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)
}
