package one.globalconnect.xtmsagent.mqtt

import org.junit.Assert.assertEquals
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

    @Test
    fun alreadyProvisionedCertificateShowsSupportMessageWithoutRetry() {
        val error = IllegalStateException(
            """IoT credentials HTTP 409: {"code":"IOT_CERTIFICATE_ALREADY_PROVISIONED"}"""
        )

        TmsTaskStatus.connectionFailed(error, nextRetryMs = 268_361)

        assertEquals(
            "TMS certificate is already provisioned for this terminal. Contact support for assistance.",
            TmsTaskStatus.connection.value.text,
        )
        assertEquals(TmsStatusSeverity.ERROR, TmsTaskStatus.connection.value.severity)
    }

    @Test
    fun tlsCertificateFailureStillShowsMismatchMessage() {
        TmsTaskStatus.connectionFailed(
            IllegalStateException("SSLPeerUnverifiedException: certificate not verified"),
            nextRetryMs = null,
        )

        assertEquals(
            "TMS security error: certificate mismatch on network",
            TmsTaskStatus.connection.value.text,
        )
    }
}
