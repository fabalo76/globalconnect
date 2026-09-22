package one.globalconnect.xtmsagent.diagnostics

import one.globalconnect.xtmsagent.recovery.OwnerRemovalBackend
import one.globalconnect.xtmsagent.recovery.RecoveryOwnerRemoval
import org.junit.Assert.*
import org.junit.Test

class RecoveryOwnerRemovalTest {
    private class Backend(var owner: Boolean = true, val applies: Boolean = true) : OwnerRemovalBackend {
        val calls = mutableListOf<String>()
        var saveFails = false
        override fun isOwner() = owner
        override fun persistOptOut() { calls.add("save"); check(!saveFails) }
        override fun cleanup() { calls.add("cleanup") }
        override fun clearOwner() { calls.add("remove"); if (applies) owner = false }
    }
    @Test fun savesOptOutBeforeRemovalAndVerifiesState() {
        val backend = Backend()
        assertTrue(RecoveryOwnerRemoval.perform(backend))
        assertEquals(listOf("save", "cleanup", "remove"), backend.calls)
    }
    @Test fun acceptedButUnappliedRemovalIsFailure() {
        assertFalse(RecoveryOwnerRemoval.perform(Backend(applies = false)))
    }
    @Test fun alreadyRemovedDoesNotInvokeOwnerOnlyApis() {
        val backend = Backend(owner = false)
        assertTrue(RecoveryOwnerRemoval.perform(backend))
        assertEquals(listOf("save"), backend.calls)
    }
    @Test fun cannotRemoveWithoutDurableOptOut() {
        val backend = Backend().apply { saveFails = true }
        try { RecoveryOwnerRemoval.perform(backend); fail("Expected storage failure") }
        catch (_: IllegalStateException) { assertEquals(listOf("save"), backend.calls) }
    }
}
