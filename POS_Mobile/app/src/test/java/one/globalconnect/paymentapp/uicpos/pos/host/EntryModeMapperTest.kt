package one.globalconnect.paymentapp.uicpos.pos.host

import one.globalconnect.paymentapp.uicpos.pos.model.TransLog
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryModeMapperTest {

    /** Verifies that chip capture advertises PIN capability without relying on PIN presence. */
    @Test
    fun `chip entry mode remains PIN capable when PIN block is omitted`() {
        val transLog = TransLog(
            CardDataSource = "CHIP",
            PINBlock = null,
        )

        assertEquals("0051", EntryModeMapper.from(transLog, onlinePinCap = true))
    }

    /** Verifies that disabling online PIN changes only the capability digit. */
    @Test
    fun `chip entry mode reports no PIN capability when terminal setting is disabled`() {
        val transLog = TransLog(CardDataSource = "CHIP")

        assertEquals("0052", EntryModeMapper.from(transLog, onlinePinCap = false))
    }

    /** Verifies that contactless capture uses the same terminal capability rule. */
    @Test
    fun `contactless entry mode reports terminal PIN capability`() {
        val transLog = TransLog(CardDataSource = "CONTACTLESS")

        assertEquals("0071", EntryModeMapper.from(transLog, onlinePinCap = true))
    }
}
