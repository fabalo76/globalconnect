package one.globalconnect.pinpad.logging

import one.globalconnect.pinpad.protocol.PINPADFrame
import one.globalconnect.pinpad.protocol.PINPADFrameCodec
import one.globalconnect.pinpad.protocol.PINPADFrameType
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionWireSummaryTest {
    @Test fun productionWireSummaryContainsOnlyCommandIdentityNotPayload() {
        val codec = PINPADFrameCodec()
        val cases = listOf("02" to "TEST_SECRET_KEY_MATERIAL", "T28" to "TEST_CARD_DATA", "M23" to "TEST_AUDIO_BASE64")
        for ((command, payload) in cases) {
            val frame = codec.encode(PINPADFrame(PINPADFrameType.Transaction, command, payload.toByteArray()))
            assertEquals("TRANSMITTED command=$command", PinpadTraceLog.diagnosticWireSummary("TX", frame))
        }
    }
}
