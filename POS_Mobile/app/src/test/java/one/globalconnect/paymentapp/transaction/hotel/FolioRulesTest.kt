package one.globalconnect.paymentapp.transaction.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioRulesTest {

    @Test
    fun numericFolio_keepsDigitsLimitsLengthAndPadsToSix() {
        assertEquals("123456789012345", FolioRules.sanitize("12A3456789012345Z", FolioInputMode.Numeric))
        assertEquals("000123", FolioRules.normalize("123", FolioInputMode.Numeric))
    }

    @Test
    fun alphanumericFolio_keepsLettersAndDigitsUppercasesAndPadsToTen() {
        assertEquals("ABC123", FolioRules.sanitize("ab-c 123", FolioInputMode.Alphanumeric))
        assertEquals("0000ABC123", FolioRules.normalize("ab-c 123", FolioInputMode.Alphanumeric))
    }

    @Test
    fun checkInType_mapsAllA10ModesAndUsesSafeDefault() {
        assertEquals(HotelCheckInType.ByFolio, HotelCheckInType.fromTms("01"))
        assertEquals(HotelCheckInType.ByAuthorizationId, HotelCheckInType.fromTms("2"))
        assertEquals(HotelCheckInType.ByCentralFolio, HotelCheckInType.fromTms("03"))
        assertEquals(HotelCheckInType.ByFolio, HotelCheckInType.fromTms("unexpected"))
        assertTrue(HotelCheckInType.ByFolio.requiresFolio)
        assertFalse(HotelCheckInType.ByAuthorizationId.requiresFolio)
    }

    @Test
    fun folioInputMode_acceptsCanonicalAndLegacyNames() {
        assertEquals(FolioInputMode.Alphanumeric, FolioInputMode.fromTms("ALPHANUMERIC"))
        assertEquals(FolioInputMode.Alphanumeric, FolioInputMode.fromTms("alpha"))
        assertEquals(FolioInputMode.Numeric, FolioInputMode.fromTms("numeric"))
        assertEquals(FolioInputMode.Numeric, FolioInputMode.fromTms("unknown"))
    }
}
