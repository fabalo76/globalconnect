package one.globalconnect.paymentapp.transaction

import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import one.globalconnect.paymentapp.cardreader.nexgo.NexgoSdkResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies issuer-directed contact fallback decisions. */
class ContactRetryContractTest {
    /** Confirms response 65 restarts a contactless attempt using contact ICC. */
    @Test
    fun response65FromContactlessRequiresContactRetry() {
        assertTrue(
            ContactRetryContract.shouldRetryUsingContact(
                responseCode = "65",
                cardSlot = CardSlotTypeEnum.RF,
            ),
        )
    }

    /** Confirms the extended response 123 has the same contact fallback meaning. */
    @Test
    fun response123FromContactlessRequiresContactRetry() {
        assertTrue(
            ContactRetryContract.shouldRetryUsingContact(
                responseCode = "123",
                cardSlot = CardSlotTypeEnum.RF,
            ),
        )
    }

    /** Confirms response 65 from contact ICC cannot start a retry loop. */
    @Test
    fun response65FromContactDoesNotRetryAgain() {
        assertFalse(
            ContactRetryContract.shouldRetryUsingContact(
                responseCode = "65",
                cardSlot = CardSlotTypeEnum.ICC1,
            ),
        )
    }

    /** Confirms response 123 from contact ICC cannot start a retry loop. */
    @Test
    fun response123FromContactDoesNotRetryAgain() {
        assertFalse(
            ContactRetryContract.shouldRetryUsingContact(
                responseCode = "123",
                cardSlot = CardSlotTypeEnum.ICC1,
            ),
        )
    }

    /** Confirms unrelated issuer declines retain their normal result handling. */
    @Test
    fun anotherResponseCodeDoesNotRequestContactRetry() {
        assertFalse(
            ContactRetryContract.shouldRetryUsingContact(
                responseCode = "05",
                cardSlot = CardSlotTypeEnum.RF,
            ),
        )
    }

    /** MCD93: First GEN AC requests another interface after a contactless tap. */
    @Test
    fun otherInterfaceFromContactlessRequiresContactRetry() {
        assertTrue(
            ContactRetryContract.shouldRetryKernelResultUsingContact(
                resultCode = NexgoSdkResult.Emv_Other_Interface,
                cardSlot = CardSlotTypeEnum.RF,
            ),
        )
    }

    /** The same kernel result on contact ICC must not create a retry loop. */
    @Test
    fun otherInterfaceFromContactDoesNotRetryAgain() {
        assertFalse(
            ContactRetryContract.shouldRetryKernelResultUsingContact(
                resultCode = NexgoSdkResult.Emv_Other_Interface,
                cardSlot = CardSlotTypeEnum.ICC1,
            ),
        )
    }
}
