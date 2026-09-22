package one.globalconnect.pinpad.comms

import one.globalconnect.pinpad.config.DeviceModelConfig
import one.globalconnect.pinpad.storage.SerialSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbBaseRoutingTest {
    private val settings = SerialSettings("SERIAL", 2, 0, 0, 9000, 9600, 8, 1, "N", usbBasePort = 101)

    @Test
    fun baseOverridesSavedPortAndNeverChangesCdc() {
        for (model in listOf("N6ProLite", "N6 Pro Lite", "n6prolite", "N6Pro", "N6 Pro")) {
            assertTrue(DeviceModelConfig.getDeviceSpec(model).usbBaseSerialSupported)
            assertTrue(usesUartTransport("SERIAL", model))
            assertEquals(0, DeviceModelConfig.getDeviceSpec(model).fixedUsbBasePort)
            assertEquals(0, selectedSerialPort(settings, model))
            assertEquals(2, selectedSerialPort(settings.copy(transportMode = "RS232"), model))
            for (mode in listOf("SERIAL", "RS232", "IP")) assertFalse(shouldDisableUsbCdc(mode, model))
            assertFalse(usesUartTransport("IP", model))
        }
    }

    @Test
    fun existingCdcModelsKeepDedicatedPort() {
        assertFalse(usesUartTransport("SERIAL", "CT20P"))
        assertEquals(0, selectedSerialPort(settings, "CT20P"))
        assertTrue(shouldDisableUsbCdc("RS232", "CT20P"))
        assertFalse(shouldDisableUsbCdc("SERIAL", "CT20P"))
        assertFalse(usesUartTransport("SERIAL", "UNKNOWN"))
    }
}
