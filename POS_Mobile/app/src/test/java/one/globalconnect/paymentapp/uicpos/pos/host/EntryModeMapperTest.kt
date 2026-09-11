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

        assertEquals(
            "051",
            EntryModeMapper.from(
                transLog,
                onlinePinCap = true,
                offlineEncrPinCap = false,
                offlineClearPinCap = false,
            ),
        )
    }

    /** Verifies that disabling online PIN changes only the capability digit. */
    @Test
    fun `chip entry mode reports no PIN capability when terminal setting is disabled`() {
        val transLog = TransLog(CardDataSource = "CHIP")

        assertEquals(
            "052",
            EntryModeMapper.from(
                transLog,
                onlinePinCap = false,
                offlineEncrPinCap = false,
                offlineClearPinCap = false,
            ),
        )
    }

    /** Verifies that encrypted offline PIN is enough to advertise PIN capability. */
    @Test
    fun `chip entry mode reports PIN capability when encrypted offline PIN is enabled`() {
        val transLog = TransLog(CardDataSource = "CHIP")

        assertEquals(
            "051",
            EntryModeMapper.from(
                transLog,
                onlinePinCap = false,
                offlineEncrPinCap = true,
                offlineClearPinCap = false,
            ),
        )
    }

    /** Verifies that clear offline PIN is enough to advertise PIN capability. */
    @Test
    fun `chip entry mode reports PIN capability when clear offline PIN is enabled`() {
        val transLog = TransLog(CardDataSource = "CHIP")

        assertEquals(
            "051",
            EntryModeMapper.from(
                transLog,
                onlinePinCap = false,
                offlineEncrPinCap = false,
                offlineClearPinCap = true,
            ),
        )
    }

    /** Verifies that contactless capture uses the same terminal capability rule. */
    @Test
    fun `contactless entry mode reports terminal PIN capability`() {
        val transLog = TransLog(CardDataSource = "CONTACTLESS")

        assertEquals(
            "071",
            EntryModeMapper.from(
                transLog,
                onlinePinCap = false,
                offlineEncrPinCap = true,
                offlineClearPinCap = false,
            ),
        )
    }
}
