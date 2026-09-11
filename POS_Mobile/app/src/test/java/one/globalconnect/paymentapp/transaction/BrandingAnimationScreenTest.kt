package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingAnimationScreenTest {

    @Test
    fun `contact chip requires a card removal gate before leaving the result`() {
        assertTrue(requiresContactCardRemoval("EMV"))
    }

    @Test
    fun `contactless does not require a contact card removal gate`() {
        assertFalse(requiresContactCardRemoval("EMV_CONTACTLESS"))
    }
}
