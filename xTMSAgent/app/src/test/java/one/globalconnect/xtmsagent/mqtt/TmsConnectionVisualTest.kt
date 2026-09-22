package one.globalconnect.xtmsagent.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test
import one.globalconnect.xtmsagent.R

class TmsConnectionVisualTest {
    @Test fun connectedUsesClosedLink() {
        assertEquals(R.drawable.ic_tms_link_connected,
            TmsConnectionStatus("Connected", TmsStatusSeverity.CONNECTED, true).linkIcon())
    }
    @Test fun connectingUsesDottedLink() {
        assertEquals(R.drawable.ic_tms_link_connecting,
            TmsConnectionStatus("Connecting", TmsStatusSeverity.CONNECTING).linkIcon())
    }
    @Test fun disconnectedAndErrorsUseBrokenLink() {
        for (severity in listOf(TmsStatusSeverity.WARNING, TmsStatusSeverity.ERROR, TmsStatusSeverity.CONNECTED)) {
            assertEquals(R.drawable.ic_tms_link_disconnected,
                TmsConnectionStatus("Offline", severity, false).linkIcon())
        }
    }
}
