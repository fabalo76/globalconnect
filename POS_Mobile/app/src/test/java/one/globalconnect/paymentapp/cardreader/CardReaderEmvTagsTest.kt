package one.globalconnect.paymentapp.cardreader

import org.junit.Assert.assertTrue
import org.junit.Test

class CardReaderEmvTagsTest {

    /** Verifies that TSI is requested from the EMV kernel for the online authorization message. */
    @Test
    fun `online EMV tag request includes transaction status information`() {
        assertTrue(EMV_TAG_WHITELIST.any { tag -> tag.equals("9B", ignoreCase = true) })
    }
}
