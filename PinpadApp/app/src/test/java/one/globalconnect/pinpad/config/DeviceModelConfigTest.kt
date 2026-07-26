package one.globalconnect.pinpad.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceModelConfigTest {
    @Test
    fun ct20pUsesDedicatedUsbCdcSerialPort() {
        assertEquals(1, DeviceModelConfig.getSerialPort("CT20P"))
        assertEquals(0, DeviceModelConfig.getUsbCdcSerialPort("CT20P"))
        assertEquals(DisplaySpec(widthPx = 480, heightPx = 800), DeviceModelConfig.getDeviceSpec("CT20P").display)
    }

    @Test
    fun n6ModelsUseRs232PortOne() {
        assertEquals(1, DeviceModelConfig.getSerialPort("N6"))
        assertEquals(1, DeviceModelConfig.getSerialPort("N6 Pro"))
        assertEquals(1, DeviceModelConfig.getSerialPort("N6PRO"))
    }

    @Test
    fun unknownModelFallsBackToPortZero() {
        assertEquals(0, DeviceModelConfig.getSerialPort("UNKNOWN"))
        assertTrue(DeviceModelConfig.supportsUsbCdc("UNKNOWN"))
    }

    @Test
    fun modelsWithoutDedicatedUsbCdcPortUseTheirDefaultPort() {
        assertEquals(
            DeviceModelConfig.getSerialPort("N82"),
            DeviceModelConfig.getUsbCdcSerialPort("N82"),
        )
    }

    @Test
    fun posMobilePreviewDisplaySpecsAreRegistered() {
        assertEquals(DisplaySpec(widthPx = 480, heightPx = 854, densityDpi = 244), DeviceModelConfig.getDeviceSpec("N82").display)
        assertEquals(DisplaySpec(widthPx = 720, heightPx = 1600, densityDpi = 320), DeviceModelConfig.getDeviceSpec("N96").display)
    }
}
