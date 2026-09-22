package one.globalconnect.paymentapp.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test
    fun `physical keypad models are recognized regardless of case`() {
        assertTrue(DeviceCapabilities.hasPhysicalNumericKeypad(model = "CT20"))
        assertTrue(DeviceCapabilities.hasPhysicalNumericKeypad(model = "ct20p"))
        assertTrue(DeviceCapabilities.hasPhysicalNumericKeypad(model = "N80"))
    }

    @Test
    fun `model names can include manufacturer text`() {
        assertTrue(DeviceCapabilities.hasPhysicalNumericKeypad(model = "NEXGO CT20P"))
        assertTrue(DeviceCapabilities.hasPhysicalNumericKeypad(model = "N80-PRO"))
    }

    @Test
    fun `device and product identifiers are fallback sources`() {
        assertTrue(DeviceCapabilities.hasPhysicalNumericKeypad(model = null, device = "N80"))
        assertTrue(DeviceCapabilities.hasPhysicalNumericKeypad(model = null, product = "NEXGO_CT20"))
    }

    @Test
    fun `touch-only and similarly named models are not treated as keypad devices`() {
        assertFalse(DeviceCapabilities.hasPhysicalNumericKeypad(model = "N62"))
        assertFalse(DeviceCapabilities.hasPhysicalNumericKeypad(model = "CT200"))
        assertFalse(DeviceCapabilities.hasPhysicalNumericKeypad(model = null, device = null, product = null))
    }

    @Test
    fun `terminals without physical reader lights use the in-app strip`() {
        assertTrue(DeviceCapabilities.usesInAppCardReaderLights(model = "CT20"))
        assertTrue(DeviceCapabilities.usesInAppCardReaderLights(model = "NEXGO CT20P"))
        assertTrue(DeviceCapabilities.usesInAppCardReaderLights(model = "N80-PRO"))
        assertTrue(DeviceCapabilities.usesInAppCardReaderLights(model = "N60 Pro"))
        assertTrue(DeviceCapabilities.usesInAppCardReaderLights(model = "N60PRO"))
        assertFalse(DeviceCapabilities.usesInAppCardReaderLights(model = "N96"))
        assertFalse(DeviceCapabilities.usesInAppCardReaderLights(model = "N62"))
    }

    @Test
    fun `payment animation selects the terminal family`() {
        assertTrue(DeviceCapabilities.paymentDeviceVisual(model = "CT20") == PaymentDeviceVisual.CT20)
        assertTrue(DeviceCapabilities.paymentDeviceVisual(model = "NEXGO CT20P") == PaymentDeviceVisual.CT20P)
        assertTrue(DeviceCapabilities.paymentDeviceVisual(model = "N80-PRO") == PaymentDeviceVisual.N80)
        assertTrue(DeviceCapabilities.paymentDeviceVisual(model = "NEXGO N96") == PaymentDeviceVisual.N96)
        assertTrue(DeviceCapabilities.paymentDeviceVisual(model = "N60_Pro") == PaymentDeviceVisual.N60_PRO)
        assertTrue(DeviceCapabilities.paymentDeviceVisual(model = "N82") == PaymentDeviceVisual.N82)
    }
}
