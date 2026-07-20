package one.globalconnect.xtmsagent.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmsTaskStatusTest {
    @Test
    fun routineOfflineStatusIsHiddenWhenLauncherConfigIsApplied() {
        val status = TmsConnectionStatus("TMS offline", TmsStatusSeverity.WARNING)

        assertFalse(shouldShowTmsConnectionStatus(status, launcherConfigApplied = true))
    }

    @Test
    fun routineOfflineStatusIsShownWhileLauncherConfigIsMissing() {
        val status = TmsConnectionStatus("TMS offline", TmsStatusSeverity.WARNING)

        assertTrue(shouldShowTmsConnectionStatus(status, launcherConfigApplied = false))
    }

    @Test
    fun connectingStatusIsHiddenWhenLauncherConfigIsApplied() {
        val status = TmsConnectionStatus("Connecting to TMS", TmsStatusSeverity.CONNECTING)

        assertFalse(shouldShowTmsConnectionStatus(status, launcherConfigApplied = true))
    }

    @Test
    fun errorsRemainVisibleWhenLauncherConfigIsApplied() {
        val status = TmsConnectionStatus("TMS authorization error", TmsStatusSeverity.ERROR)

        assertTrue(shouldShowTmsConnectionStatus(status, launcherConfigApplied = true))
    }

    @Test
    fun connectedStatusIsNeverShownInWarningStrip() {
        val status = TmsConnectionStatus(
            "TMS connected",
            TmsStatusSeverity.CONNECTED,
            connected = true,
        )

        assertFalse(shouldShowTmsConnectionStatus(status, launcherConfigApplied = false))
    }
}
