package one.globalconnect.pinpad.device

import kotlin.test.Test
import kotlin.test.assertEquals

class NewPinConfirmationPolicyTest {
    /** Verifies matching MK/SK encrypted captures return the first response. */
    @Test
    fun confirmsMatchingMasterSessionPinBlocks() {
        val first = ".004010123456789ABCDEF"

        assertEquals(
            NewPinConfirmationPolicy.Result.Confirmed(first),
            NewPinConfirmationPolicy.compare(
                first,
                ".004010123456789abcdef",
                NewPinConfirmationPolicy.Scheme.MASTER_SESSION,
            ),
        )
    }

    /** Verifies MK/SK confirmation detects a different encrypted PIN block. */
    @Test
    fun rejectsDifferentMasterSessionPinBlocks() {
        assertEquals(
            NewPinConfirmationPolicy.Result.Mismatch,
            NewPinConfirmationPolicy.compare(
                ".004010123456789ABCDEF",
                ".00401FEDCBA9876543210",
                NewPinConfirmationPolicy.Scheme.MASTER_SESSION,
            ),
        )
    }

    /** Verifies DUKPT confirmation requires the same reserved KSN for both captures. */
    @Test
    fun confirmsMatchingDukptPinBlocksWithSameKsn() {
        val first = "0FFFF9876543210E000010123456789ABCDEF"

        assertEquals(
            NewPinConfirmationPolicy.Result.Confirmed(first),
            NewPinConfirmationPolicy.compare(
                first,
                "0FFFF9876543210E000010123456789abcdef",
                NewPinConfirmationPolicy.Scheme.DUKPT,
            ),
        )
    }

    /** Verifies changing the KSN between DUKPT entries invalidates confirmation. */
    @Test
    fun rejectsDukptConfirmationWithDifferentKsn() {
        assertEquals(
            NewPinConfirmationPolicy.Result.Invalid,
            NewPinConfirmationPolicy.compare(
                "0FFFF9876543210E000010123456789ABCDEF",
                "0FFFF9876543210E00002FEDCBA9876543210",
                NewPinConfirmationPolicy.Scheme.DUKPT,
            ),
        )
    }

    /** Verifies malformed captures are rejected without comparison. */
    @Test
    fun rejectsMalformedCapture() {
        assertEquals(
            NewPinConfirmationPolicy.Result.Invalid,
            NewPinConfirmationPolicy.compare(
                ".00401NOT-A-PIN-BLOCK",
                ".00401NOT-A-PIN-BLOCK",
                NewPinConfirmationPolicy.Scheme.MASTER_SESSION,
            ),
        )
    }
}
