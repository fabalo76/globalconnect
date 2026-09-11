package one.globalconnect.pinpad.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflinePinAttemptTrackerTest {
    /** Verifies retry information is withheld initially and shown after a failed attempt. */
    @Test
    fun reportsRemainingTriesOnlyAfterInitialPrompt() {
        val tracker = OfflinePinAttemptTracker()

        assertNull(tracker.recordPrompt(3))
        assertEquals(2, tracker.recordPrompt(2))
        assertEquals(1, tracker.recordPrompt(1))
    }

    /** Verifies a new transaction starts without a stale failure message. */
    @Test
    fun resetClearsPreviousPromptHistory() {
        val tracker = OfflinePinAttemptTracker()
        tracker.recordPrompt(3)
        tracker.recordPrompt(2)

        tracker.reset()

        assertNull(tracker.recordPrompt(3))
    }
}
