package one.globalconnect.xtmsagent.profiles

import one.globalconnect.xtmsagent.nexgo.NexgoProfileResolver
import one.globalconnect.xtmsagent.nexgo.NexgoSystemAsset
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NexgoProfileCommandsTest {
    @Test fun ct20HasItsOwnProfileAndVendorAudioName() {
        val profile = NexgoProfileResolver.resolve("CT20", "CT20", "70000000")
        assertEquals("CT20", profile.modelKey)
        assertTrue(profile.commandProfileVerified)
        assertEquals("bootsound.mp3", NexgoSystemAsset.STANDALONE_BOOT_SOUND.fixedFileName(profile))
    }

    @Test fun rejectsUnverifiedFirmwareAndMismatchedFamilies() {
        assertTrue(NexgoProfileCommands.forFirmware("CT20", 70_000_000, "unknown").isEmpty())
        assertTrue(NexgoProfileCommands.forFirmware("N96", 70_000_000, NexgoProfileCommands.N96_PSS).isEmpty())
        assertTrue(NexgoProfileCommands.forFirmware("N6ProLite", 90_000_000, NexgoProfileCommands.N96_PSS).isEmpty())
        assertTrue(NexgoProfileCommands.forFirmware("CT20", null, NexgoProfileCommands.CT20_PSS).isEmpty())
    }

    @Test fun usesNewEthernetIdAndDoesNotInventCt20MobileCommands() {
        val ct20 = NexgoProfileCommands.forFirmware("CT20", 70_000_000, NexgoProfileCommands.CT20_PSS)
        assertEquals(702_210_181, ct20["ethernetEnabled"])
        assertFalse(ct20.containsKey("mobileDataEnabled"))
        assertFalse(ct20.containsKey("airplaneModeEnabled"))
        val n96 = NexgoProfileCommands.forFirmware("N96", 90_000_000, NexgoProfileCommands.N96_PSS)
        assertEquals(902_506_171, n96["mobileDataEnabled"])
        assertEquals(902_506_172, n96["airplaneModeEnabled"])
        // Keep broken brightness readback and index-based timeout APIs out of this path.
        assertFalse(n96.containsKey("brightnessPercent"))
        assertFalse(n96.containsKey("screenTimeoutSeconds"))
    }

    @Test fun encodesVendorBooleansAsSingleBytesAndTimeZoneAsRawUtf8() {
        assertArrayEquals(byteArrayOf(1), NexgoProfileCommands.payload("wifiEnabled", JSONObject("{\"wifiEnabled\":true}")))
        assertArrayEquals(byteArrayOf(0), NexgoProfileCommands.payload("wifiEnabled", JSONObject("{\"wifiEnabled\":false}")))
        assertArrayEquals("America/Costa_Rica".toByteArray(), NexgoProfileCommands.payload("timeZone", JSONObject("{\"timeZone\":\"America/Costa_Rica\"}")))
    }
}
