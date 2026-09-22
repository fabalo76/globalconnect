package one.globalconnect.paymentapp.ecr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoidConfirmationTest {
    @Test fun cancelPreventsConfirmation() = runBlocking {
        val gate = VoidConfirmation()
        assertTrue(gate.decide(false))
        assertFalse(gate.decide(true))
        assertEquals(false, gate.await())
    }
    @Test fun confirmationCanOnlyBeSubmittedOnce() = runBlocking {
        val gate = VoidConfirmation()
        assertTrue(gate.decide(true))
        assertFalse(gate.decide(true))
        assertFalse(gate.decide(false))
        assertEquals(true, gate.await())
    }
    @Test fun noDecisionTimesOutAsCancellationAndRejectsLateApproval() = runBlocking {
        val gate = VoidConfirmation()
        assertFalse(gate.await(1))
        assertFalse(gate.decide(true))
        assertFalse(gate.await())
    }
}
