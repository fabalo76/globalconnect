package one.globalconnect.paymentapp.cardreader.nexgo

import com.nexgo.oaf.apiv3.emv.EmvProcessFlowEnum
import com.nexgo.oaf.apiv3.emv.EmvTransConfigurationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MobileCvmContinuationTest {

    @Test
    fun `second tap reuses configuration and preserves trace and amount`() {
        val original = EmvTransConfigurationEntity().apply {
            traceNo = "123456"
            transAmount = "000000110000"
            countryCode = "0840"
            currencyCode = "0840"
            transDate = "260827"
            transTime = "235959"
            emvProcessFlowEnum = EmvProcessFlowEnum.EMV_PROCESS_FLOW_STANDARD
        }

        val continuation = prepareMobileCvmContinuationConfiguration(
            config = original,
            transDate = "260828",
            transTime = "183800",
        )

        assertSame(original, continuation)
        assertEquals("123456", continuation.traceNo)
        assertEquals("000000110000", continuation.transAmount)
        assertEquals("0840", continuation.countryCode)
        assertEquals("0840", continuation.currencyCode)
        assertEquals(EmvProcessFlowEnum.EMV_PROCESS_FLOW_STANDARD, continuation.emvProcessFlowEnum)
        assertEquals("260828", continuation.transDate)
        assertEquals("183800", continuation.transTime)
    }
}
