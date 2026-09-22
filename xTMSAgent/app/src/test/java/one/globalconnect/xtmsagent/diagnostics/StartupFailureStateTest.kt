package one.globalconnect.xtmsagent.diagnostics

import one.globalconnect.xtmsagent.recovery.StartupFailureState
import org.junit.Assert.*
import org.junit.Test

class StartupFailureStateTest {
    @Test fun thirdCrashEntersRecoveryAndItStaysLatchedAcrossUpdates() {
        var state = StartupFailureState()
        for (index in 1..3) {
            state = state.begin(index * 1000L, index, 73, null, null).failed(index * 1000L + 100)
            assertEquals(index == 3, state.recovery)
        }
        assertTrue(state.begin(1_000_000, 5, 74, null, null).recovery)
    }
    @Test fun nativeCrashAndAnrCountWhenNoExceptionHandlerRan() {
        var state = StartupFailureState().begin(1000, 1, 73, null, null)
        state = state.begin(2000, 2, 73, 1500, 5)
        state = state.begin(3000, 3, 73, 2500, 6)
        assertTrue(state.begin(4000, 4, 73, 3500, 4).recovery)
    }
    @Test fun exceptionAndOsExitDoNotCountTheSameAttemptTwice() {
        val state = StartupFailureState().begin(1000, 1, 73, null, null).failed(1100)
            .begin(2000, 2, 73, 1100, 4)
        assertEquals(1, state.failures.size)
    }
    @Test fun normalKillsAndIncompleteStartsWithoutEvidenceAreNotFailures() {
        for (reason in listOf(null, 1, 2, 3, 7, 10, 13)) {
            val state = StartupFailureState().begin(1000, 1, 73, null, null)
                .begin(2000, 2, 73, 1500, reason)
            assertTrue(state.failures.isEmpty())
        }
    }
    @Test fun previousBuildExitIsNotAttributedToUpdatedBuild() {
        val state = StartupFailureState().begin(1000, 1, 72, null, null)
            .begin(2000, 2, 73, 1500, 4)
        assertTrue(state.failures.isEmpty())
    }
    @Test fun stableAttemptClearsFailureHistory() {
        val state = StartupFailureState().begin(1000, 1, 73, null, null).failed(1100)
            .begin(2000, 2, 73, null, null).healthy(2000)
        assertEquals(0L, state.started)
        assertTrue(state.failures.isEmpty())
    }
    @Test fun staleHealthCallbackCannotClearANewerOrFailedAttempt() {
        val state = StartupFailureState().begin(1000, 1, 73, null, null).failed(1100)
            .begin(2000, 2, 73, null, null)
        assertEquals(state, state.healthy(1000))
        val failed = state.failed(2100)
        assertEquals(failed, failed.healthy(2000))
    }
    @Test fun failureWindowExpiresWithoutResettingLatchedRecovery() {
        val state = StartupFailureState().begin(1000, 1, 73, null, null).failed(1100)
            .begin(700000, 2, 73, null, null).failed(700100)
        assertEquals(1, state.failures.size)
        assertFalse(state.recovery)
    }
    @Test fun explicitRetryAllowsOnlyOneFailedAttemptEvenAfterLongWait() {
        val state = StartupFailureState(recovery = true).retry()
            .begin(1_000_000, 1, 73, null, null).failed(1_000_100)
        assertTrue(state.recovery)
    }
    @Test fun successfulRetryDisarmsImmediateRecovery() {
        val state = StartupFailureState(recovery = true).retry()
            .begin(1000, 1, 73, null, null).healthy(1000)
            .begin(10000, 2, 73, null, null).failed(10100)
        assertFalse(state.recovery)
    }
}
