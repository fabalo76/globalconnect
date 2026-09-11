package one.globalconnect.tms.paymentapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class TMSDataTest {
    @Test
    fun `terminal parses loyalty protocol switches`() {
        val terminal = TMS_Terminal.fromJson(
            JSONObject(
                """{"enableLoyalty":true,"sendAppVersion":true,"ctlsLoyaltyEnabled":true}""",
            ),
        )

        assertTrue(terminal.enableLoyalty)
        assertTrue(terminal.sendAppVersion)
        assertTrue(terminal.ctlsLoyaltyEnabled)
    }

    @Test
    fun terminal_enablesTaxDiscountWhenPercentageIsGreaterThanZero() {
        val terminal = TMS_Terminal(tax1DiscountPercentage = 8.0)

        assertEquals(8.0, terminal.tax1DiscountPercentage, 0.0)
        assertEquals(8.0, terminal.TaxDiscount, 0.0)
        assertTrue(terminal.ApplyTaxDisc)
    }

    @Test
    fun terminal_disablesTaxDiscountWhenPercentageIsZero() {
        val terminal = TMS_Terminal(tax1DiscountPercentage = 0.0)

        assertEquals(0.0, terminal.tax1DiscountPercentage, 0.0)
        assertEquals(0.0, terminal.TaxDiscount, 0.0)
        assertFalse(terminal.ApplyTaxDisc)
    }

    @Test
    fun terminal_enablesOnlinePinByDefaultForLegacyConfigurations() {
        val terminal = TMS_Terminal.fromJson(JSONObject("{}"))

        assertTrue(terminal.onlinePinCap)
    }

    @Test
    fun terminal_mapsExplicitOnlinePinDisablement() {
        val terminal = TMS_Terminal.fromJson(JSONObject("""{"onlinePinCap":false}"""))

        assertFalse(terminal.onlinePinCap)
    }

    @Test
    fun terminal_disablesOfflinePinChangeByDefaultAndMapsExplicitEnablement() {
        val legacyTerminal = TMS_Terminal.fromJson(JSONObject("{}"))
        val enabledTerminal = TMS_Terminal.fromJson(
            JSONObject("""{"enableOfflinePinChange":true}"""),
        )

        assertFalse(legacyTerminal.enableOfflinePinChange)
        assertTrue(enabledTerminal.enableOfflinePinChange)
    }

    @Test
    fun terminal_disablesOfflinePinUnblockByDefaultAndMapsExclusiveEnablement() {
        val legacyTerminal = TMS_Terminal.fromJson(JSONObject("{}"))
        val enabledTerminal = TMS_Terminal.fromJson(
            JSONObject("""{"enableOfflinePinUnblock":true}"""),
        )

        assertFalse(legacyTerminal.enableOfflinePinUnblock)
        assertTrue(enabledTerminal.enableOfflinePinUnblock)
        assertFalse(enabledTerminal.enableOfflinePinChange)
    }

    @Test
    fun terminal_mapsHotelTransactionConfiguration() {
        val terminal = TMS_Terminal.fromJson(
            JSONObject(
                """{
                    "checkInType":"03",
                    "autoFolio":true,
                    "folioInputMode":"alphanumeric",
                    "allowCheckinManualDataEntry":true
                }""",
            ),
        )

        assertEquals("03", terminal.checkInType)
        assertTrue(terminal.autoFolio)
        assertTrue(terminal.AutoFolio)
        assertEquals("alphanumeric", terminal.folioInputMode)
        assertTrue(terminal.allowCheckinManualDataEntry)
    }

    @Test
    fun terminal_usesBackwardCompatibleHotelDefaults() {
        val terminal = TMS_Terminal.fromJson(JSONObject("{}"))

        assertEquals("01", terminal.checkInType)
        assertFalse(terminal.autoFolio)
        assertEquals("numeric", terminal.folioInputMode)
        assertFalse(terminal.allowCheckinManualDataEntry)
    }

    @Test
    fun terminal_mapsActionPasswordsWithoutLosingLeadingZeroes() {
        val terminal = TMS_Terminal.fromJson(
            JSONObject(
                """{
                    "bankPassword":"0123",
                    "voidPassword":"1234",
                    "reportPassword":"2345",
                    "settlementPassword":"3456"
                }""",
            ),
        )

        assertEquals("0123", terminal.bankPassword)
        assertEquals("1234", terminal.voidPassword)
        assertEquals("2345", terminal.reportPassword)
        assertEquals("3456", terminal.settlementPassword)
    }

    @Test
    fun aidConfigurationsEnableOnlinePinByDefaultAndMapExplicitDisablement() {
        val legacyContact = TMS_EmvContactConfig.fromJson(JSONObject("{}"))
        val disabledContact = TMS_EmvContactConfig.fromJson(JSONObject("""{"onlinePinCap":false}"""))
        val disabledContactless = TMS_EmvCtlsConfig.fromJson(JSONObject("""{"onlinePinCap":0}"""))
        val enabledContactless = TMS_EmvCtlsConfig.fromJson(JSONObject("""{"onlinePinCap":true}"""))

        assertEquals(1, legacyContact.onlinePinCap)
        assertEquals(0, disabledContact.onlinePinCap)
        assertEquals(0, disabledContactless.onlinePinCap)
        assertEquals(1, enabledContactless.onlinePinCap)
    }

    @Test
    fun emvConfigurationsMapInterfaceCvmCapabilitiesAndCurrentAliases() {
        val contact = TMS_EmvContactConfig.fromJson(
            JSONObject(
                """{
                    "onlinePinCap":true,
                    "signarureCap":false,
                    "noCVMCap":true,
                    "offlineEncrPinCap":false,
                    "offlineClearPinCap":true
                }""",
            ),
        )
        val contactless = TMS_EmvCtlsConfig.fromJson(
            JSONObject(
                """{
                    "onlinePinCap":true,
                    "signatureCap":false,
                    "noCVMCap":true
                }""",
            ),
        )
        val terminal = TMS_Terminal.fromJson(
            JSONObject(
                """{
                    "onlinePinCap":true,
                    "signaturePinCap":false,
                    "noCVMPinCap":true,
                    "offlineEncPinCap":false,
                    "offlineClearPinCap":true
                }""",
            ),
        )

        listOf(contact.signatureCap, contactless.signatureCap, terminal.signatureCap).forEach(::assertFalse)
        listOf(contact.noCVMCap, contactless.noCVMCap, terminal.noCVMCap).forEach(::assertTrue)
        listOf(contact.offlineEncrPinCap, terminal.offlineEncrPinCap)
            .forEach(::assertFalse)
        listOf(contact.offlineClearPinCap, terminal.offlineClearPinCap)
            .forEach(::assertTrue)
    }

    @Test
    fun simplifiedContactlessFlagsGenerateVisaKernelValues() {
        val config = TMS_EmvCtlsConfig.fromJson(
            JSONObject(
                """{
                    "aid":"A0000000031010",
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

        assertTrue(config.simplifiedCapabilityFlagsConfigured)
        assertEquals("E0F8C8", config.terminalCapabilities)
        assertEquals("E0", config.cardDataInputCapa)
        assertEquals("60", config.cvmCapaCvmRequired)
        assertEquals("08", config.cvmCapaNoCvmRequired)
        assertEquals("36004000", config.ttq)
        assertEquals("030000", config.kernelId)
        assertEquals("22", config.terminalType)
    }

    @Test
    fun simplifiedContactlessFlagsGenerateMastercardKernelDefaults() {
        val config = TMS_EmvCtlsConfig.fromJson(
            JSONObject(
                """{
                    "aid":"A0000000041010",
                    "manualKeyEntryCap":true,
                    "magneticStripeCap":true,
                    "contactChipCap":true,
                    "offlineClearPinCap":true,
                    "offlineEncrPinCap":true,
                    "sdaCap":true,
                    "ddaCap":true,
                    "cardCaptureCap":false,
                    "cdaCap":true
                }""",
            ),
        )

        assertEquals("E0F8C8", config.terminalCapabilities)
        assertEquals("020000", config.kernelId)
        assertEquals("9F6A04", config.defaultUdol)
        assertEquals("B0", config.kernelConfig)
        assertEquals("08", config.secCapability)
        assertEquals("6C7A800000000000", config.termRiskData)
        assertEquals("10", config.magCvmCapaCvmRequired)
    }

    @Test
    fun mastercardTerminalRiskDataDoesNotAdvertiseDisabledContactlessOnlinePin() {
        val config = TMS_EmvCtlsConfig.fromJson(
            JSONObject(
                """{
                    "aid":"A0000000041010",
                    "onlinePinCap":false,
                    "signatureCap":true,
                    "noCVMCap":true,
                    "offlineClearPinCap":true,
                    "offlineEncrPinCap":true
                }""",
            ),
        )

        assertEquals("2C7A800000000000", config.termRiskData)
    }

    @Test
    fun simplifiedContactlessFlagsClearDependentTechnicalBits() {
        val config = TMS_EmvCtlsConfig.fromJson(
            JSONObject(
                """{
                    "aid":"A0000000031010",
                    "manualKeyEntryCap":false,
                    "magneticStripeCap":true,
                    "contactChipCap":false,
                    "offlineClearPinCap":false,
                    "onlinePinCap":false,
                    "signatureCap":false,
                    "offlineEncrPinCap":false,
                    "noCVMCap":true,
                    "sdaCap":false,
                    "ddaCap":true,
                    "cardCaptureCap":true,
                    "cdaCap":false
                }""",
            ),
        )

        assertEquals("400860", config.terminalCapabilities)
        assertEquals("40", config.cardDataInputCapa)
        assertEquals("00", config.cvmCapaCvmRequired)
        assertEquals("08", config.cvmCapaNoCvmRequired)
        assertEquals("30004000", config.ttq)
    }

    @Test
    fun legacyContactlessConfigurationKeepsRawTechnicalValues() {
        val config = TMS_EmvCtlsConfig.fromJson(
            JSONObject(
                """{
                    "aid":"A0000000031010",
                    "terminalCapabilities":"A1B2C3",
                    "terminalType":"14",
                    "ttq":"B600C000",
                    "kernelConfig":"7F"
                }""",
            ),
        )

        assertFalse(config.simplifiedCapabilityFlagsConfigured)
        assertEquals("A1B2C3", config.terminalCapabilities)
        assertEquals("14", config.terminalType)
        assertEquals("B600C000", config.ttq)
        assertEquals("7F", config.kernelConfig)
    }

    @Test
    fun acquirer_mapsCurrencyAndCountryParametersFromSchemaNames() {
        val acquirer = TMS_Acquirer.fromJson(
            JSONObject(
                """
                {
                  "acquirer_id": "VENTAS",
                  "countryCode": 340,
                  "currencyCode": 340,
                  "currencySymbol": "Lps"
                }
                """.trimIndent()
            )
        )

        assertEquals(340L, acquirer.CountryCode)
        assertEquals(340L, acquirer.CurrencyCode)
        assertEquals("Lps", acquirer.Currency)
    }

    @Test
    fun acquirer_mapsCurrentAndLegacyPinTypes() {
        val current = TMS_Acquirer.fromJson(JSONObject("""{"pinType":3}"""))
        val legacy = TMS_Acquirer.fromJson(JSONObject("""{"PINType":"04"}"""))
        val missing = TMS_Acquirer.fromJson(JSONObject("{}"))

        assertEquals(3, current.PINType)
        assertTrue(current.supportsDukptOnlinePin)
        assertEquals(4, legacy.PINType)
        assertTrue(legacy.supportsDukptOnlinePin)
        assertEquals(0, missing.PINType)
        assertFalse(missing.supportsDukptOnlinePin)
    }

    @Test
    fun acquirer_mapsMasterSessionPinConfiguration() {
        val acquirer = TMS_Acquirer.fromJson(
            JSONObject(
                """{
                    "PINType":"01",
                    "MkID":"00",
                    "sessionKey_A":"3333333333333333",
                    "sessionKey_B":"2222222222222222"
                }"""
            )
        )

        assertEquals(TMS_PinKeyScheme.MKSK, acquirer.pinKeyScheme)
        assertTrue(acquirer.supportsOnlinePin)
        assertEquals(1, acquirer.nexgoPinKeyIndex)
        assertEquals("33333333333333332222222222222222", acquirer.encryptedPinSessionKey)
    }

    @Test
    fun zeroMasterSessionKeySelectsPreloadedPek() {
        val acquirer = TMS_Acquirer.fromJson(
            JSONObject(
                """{
                    "PINType":"01",
                    "MkID":"00",
                    "sessionKey_A":"0000000000000000",
                    "sessionKey_B":"0000000000000000"
                }"""
            )
        )

        assertEquals(null, acquirer.encryptedPinSessionKey)
    }

    @Test
    fun currentPinFieldsUseDirectSlotAndAllFMeansPreloadedPek() {
        val acquirer = TMS_Acquirer.fromJson(
            JSONObject(
                """{
                    "pinType":"1",
                    "pinMasterKeyIndex":"01",
                    "pinStaticSessionKey":"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
                }"""
            )
        )

        assertEquals(TMS_PinKeyScheme.MKSK, acquirer.pinKeyScheme)
        assertEquals(1, acquirer.nexgoPinKeyIndex)
        assertEquals(null, acquirer.encryptedPinSessionKey)
    }

    @Test
    fun currentPinFieldsExposeEncryptedStaticSessionKey() {
        val acquirer = TMS_Acquirer.fromJson(
            JSONObject(
                """{
                    "pinType":"1",
                    "pinMasterKeyIndex":"02",
                    "pinStaticSessionKey":"33333333333333332222222222222222"
                }"""
            )
        )

        assertEquals(2, acquirer.nexgoPinKeyIndex)
        assertEquals("33333333333333332222222222222222", acquirer.encryptedPinSessionKey)
    }

    @Test
    fun acquirer_mapsCurrencyAndCountryParametersFromLegacyNames() {
        val acquirer = TMS_Acquirer.fromJson(
            JSONObject(
                """
                {
                  "acquirer_id": "VENTAS",
                  "CountrCode": 188,
                  "CurrencyCode": 188,
                  "CurrencySymbol": "CRC"
                }
                """.trimIndent()
            )
        )

        assertEquals(188L, acquirer.CountryCode)
        assertEquals(188L, acquirer.CurrencyCode)
        assertEquals("CRC", acquirer.Currency)
    }
}
