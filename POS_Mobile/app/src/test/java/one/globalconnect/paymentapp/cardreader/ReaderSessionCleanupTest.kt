package one.globalconnect.paymentapp.cardreader

import org.junit.Assert.*
import org.junit.Test

class ReaderSessionCleanupTest {
    @Test fun localErrorStillCleansUpRunningSdk() {
        assertTrue(shouldCancelReaderSession(true, false, true))
    }
    @Test fun completedReadDoesNotCancelSensoryPlayback() {
        assertFalse(shouldCancelReaderSession(true, false, false))
    }
    @Test fun lateDisposalNeverCancelsNewScreen() {
        for (local in listOf(false, true)) for (sdk in listOf(false, true)) {
            assertFalse(shouldCancelReaderSession(false, local, sdk))
        }
    }
    @Test fun activeSearchIsCancelled() {
        assertTrue(shouldCancelReaderSession(true, true, true))
    }
}
