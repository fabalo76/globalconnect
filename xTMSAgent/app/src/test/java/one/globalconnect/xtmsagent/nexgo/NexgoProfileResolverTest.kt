package one.globalconnect.xtmsagent.nexgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexgoProfileResolverTest {
    @Test
    fun `N6 marketing model resolves to N6S firmware profile`() {
        val profile = NexgoProfileResolver.resolve(null, "N6", null)

        assertEquals("N6S", profile.modelKey)
        assertEquals(80_000_000, profile.detectedCommandBase)
        assertTrue(profile.commandProfileVerified)
        assertEquals(
            NexgoCapabilityState.PENDING_VALIDATION,
            profile.capabilities[NexgoCapability.STANDALONE_BOOT_SOUND],
        )
        assertEquals(
            NexgoCapabilityState.SUPPORTED,
            profile.capabilities[NexgoCapability.POWER_LOGO],
        )
    }

    @Test
    fun `N82 uses verified 80 million fallback when command properties are absent`() {
        val profile = NexgoProfileResolver.resolve("N82", "N82", "")

        assertEquals("N82", profile.modelKey)
        assertEquals(NexgoDisplaySpec(480, 854), profile.display)
        assertTrue(profile.commandProfileVerified)
        assertEquals(
            NexgoCapabilityState.SUPPORTED,
            profile.capabilities[NexgoCapability.POWER_LOGO],
        )
    }

    @Test
    fun `N96 keeps newer controls behind a runtime probe`() {
        val profile = NexgoProfileResolver.resolve("N96", "N96", "90000000")

        assertEquals("N96", profile.modelKey)
        assertEquals(NexgoDisplaySpec(720, 1600), profile.display)
        assertTrue(profile.commandProfileVerified)
        assertEquals(
            NexgoCapabilityState.RUNTIME_PROBE_REQUIRED,
            profile.capabilities[NexgoCapability.EXTENDED_SYSTEM_CONTROLS],
        )
        assertEquals(
            NexgoCapabilityState.SUPPORTED,
            profile.capabilities[NexgoCapability.STANDALONE_BOOT_SOUND],
        )
    }

    @Test
    fun `unexpected command family is reported as a profile mismatch`() {
        val profile = NexgoProfileResolver.resolve("N96", "N96", "80000000")

        assertFalse(profile.commandProfileVerified)
    }

    @Test
    fun `unknown model exposes no Nexgo capabilities`() {
        val profile = NexgoProfileResolver.resolve("UNLISTED", "UNLISTED", null)

        assertFalse(profile.isKnownModel)
        assertTrue(profile.capabilities.values.all { it == NexgoCapabilityState.UNSUPPORTED })
    }

    @Test
    fun `CT20P uses its verified live firmware capability profile`() {
        val profile = NexgoProfileResolver.resolve("CT20P", "CT20P", "70000000")

        assertEquals("CT20P", profile.modelKey)
        assertEquals(NexgoDisplaySpec(480, 800), profile.display)
        assertTrue(profile.commandProfileVerified)
        assertEquals(
            NexgoCapabilityState.SUPPORTED,
            profile.capabilities[NexgoCapability.STANDALONE_BOOT_SOUND],
        )
        assertEquals(
            NexgoCapabilityState.SUPPORTED,
            profile.capabilities[NexgoCapability.POWER_LOGO],
        )
        assertEquals(
            NexgoCapabilityState.RUNTIME_PROBE_REQUIRED,
            profile.capabilities[NexgoCapability.SCHEDULED_REBOOT],
        )
        assertEquals("bootsound.mp3", NexgoSystemAsset.STANDALONE_BOOT_SOUND.fixedFileName(profile))
    }
}
