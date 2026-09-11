package one.globalconnect.paymentapp.transaction

import com.nexgo.oaf.apiv3.SdkResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies classification of EMV card declines that occur after issuer approval. */
class EmvOnlineOutcomeClassifierTest {
    /** Confirms Nexgo's EMV decline result identifies the card as the final declining party. */
    @Test
    fun hostApprovalFollowedByEmvDeclineIsCardDecline() {
        assertTrue(
            isCardDeclinedAfterOnlineApproval(
                hostApproved = true,
                kernelApproved = false,
                kernelResultCode = SdkResult.Emv_Declined,
                cryptogramInformationData = null,
            )
        )
    }

    /** Confirms an AAC in tag 9F27 identifies a card decline even with a generic kernel result. */
    @Test
    fun finalAacIsCardDecline() {
        assertTrue(
            isCardDeclinedAfterOnlineApproval(
                hostApproved = true,
                kernelApproved = false,
                kernelResultCode = SdkResult.Fail,
                cryptogramInformationData = "00",
            )
        )
    }

    /** Confirms issuer declines are not mislabeled as card declines. */
    @Test
    fun issuerDeclineIsNotCardDecline() {
        assertFalse(
            isCardDeclinedAfterOnlineApproval(
                hostApproved = false,
                kernelApproved = false,
                kernelResultCode = SdkResult.Emv_Declined,
                cryptogramInformationData = "00",
            )
        )
    }

    /** Confirms an expected successful AAC flow remains successful for PIN maintenance. */
    @Test
    fun acceptedKernelOutcomeIsNotCardDecline() {
        assertFalse(
            isCardDeclinedAfterOnlineApproval(
                hostApproved = true,
                kernelApproved = true,
                kernelResultCode = SdkResult.Emv_Declined,
                cryptogramInformationData = "00",
            )
        )
    }
}
