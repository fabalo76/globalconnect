package one.globalconnect.keyinjection

import one.globalconnect.keyinjection.util.hexToByte
import one.globalconnect.keyinjection.util.hexToByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexUtilsTest {
    @Test
    fun hexToByteArrayConverts() {
        val hex = "0A1B2C"
        val expected = byteArrayOf(0x0A, 0x1B, 0x2C)
        assertArrayEquals(expected, hex.hexToByteArray())
    }

    @Test
    fun hexToByteConverts() {
        val hex = "FF"
        assertEquals(0xFF.toByte(), hex.hexToByte())
    }
}
