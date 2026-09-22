package one.globalconnect.xtmsagent.remote

import org.junit.Assert.*
import org.junit.Test

class RestrictedSettingsProvisionerTest {
    @Test fun trialRequiresExactModelAndroid13AndOwner() {
        assertTrue(RestrictedSettingsProvisioner.eligible("N6ProLite", 33, true))
        assertFalse(RestrictedSettingsProvisioner.eligible("N6ProLite", 33, false))
        assertFalse(RestrictedSettingsProvisioner.eligible("N6ProLite", 34, true))
        assertFalse(RestrictedSettingsProvisioner.eligible("N96", 33, true))
    }

    @Test fun alreadyApprovedDoesNotWrite() {
        assertEquals("already_allowed", RestrictedSettingsProvisioner.approveIfBlocked({ 0 }) {
            fail("Must preserve an existing approval")
        })
    }

    @Test fun acceptedWriteWithoutStateChangeIsNotSuccess() {
        assertEquals("approval_not_applied", RestrictedSettingsProvisioner.approveIfBlocked({ 1 }, {}))
    }

    @Test fun successRequiresReadBack() {
        var mode = 1
        assertEquals("approved_by_device_owner", RestrictedSettingsProvisioner.approveIfBlocked({ mode }) {
            mode = 0
        })
    }

    @Test fun unexpectedModesAreNotOverwritten() {
        listOf(2, 3, 4, -1).forEach { mode ->
            assertEquals("unsupported_state_$mode", RestrictedSettingsProvisioner.approveIfBlocked({ mode }) {
                fail("Unexpected state must remain unchanged")
            })
        }
    }

    @Test(expected = SecurityException::class)
    fun permissionFailureIsPropagatedToDiagnosticHandler() {
        RestrictedSettingsProvisioner.approveIfBlocked({ 1 }) { throw SecurityException("denied") }
    }
}
