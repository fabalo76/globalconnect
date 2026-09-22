package one.globalconnect.xtmsagent.mqtt.status

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmsStatusWorkerNetworkTest {

    @Test
    fun formatsValidHardwareAddressAndRejectsAndroidPlaceholder() {
        assertEquals(
            "F4:1A:B0:39:6A:35",
            TmsStatusWorker.formatMacAddress(byteArrayOf(0xF4.toByte(), 0x1A, 0xB0.toByte(), 0x39, 0x6A, 0x35)),
        )
        assertNull(TmsStatusWorker.formatMacAddress(byteArrayOf(0x02, 0, 0, 0, 0, 0)))
    }

    @Test
    fun extractsWifiAndEthernetMacsFromNexgoVendorProperty() {
        val addresses = TmsStatusWorker.parseNexgoNetworkMacProperty(
            "f41ab0396a35f41ab0396a36f41ab0396a37",
        )
        assertEquals("F4:1A:B0:39:6A:35", addresses.first)
        assertEquals("F4:1A:B0:39:6A:37", addresses.second)
    }

    @Test
    fun ct20DesktopModelIsReportedWithoutBattery() {
        assertTrue(TmsStatusWorker.isBatterylessModel("NEXGO CT20"))
        assertFalse(TmsStatusWorker.isBatterylessModel("CT20P"))
        assertFalse(TmsStatusWorker.isBatterylessModel("N80"))
    }

    @Test
    fun mapsCellularRadioTypesToPortalGenerations() {
        assertEquals("2G", TmsStatusWorker.cellularGeneration(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals("3G", TmsStatusWorker.cellularGeneration(TelephonyManager.NETWORK_TYPE_HSPA))
        assertEquals("LTE", TmsStatusWorker.cellularGeneration(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("5G", TmsStatusWorker.cellularGeneration(TelephonyManager.NETWORK_TYPE_NR))
        assertNull(TmsStatusWorker.cellularGeneration(TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }
}
