package one.globalconnect.tms.paymentapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class TMSDataTest {
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
