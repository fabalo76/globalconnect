package one.globalconnect.pinpad.comms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinpadSerialServiceTest {
    @Test
    fun usbCdcRemainsEnabledOnlyForUsbSerialMode() {
        assertFalse(shouldDisableUsbCdc("SERIAL"))
        assertFalse(shouldDisableUsbCdc("serial"))
        assertTrue(shouldDisableUsbCdc("RS232"))
        assertTrue(shouldDisableUsbCdc("IP"))
    }
}
