package one.globalconnect.xtmsagent.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePeerLifecycleGateTest {
    @Test
    fun stopWaitsForActiveNegotiationBeforeFinalizing() {
        val gate = RemotePeerLifecycleGate()

        assertTrue(gate.tryBeginNegotiation())
        val stop = gate.requestStop()

        assertTrue(stop.newlyRequested)
        assertFalse(stop.canFinalize)
        assertFalse(gate.tryFinalize())
        assertTrue(gate.completeNegotiation())
        assertTrue(gate.tryFinalize())
        assertFalse(gate.tryFinalize())
    }

    @Test
    fun stopWithoutNegotiationCanFinalizeImmediately() {
        val gate = RemotePeerLifecycleGate()

        val stop = gate.requestStop()

        assertTrue(stop.newlyRequested)
        assertTrue(stop.canFinalize)
        assertTrue(gate.tryFinalize())
    }

    @Test
    fun noNegotiationCanStartAfterStop() {
        val gate = RemotePeerLifecycleGate()

        gate.requestStop()

        assertTrue(gate.isStopping())
        assertFalse(gate.tryBeginNegotiation())
    }
}
