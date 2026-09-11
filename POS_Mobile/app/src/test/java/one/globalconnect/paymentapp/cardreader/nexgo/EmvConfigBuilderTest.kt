package one.globalconnect.paymentapp.cardreader.nexgo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import one.globalconnect.tms.paymentapp.TMS_EmvContactConfig
import one.globalconnect.tms.paymentapp.TMS_EmvCtlsConfig
import one.globalconnect.tms.paymentapp.TMS_Terminal

class EmvConfigBuilderTest {
    @Test
    fun contactlessAidUsesApplicationVersionFromConfigured9F09ExtraTag() {
        val aid = EmvConfigBuilder.buildAidList(
            aidTab = emptyList(),
            pcdApps = listOf(
                TMS_EmvCtlsConfig(
                    aid = "A0000000041010",
                    extraTag02Name = "9f09",
                    extraTag02Type = "b",
                    extraTag02Value = "0002",
                ),
            ),
        ).single()

        assertEquals("0002", aid.appVerNum)
    }

    @Test
    fun invalidContactlessApplicationVersionIsNotSentToNexgoSdk() {
        val config = TMS_EmvCtlsConfig(
            aid = "A0000000041010",
            extraTag01Name = "9F09",
            extraTag01Value = "002",
        )

        assertEquals(null, EmvConfigBuilder.configuredContactlessApplicationVersion(config))
    }

    @Test
    fun contactlessApplicationVersionCanBeConfiguredInAnySupportedExtraTagSlot() {
        val config = TMS_EmvCtlsConfig(
            aid = "A0000000041010",
            extraTag05Name = " 9F09 ",
            extraTag05Value = "00 02",
        )

        assertEquals("0002", EmvConfigBuilder.configuredContactlessApplicationVersion(config))
    }

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
    fun contactlessCapabilitiesIncludeEnabledOfflinePinBits() {
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

        assertEquals(0xF8, profile.enabledCvmMask)
        assertArrayEquals(
            byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte()),
            EmvTerminalCapabilities.apply9F33(null, profile),
        )
    }

    @Test
    fun terminalCanDisableContactlessOfflinePinCapabilities() {
        val profile = EmvConfigBuilder.buildTerminalCapabilityProfiles(
            aidTab = emptyList(),
            pcdApps = listOf(
                TMS_EmvCtlsConfig(
                    aid = "A0000000041010",
                    terminalCapabilities = "E0F8C8",
                    offlineClearPinCap = true,
                    offlineEncrPinCap = true,
                ),
            ),
            terminal = TMS_Terminal(
                offlineClearPinCap = false,
                offlineEncrPinCap = false,
            ),
        ).single()

        assertEquals(0x68, profile.enabledCvmMask)
        assertArrayEquals(
            byteArrayOf(0xE0.toByte(), 0x68, 0xC8.toByte()),
            EmvTerminalCapabilities.apply9F33(null, profile),
        )
    }

    @Test
    fun contactlessProfileBuildsVisaTtqFromEffectiveCvmFlags() {
        val profile = EmvConfigBuilder.buildTerminalCapabilityProfiles(
            aidTab = emptyList(),
            pcdApps = listOf(
                TMS_EmvCtlsConfig(
                    aid = "A0000000031010",
                    terminalCapabilities = "E068C8",
                    onlinePinCap = 1,
                    signatureCap = false,
                    noCVMCap = true,
                ),
            ),
            terminal = TMS_Terminal(),
        ).single()

        assertArrayEquals(
            byteArrayOf(0x34, 0x00, 0x40, 0x00),
            profile.ttq,
        )
    }

    @Test
    fun terminalCvmFlagsOverrideAidWhenBuildingContactlessTtq() {
        val profile = EmvConfigBuilder.buildTerminalCapabilityProfiles(
            aidTab = emptyList(),
            pcdApps = listOf(
                TMS_EmvCtlsConfig(
                    aid = "A0000000031010",
                    terminalCapabilities = "E068C8",
                    onlinePinCap = 1,
                    signatureCap = true,
                ),
            ),
            terminal = TMS_Terminal(
                onlinePinCap = false,
                signatureCap = false,
            ),
        ).single()

        assertArrayEquals(
            byteArrayOf(0x30, 0x00, 0x40, 0x00),
            profile.ttq,
        )
    }

    @Test
    fun simplifiedMastercardProfileCarriesDerivedKernelValues() {
        val configuredAid = TMS_EmvCtlsConfig.fromJson(
            JSONObject(
                """{
                    "aid":"A0000000041010",
                    "manualKeyEntryCap":true,
                    "magneticStripeCap":true,
                    "contactChipCap":true,
                    "offlineClearPinCap":true,
                    "onlinePinCap":true,
                    "signatureCap":true,
                    "offlineEncrPinCap":true,
                    "noCVMCap":true,
                    "sdaCap":true,
                    "ddaCap":true,
                    "cardCaptureCap":false,
                    "cdaCap":true
                }""",
            ),
        )
        val profile = EmvConfigBuilder.buildTerminalCapabilityProfiles(
            aidTab = emptyList(),
            pcdApps = listOf(configuredAid),
            terminal = TMS_Terminal(signatureCap = false),
        ).single()

        assertEquals("E0D8C8", profile.contactlessTechnicalValues?.terminalCapabilities)
        assertEquals("40", profile.contactlessTechnicalValues?.cvmCapabilityRequired)
        assertEquals("B0", profile.contactlessTechnicalValues?.kernelConfiguration)
        assertEquals("08", profile.contactlessTechnicalValues?.securityCapability)
    }

    @Test
    fun offlinePinChangeCvmPolicyKeepsOnlyConfiguredOfflinePinCapabilities() {
        assertArrayEquals(
            byteArrayOf(0xE0.toByte(), 0x90.toByte(), 0xC8.toByte()),
            EmvTerminalCapabilities.applyOfflinePinChangeCvmPolicy(
                byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte()),
            ),
        )
        assertArrayEquals(
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0xC8.toByte()),
            EmvTerminalCapabilities.applyOfflinePinChangeCvmPolicy(
                byteArrayOf(0xE0.toByte(), 0xE8.toByte(), 0xC8.toByte()),
            ),
        )
    }

    @Test
    fun offlinePinChangeCvmPolicyRejectsAidWithoutOfflinePinCapability() {
        assertEquals(
            null,
            EmvTerminalCapabilities.applyOfflinePinChangeCvmPolicy(
                byteArrayOf(0xE0.toByte(), 0x68, 0xC8.toByte()),
            ),
        )
    }

    @Test
    fun offlinePinUnblockCvmPolicyKeepsOnlyNoCvm() {
        assertArrayEquals(
            byteArrayOf(0xE0.toByte(), 0x08, 0xC8.toByte()),
            EmvTerminalCapabilities.applyOfflinePinUnblockCvmPolicy(
                byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte()),
            ),
        )
    }

    @Test
    fun offlinePinUnblockCvmPolicyRejectsAidWithoutNoCvm() {
        assertEquals(
            null,
            EmvTerminalCapabilities.applyOfflinePinUnblockCvmPolicy(
                byteArrayOf(0xE0.toByte(), 0xF0.toByte(), 0xC8.toByte()),
            ),
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
