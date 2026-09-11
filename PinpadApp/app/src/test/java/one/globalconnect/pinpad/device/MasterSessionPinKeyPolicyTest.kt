package one.globalconnect.pinpad.device

import kotlin.test.Test
import kotlin.test.assertEquals

class MasterSessionPinKeyPolicyTest {
    @Test
    fun p0UsesResidentPinKeyOnlyForAllZeroPlaceholder() {
        assertEquals(
            MasterSessionPinKeyAction.UseResidentPinKey,
            masterSessionPinKeyAction("P0", "0000000000000000"),
        )
        assertEquals(
            MasterSessionPinKeyAction.Reject('1', schemeMismatch = true),
            masterSessionPinKeyAction("P0", "0123456789ABCDEF"),
        )
        assertEquals(
            MasterSessionPinKeyAction.Reject('1', schemeMismatch = true),
            masterSessionPinKeyAction("P0", ""),
        )
        assertEquals(
            MasterSessionPinKeyAction.Reject('1', schemeMismatch = true),
            masterSessionPinKeyAction("P0", "0000"),
        )
    }

    @Test
    fun k0LoadsOnlyANonZeroEncryptedSessionKey() {
        assertEquals(
            MasterSessionPinKeyAction.LoadEncryptedSessionKey,
            masterSessionPinKeyAction("K0", "0123456789ABCDEF"),
        )
        assertEquals(
            MasterSessionPinKeyAction.Reject('1', schemeMismatch = true),
            masterSessionPinKeyAction("K0", "0000000000000000"),
        )
        assertEquals(
            MasterSessionPinKeyAction.Reject('5'),
            masterSessionPinKeyAction("K0", ""),
        )
        assertEquals(
            MasterSessionPinKeyAction.Reject('5'),
            masterSessionPinKeyAction("K0", "NOT-A-KEY"),
        )
        assertEquals(
            MasterSessionPinKeyAction.Reject('5'),
            masterSessionPinKeyAction("K0", "0123"),
        )
    }

    @Test
    fun otherUsagesAreNotAcceptedAsMasterSessionPinKeys() {
        assertEquals(
            MasterSessionPinKeyAction.Reject('A'),
            masterSessionPinKeyAction("M3", "0123456789ABCDEF"),
        )
    }
}
