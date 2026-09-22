package one.globalconnect.paymentapp.ecr

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class EcrVoidRejectionTest {
    @Test fun rejectionBeforeConfirmationIsVisibleAndDismissible() = runBlocking {
        EcrVoidInteraction.clear()
        try {
            val result = launch { EcrVoidInteraction.result(false, "Already voided") }
            yield()
            assertNull(EcrVoidInteraction.screen.value)
            assertEquals("Already voided", EcrVoidInteraction.rejectionMessage.value)
            EcrVoidInteraction.back()
            result.join()
            assertNull(EcrVoidInteraction.rejectionMessage.value)
        } finally { EcrVoidInteraction.clear() }
    }
}
