package one.globalconnect.paymentapp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmsParamsReceiverTest {

    @Test
    fun `waits for application initialization before continuing`() = runBlocking {
        val initialized = MutableStateFlow(false)
        launch {
            delay(10L)
            initialized.value = true
        }

        assertTrue(awaitTmsApplicationInitialization(initialized, timeoutMillis = 1_000L))
    }

    @Test
    fun `reports timeout when application initialization never completes`() = runBlocking {
        val initialized = MutableStateFlow(false)

        assertFalse(awaitTmsApplicationInitialization(initialized, timeoutMillis = 10L))
    }

    @Test
    fun `defers parameter update when approved closed transaction remains in live batch`() {
        assertTrue(
            shouldDeferParameterUpdate(
                operationInProgress = false,
                liveTransactionCount = 1,
            ),
        )
    }

    @Test
    fun `defers parameter update when batch status cannot be verified`() {
        assertTrue(
            shouldDeferParameterUpdate(
                operationInProgress = false,
                liveTransactionCount = null,
            ),
        )
    }

    @Test
    fun `defers parameter update while payment operation is active`() {
        assertTrue(
            shouldDeferParameterUpdate(
                operationInProgress = true,
                liveTransactionCount = 0,
            ),
        )
    }

    @Test
    fun `allows parameter update only when payment app is idle and batch is empty`() {
        assertFalse(
            shouldDeferParameterUpdate(
                operationInProgress = false,
                liveTransactionCount = 0,
            ),
        )
    }
}
