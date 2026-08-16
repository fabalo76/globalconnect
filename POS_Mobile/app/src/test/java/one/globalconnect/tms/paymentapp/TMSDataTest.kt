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
