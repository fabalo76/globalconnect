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
