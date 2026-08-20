package one.globalconnect.paymentapp.cardreader.nexgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlinePinConfigurationTest {
    @Test
    fun resolvesProfileFromMatchingPanRange() {
        val resolver = OnlinePinProfileResolver.fromProfiles(
            listOf(
                OnlinePinProfile(
                    acquirerId = "MKSK",
                    scheme = OnlinePinScheme.MKSK,
                    keyIndex = 1,
                    ranges = listOf(OnlinePinPanRange("400000", "499999", 16)),
                ),
                OnlinePinProfile(
                    acquirerId = "DUKPT",
                    scheme = OnlinePinScheme.DUKPT,
                    keyIndex = 1,
                    ranges = listOf(OnlinePinPanRange("500000", "599999", 16)),
                ),
            )
        )

        val selection = resolver.resolve("5123456789012345")

        assertEquals(OnlinePinScheme.DUKPT, selection?.scheme)
        assertEquals(1, selection?.keyIndex)
        assertEquals(setOf("DUKPT"), selection?.compatibleAcquirerIds)
    }

    @Test
    fun rejectsAmbiguousMixedProfilesWithoutPanMatch() {
        val resolver = OnlinePinProfileResolver.fromProfiles(
            listOf(
                OnlinePinProfile("MKSK", OnlinePinScheme.MKSK, 1, emptyList()),
                OnlinePinProfile("DUKPT", OnlinePinScheme.DUKPT, 1, emptyList()),
            )
        )

        assertNull(resolver.resolve("4111111111111111"))
    }

    @Test
    fun permitsMultipleAcquirersSharingSameSchemeAndIndex() {
        val resolver = OnlinePinProfileResolver.fromProfiles(
            listOf(
                OnlinePinProfile("A", OnlinePinScheme.MKSK, 1, emptyList()),
                OnlinePinProfile("B", OnlinePinScheme.MKSK, 1, emptyList()),
            )
        )

        val selection = resolver.resolve("4111111111111111")

        assertEquals(OnlinePinScheme.MKSK, selection?.scheme)
        assertEquals(setOf("A", "B"), selection?.compatibleAcquirerIds)
    }
}
