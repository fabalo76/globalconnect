package one.globalconnect.paymentapp.ecr

import org.junit.Assert.assertEquals
import org.junit.Test

class EcrDevicePortTest {
    @Test fun ct20UsbOverridesPreviouslySavedSerialPorts() {
        for (model in listOf("CT20", "ct20", "CT20P")) {
            for (port in listOf(0, 1, 101)) {
                assertEquals(0, EcrSettings(transport = "USB", serialPort = port).forDevice(model).serialPort)
            }
        }
    }
    @Test fun rs232AndOtherDevicesKeepTheirPort() {
        assertEquals(101, EcrSettings(transport = "RS232", serialPort = 101).forDevice("CT20").serialPort)
        assertEquals(1, EcrSettings(transport = "USB", serialPort = 1).forDevice("N96").serialPort)
    }
}
