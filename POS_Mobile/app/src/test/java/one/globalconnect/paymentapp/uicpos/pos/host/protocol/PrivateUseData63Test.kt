package one.globalconnect.paymentapp.uicpos.pos.host.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateUseData63Test {

    @Test
    fun `encode builds expected hex payload`() {
        val tags = listOf(
            PrivateUseData63.Tag("16", "123"),
            PrivateUseData63.Tag("39", "000000001234"),
        )

        val encoded = PrivateUseData63.encode(tags)

        assertEquals("0005313631323300143339303030303030303031323334", encoded)
    }

    @Test
    fun `parse decodes tags from payload`() {
        val payload = "0005313631323300143339303030303030303031323334"

        val parsed = PrivateUseData63.parse(payload)

        assertEquals("123", parsed["16"])
        assertEquals("000000001234", parsed["39"])
    }

    @Test
    fun `parse ignores invalid payload`() {
        val parsed = PrivateUseData63.parse("ZZZ")
        assertTrue(parsed.isEmpty())
    }
}
