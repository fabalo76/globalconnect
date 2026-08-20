package one.globalconnect.paymentapp.transaction

import com.nexgo.oaf.apiv3.SdkResult
import kotlinx.coroutines.runBlocking
import one.globalconnect.paymentapp.cardreader.EmvKernelCompletion
import one.globalconnect.paymentapp.cardreader.EmvOnlineAuthorizationResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmvOnlineFlowCoordinatorTest {
    @Test
    fun submitsOnlyOneHostResponseToTheKernel() {
        val submittedResponses = mutableListOf<EmvOnlineAuthorizationResponse>()
        val coordinator = EmvOnlineFlowCoordinator(submittedResponses::add)

        coordinator.submitResponse(
            EmvOnlineAuthorizationResponse(
                hostReachable = true,
                responseCode = "00",
                authorizationCode = "123456",
            )
        )
        coordinator.submitResponse(
            EmvOnlineAuthorizationResponse(
                hostReachable = false,
                responseCode = "96",
            )
        )

        assertTrue(coordinator.responseSent)
        assertEquals(1, submittedResponses.size)
        assertEquals("00", submittedResponses.single().responseCode)
    }

    @Test
    fun resumesHostProcessingAfterKernelCompletion() = runBlocking {
        val coordinator = EmvOnlineFlowCoordinator { }
        val completion = EmvKernelCompletion(
            resultCode = SdkResult.Success,
            message = "",
            cardData = null,
        )

        assertTrue(coordinator.complete(completion))
        assertFalse(coordinator.complete(completion))
        assertEquals(completion, coordinator.completion.await())
    }

    @Test
    fun failedResponseDeliveryCanBeRetried() {
        var attempts = 0
        val coordinator = EmvOnlineFlowCoordinator {
            attempts += 1
            if (attempts == 1) error("simulated delivery failure")
        }
        val response = EmvOnlineAuthorizationResponse(
            hostReachable = false,
            responseCode = "91",
        )

        runCatching { coordinator.submitResponse(response) }
        assertFalse(coordinator.responseSent)

        coordinator.submitResponse(response)
        assertTrue(coordinator.responseSent)
        assertEquals(2, attempts)
    }
}
