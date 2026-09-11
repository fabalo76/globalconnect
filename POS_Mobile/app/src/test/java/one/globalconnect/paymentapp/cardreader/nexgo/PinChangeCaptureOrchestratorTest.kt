package one.globalconnect.paymentapp.cardreader.nexgo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinChangeCaptureOrchestratorTest {
    @Test
    fun `DUKPT confirmation uses one KSN and advances it once`() = runBlocking {
        val gateway = FakePinChangeGateway(
            results = listOf(
                entered(PIN_BLOCK, KSN),
                entered(PIN_BLOCK, KSN),
            ),
        )

        val result = PinChangeCaptureOrchestrator(gateway).capture(
            pan = PAN,
            scheme = OnlinePinScheme.DUKPT,
            keyIndex = 1,
            compatibleAcquirerIds = setOf("ACQ1"),
        )

        assertTrue(result is PinChangeCaptureResult.Success)
        assertEquals(KSN, (result as PinChangeCaptureResult.Success).ksn)
        assertEquals(listOf(SecurePinEntryPurpose.NEW_PIN, SecurePinEntryPurpose.CONFIRM_NEW_PIN), gateway.purposes)
        assertEquals(1, gateway.reserveCalls)
        assertEquals(1, gateway.closeCalls)
        assertTrue(gateway.lastAdvance)
    }

    @Test
    fun `DUKPT mismatch consumes the KSN and rejects the PIN`() = runBlocking {
        val gateway = FakePinChangeGateway(
            results = listOf(
                entered(PIN_BLOCK, KSN),
                entered("1411111111111111", KSN),
            ),
        )

        val result = PinChangeCaptureOrchestrator(gateway).capture(
            pan = PAN,
            scheme = OnlinePinScheme.DUKPT,
            keyIndex = 1,
            compatibleAcquirerIds = setOf("ACQ1"),
        )

        assertEquals(
            PinChangeCaptureFailure.PIN_MISMATCH,
            (result as PinChangeCaptureResult.Failure).reason,
        )
        assertEquals(1, gateway.closeCalls)
        assertTrue(gateway.lastAdvance)
    }

    @Test
    fun `first-entry cancellation clears reservation without advancing KSN`() = runBlocking {
        val gateway = FakePinChangeGateway(
            results = listOf(SecurePinEntryResult(SecurePinEntryStatus.CANCELED)),
        )

        val result = PinChangeCaptureOrchestrator(gateway).capture(
            pan = PAN,
            scheme = OnlinePinScheme.DUKPT,
            keyIndex = 1,
            compatibleAcquirerIds = setOf("ACQ1"),
        )

        assertEquals(
            PinChangeCaptureFailure.FIRST_ENTRY_CANCELED,
            (result as PinChangeCaptureResult.Failure).reason,
        )
        assertEquals(1, gateway.closeCalls)
        assertFalse(gateway.lastAdvance)
    }

    @Test
    fun `confirmation cancellation consumes the KSN`() = runBlocking {
        val gateway = FakePinChangeGateway(
            results = listOf(
                entered(PIN_BLOCK, KSN),
                SecurePinEntryResult(SecurePinEntryStatus.CANCELED),
            ),
        )

        val result = PinChangeCaptureOrchestrator(gateway).capture(
            pan = PAN,
            scheme = OnlinePinScheme.DUKPT,
            keyIndex = 1,
            compatibleAcquirerIds = setOf("ACQ1"),
        )

        assertEquals(
            PinChangeCaptureFailure.CONFIRMATION_CANCELED,
            (result as PinChangeCaptureResult.Failure).reason,
        )
        assertTrue(gateway.lastAdvance)
    }

    @Test
    fun `MKSK compares blocks without reserving a KSN`() = runBlocking {
        val gateway = FakePinChangeGateway(
            results = listOf(
                entered(PIN_BLOCK),
                entered(PIN_BLOCK),
            ),
        )

        val result = PinChangeCaptureOrchestrator(gateway).capture(
            pan = PAN,
            scheme = OnlinePinScheme.MKSK,
            keyIndex = 1,
            compatibleAcquirerIds = setOf("ACQ1"),
        )

        assertTrue(result is PinChangeCaptureResult.Success)
        assertEquals(0, gateway.reserveCalls)
        assertEquals(0, gateway.closeCalls)
    }

    /** Creates a successful encrypted PIN-entry result for a test. */
    private fun entered(pinBlock: String, ksn: String = ""): SecurePinEntryResult =
        SecurePinEntryResult(
            status = SecurePinEntryStatus.ENTERED,
            pinBlock = pinBlock,
            ksn = ksn,
        )

    /** Records orchestrator calls while returning queued secure PIN-entry results. */
    private class FakePinChangeGateway(
        results: List<SecurePinEntryResult>,
    ) : PinChangeCaptureGateway {
        private val queuedResults = ArrayDeque(results)
        val purposes = mutableListOf<SecurePinEntryPurpose>()
        var reserveCalls: Int = 0
        var closeCalls: Int = 0
        var lastAdvance: Boolean = false

        /** Returns the fixed test KSN. */
        override fun currentDukptKsn(keyIndex: Int): String = KSN

        /** Records a DUKPT reservation. */
        override fun reserveDukptKsn(keyIndex: Int, ksn: String): Boolean {
            reserveCalls += 1
            return true
        }

        /** Records whether the orchestrator consumed the reserved DUKPT KSN. */
        override fun closeDukptKsnReservation(keyIndex: Int, ksn: String, advance: Boolean): Boolean {
            closeCalls += 1
            lastAdvance = advance
            return true
        }

        /** Returns the next queued encrypted PIN-entry result. */
        override suspend fun captureSecurePin(
            purpose: SecurePinEntryPurpose,
            pan: String,
            scheme: OnlinePinScheme,
            keyIndex: Int,
            compatibleAcquirerIds: Set<String>,
        ): SecurePinEntryResult {
            purposes += purpose
            return queuedResults.removeFirst()
        }
    }

    private companion object {
        const val PAN = "5413330089098934"
        const val PIN_BLOCK = "141234FFFFFFFFFF"
        const val KSN = "FFFF9876543210E00008"
    }
}
