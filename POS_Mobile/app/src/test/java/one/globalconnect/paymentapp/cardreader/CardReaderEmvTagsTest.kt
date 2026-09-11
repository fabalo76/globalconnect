package one.globalconnect.paymentapp.cardreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardReaderEmvTagsTest {

    @Test
    fun `online EMV tag request includes 9F6E`() {
        assertTrue(EMV_TAG_WHITELIST.any { tag -> tag.equals("9F6E", ignoreCase = true) })
    }

    /** Verifies that TSI is requested from the EMV kernel for the online authorization message. */
    @Test
    fun `online EMV tag request includes transaction status information`() {
        assertTrue(EMV_TAG_WHITELIST.any { tag -> tag.equals("9B", ignoreCase = true) })
    }

    /** Verifies that both card-provided application names are retained for receipt printing. */
    @Test
    fun `online EMV tag request includes application names`() {
        assertTrue(EMV_TAG_WHITELIST.any { tag -> tag.equals("9F12", ignoreCase = true) })
        assertTrue(EMV_TAG_WHITELIST.any { tag -> tag.equals("50", ignoreCase = true) })
    }

    /** Cardholder name is fetched separately for printing and must not be sent in DE55. */
    @Test
    fun `online EMV field 55 request excludes cardholder name`() {
        assertFalse(EMV_TAG_WHITELIST.any { tag -> tag.equals("5F20", ignoreCase = true) })
    }

    /** These tags remain available to transaction and certification logic before ISO filtering. */
    @Test
    fun `kernel EMV tag request retains PAN and track data`() {
        val requestedTags = EMV_TAG_WHITELIST.map { tag -> tag.uppercase() }.toSet()

        assertTrue(requestedTags.containsAll(setOf("5A", "56", "57")))
    }

}
