package one.globalconnect.paymentapp.transaction

import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import one.globalconnect.paymentapp.cardreader.nexgo.NexgoSdkResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactlessReadRetryPolicyTest {
    @Test fun retriesRepeatedEmptyCandidatesForContactlessBeforeAuthorization() {
        val code = NexgoSdkResult.Emv_Candidatelist_Empty
        repeat(3) { assertTrue(ContactlessReadRetryPolicy.shouldRetry(code, CardSlotTypeEnum.RF, false)) }
    }

    @Test fun emptyCandidatesDoNotRetryAfterAuthorizationOrForContact() {
        val code = NexgoSdkResult.Emv_Candidatelist_Empty
        assertFalse(ContactlessReadRetryPolicy.shouldRetry(code, CardSlotTypeEnum.RF, true))
        assertFalse(ContactlessReadRetryPolicy.shouldRetry(code, CardSlotTypeEnum.ICC1, false))
        assertFalse(ContactlessReadRetryPolicy.shouldRetry(code, null, false))
    }

    @Test fun retriesObservedFailureBeforeAuthorization() {
        assertTrue(ContactlessReadRetryPolicy.shouldRetry(-1, CardSlotTypeEnum.RF, false))
    }

    @Test fun retriesCardCommunicationErrorsBeforeAuthorization() {
        assertTrue(ContactlessReadRetryPolicy.shouldRetry(NexgoSdkResult.Emv_Communicate_Timeout, CardSlotTypeEnum.RF, false))
        assertTrue(ContactlessReadRetryPolicy.shouldRetry(NexgoSdkResult.Picc_Card_Sense_Err, CardSlotTypeEnum.RF, false))
    }

    @Test fun neverRestartsAfterAuthorizationBegins() {
        assertFalse(ContactlessReadRetryPolicy.shouldRetry(-1, CardSlotTypeEnum.RF, true))
        assertFalse(ContactlessReadRetryPolicy.shouldRetry(NexgoSdkResult.Emv_Communicate_Timeout, CardSlotTypeEnum.RF, true))
    }

    @Test fun preservesDeclinesCancellationAndContactFallback() {
        listOf(NexgoSdkResult.Emv_Declined, NexgoSdkResult.Emv_Offline_Declined,
            NexgoSdkResult.Cancel, NexgoSdkResult.Emv_Cancel, NexgoSdkResult.TimeOut,
            NexgoSdkResult.Emv_Other_Interface, NexgoSdkResult.Emv_Plz_See_Phone,
            NexgoSdkResult.Emv_Cvm_Fail, NexgoSdkResult.Success, null).forEach {
            assertFalse("Unexpected retry for $it", ContactlessReadRetryPolicy.shouldRetry(it, CardSlotTypeEnum.RF, false))
        }
    }

    @Test fun doesNotRetryContactSwipeOrUnknownInterface() {
        listOf(CardSlotTypeEnum.ICC1, CardSlotTypeEnum.SWIPE, null).forEach {
            assertFalse(ContactlessReadRetryPolicy.shouldRetry(-1, it, false))
        }
    }
}
