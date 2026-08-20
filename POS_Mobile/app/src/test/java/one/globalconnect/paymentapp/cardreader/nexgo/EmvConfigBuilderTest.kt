package one.globalconnect.paymentapp.cardreader.nexgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import one.globalconnect.tms.paymentapp.TMS_EmvContactConfig
import one.globalconnect.tms.paymentapp.TMS_EmvCtlsConfig
import one.globalconnect.tms.paymentapp.TMS_Terminal

class EmvConfigBuilderTest {
    @Test
    fun terminalDisablementOverridesEnabledAidCapability() {
        assertEquals(0, EmvConfigBuilder.effectiveOnlinePinCap(false, 1))
    }

    @Test
    fun enabledTerminalHonorsEachAidCapability() {
        assertEquals(1, EmvConfigBuilder.effectiveOnlinePinCap(true, 1))
        assertEquals(0, EmvConfigBuilder.effectiveOnlinePinCap(true, 0))
    }

    @Test
    fun disabledOnlinePinIsRemovedFromKernelCvmCapability() {
        assertEquals(0x20, NexgoApi.applyOnlinePinCvmCapability(0x60, false))
        assertEquals(0x40, NexgoApi.applyOnlinePinCvmCapability(0x40, true))
    }

    @Test
    fun disabledOnlinePinIsRemovedFromTerminalTransactionQualifiers() {
        assertEquals(0x32, NexgoApi.applyOnlinePinTtqCapability(0x36, false))
        assertEquals(0x36, NexgoApi.applyOnlinePinTtqCapability(0x36, true))
    }

    @Test
    fun emvCountryAndCurrencyCodesAreEncodedAsTwoByteBcd() {
        val tags = EmvTransactionTags.countryAndCurrency(
            countryCode = "0188",
            currencyCode = "0840",
        )

        assertArrayEquals(byteArrayOf(0x01, 0x88.toByte()), tags?.countryCode)
        assertArrayEquals(byteArrayOf(0x08, 0x40), tags?.currencyCode)
    }

    @Test
    fun emvCountryAndCurrencyAcceptUnpaddedIsoNumericCodes() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x88.toByte()),
            EmvTransactionTags.encodeNumericCode("188"),
        )
        assertArrayEquals(
            byteArrayOf(0x08, 0x40),
            EmvTransactionTags.encodeNumericCode("840"),
        )
    }

    @Test
    fun invalidEmvCountryOrCurrencyIsRejectedInsteadOfWritingBadTlvData() {
        assertEquals(null, EmvTransactionTags.countryAndCurrency("0000", "0840"))
        assertEquals(null, EmvTransactionTags.countryAndCurrency("0188", "USD"))
        assertEquals(null, EmvTransactionTags.encodeNumericCode("1234"))
    }

    @Test
    fun terminalDisablesBothOfflinePinCapabilitiesForContact() {
        val profile = EmvConfigBuilder.buildTerminalCapabilityProfiles(
            aidTab = listOf(
                TMS_EmvContactConfig(
                    aid = "A0000000041010",
                    onlinePinCap = 1,
                    signatureCap = true,
                    noCVMCap = true,
                    offlineEncrPinCap = true,
                    offlineClearPinCap = true,
                ),
            ),
            pcdApps = emptyList(),
            terminal = TMS_Terminal(
                onlinePinCap = true,
                signatureCap = true,
                noCVMCap = true,
                offlineEncrPinCap = false,
                offlineClearPinCap = false,
            ),
        ).single()

        assertEquals(0xF8, profile.controlledCvmMask)
        assertEquals(0x68, profile.enabledCvmMask)
        assertArrayEquals(
            byteArrayOf(0xE0.toByte(), 0x68, 0xC8.toByte()),
            EmvTerminalCapabilities.apply9F33(
                byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte()),
                profile,
            ),
        )
    }

    @Test
    fun contactlessCapabilitiesAlwaysRemoveOfflinePinBits() {
        val profile = EmvConfigBuilder.buildTerminalCapabilityProfiles(
            aidTab = emptyList(),
            pcdApps = listOf(
                TMS_EmvCtlsConfig(
                    aid = "A0000000041010",
                    terminalCapabilities = "E0F8C8",
                    onlinePinCap = 1,
                    signatureCap = true,
                    noCVMCap = true,
                ),
            ),
            terminal = TMS_Terminal(),
        ).single()

        assertEquals(0x68, profile.enabledCvmMask)
        assertArrayEquals(
            byteArrayOf(0xE0.toByte(), 0x68, 0xC8.toByte()),
            EmvTerminalCapabilities.apply9F33(null, profile),
        )
    }

    @Test
    fun capabilityProfileMatchesSelectedCardAidByLongestPrefix() {
        val shortProfile = EmvTerminalCapabilityProfile(
            aid = "A000000004",
            interfaceType = EmvCapabilityInterface.CONTACT,
            base9F33 = null,
            controlledCvmMask = 0xF8,
            enabledCvmMask = 0x40,
        )
        val specificProfile = shortProfile.copy(aid = "A0000000041010", enabledCvmMask = 0x68)

        assertEquals(
            specificProfile,
            EmvTerminalCapabilities.findProfile(
                listOf(shortProfile, specificProfile),
                "A000000004101002",
                EmvCapabilityInterface.CONTACT,
            ),
        )
    }

    @Test
    fun masterSessionAndDukptPinTypesAreSupportedByInternalPinPadFlow() {
        assertEquals(false, NexgoApi.supportsOnlinePinType(0))
        assertEquals(true, NexgoApi.supportsOnlinePinType(1))
        assertEquals(false, NexgoApi.supportsOnlinePinType(2))
        assertEquals(true, NexgoApi.supportsOnlinePinType(3))
        assertEquals(true, NexgoApi.supportsOnlinePinType(4))
    }

    @Test
    fun applicationSelectionUsesOneBasedKernelIndexes() {
        assertEquals(1, NexgoApi.applicationSelectionResponseIndex(0, 2))
        assertEquals(2, NexgoApi.applicationSelectionResponseIndex(1, 2))
    }

    @Test
    fun applicationSelectionRejectsInvalidOrStaleIndexes() {
        assertEquals(null, NexgoApi.applicationSelectionResponseIndex(-1, 2))
        assertEquals(null, NexgoApi.applicationSelectionResponseIndex(2, 2))
        assertEquals(null, NexgoApi.applicationSelectionResponseIndex(0, 0))
    }
}
