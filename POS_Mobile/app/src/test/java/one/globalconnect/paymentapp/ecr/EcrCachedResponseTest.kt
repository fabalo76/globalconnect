package one.globalconnect.paymentapp.ecr

import org.junit.Assert.assertEquals
import org.junit.Test

class EcrCachedResponseTest {
    private val legacy = EcrMessage("42", "TO", 1, mapOf(
        "80" to "request-1", "00" to "TO",
        "02" to "Void confirmation timed out; nothing sent to host",
    ))

    @Test fun legacyConfirmationExpiryIsCanceledAndKeepsRequestId() {
        val result = legacy.normalizeLegacyVoidCancellation()
        assertEquals("UC", result.response)
        assertEquals("UC", result.fields["00"])
        assertEquals("Void cancelled", result.fields["02"])
        assertEquals("request-1", result.fields["80"])
        assertEquals(result, result.normalizeLegacyVoidCancellation())
    }

    @Test fun unknownHostOutcomesAndOtherOperationsAreUnchanged() {
        val cases = listOf(
            legacy.copy(fields = legacy.fields + ("02" to "Void outcome unknown; reconcile terminal")),
            legacy.copy(command = "20"),
            legacy.copy(response = "00"),
            legacy.copy(indicator = 0),
            legacy.copy(more = true),
            legacy.copy(fields = legacy.fields - "02"),
        )
        cases.forEach { assertEquals(it, it.normalizeLegacyVoidCancellation()) }
    }
}
