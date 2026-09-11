package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Test

class EmvApplicationNameTest {

    @Test
    fun `preferred name is decoded and takes precedence for MTIP receipt`() {
        val result = resolveEmvApplicationName(
            preferredNameHex = "4D617374657243617264",
            applicationLabelHex = "4D415354455243415244",
            issuerFallback = "MASTER",
        )

        assertEquals("MasterCard", result)
    }

    @Test
    fun `application label is used when preferred name is absent`() {
        val result = resolveEmvApplicationName(
            preferredNameHex = null,
            applicationLabelHex = "4D415354455243415244",
            issuerFallback = "MASTER",
        )

        assertEquals("MASTERCARD", result)
    }

    @Test
    fun `issuer name is used when card application names are unavailable`() {
        val result = resolveEmvApplicationName(
            preferredNameHex = null,
            applicationLabelHex = null,
            issuerFallback = "MASTER",
        )

        assertEquals("MASTER", result)
    }

    @Test
    fun `cardholder name is decoded from EMV tag 5F20`() {
        val result = resolveEmvCardholderName(
            cardholderNameHex = "444F452F4A4F484E",
            track1 = null,
        )

        assertEquals("DOE/JOHN", result)
    }

    @Test
    fun `track one name is used when tag 5F20 is absent`() {
        val result = resolveEmvCardholderName(
            cardholderNameHex = null,
            track1 = "%B5413330089010012^DOE/JOHN^29122010000000000000?",
        )

        assertEquals("DOE/JOHN", result)
    }
}
