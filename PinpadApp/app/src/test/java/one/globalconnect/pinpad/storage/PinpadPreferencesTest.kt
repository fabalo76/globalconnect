package one.globalconnect.pinpad.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class PinpadPreferencesTest {
    @Test
    fun transportModeUsesOnlyExplicitSerialOrRs232Values() {
        assertEquals("SERIAL", normalizeTransportMode(null))
        assertEquals("SERIAL", normalizeTransportMode(""))
        assertEquals("SERIAL", normalizeTransportMode("AUTO"))
        assertEquals("SERIAL", normalizeTransportMode("USB_CDC"))
        assertEquals("SERIAL", normalizeTransportMode("serial"))
        assertEquals("RS232", normalizeTransportMode("rs232"))
        assertEquals("SERIAL", normalizeTransportMode("unknown"))
    }
}
