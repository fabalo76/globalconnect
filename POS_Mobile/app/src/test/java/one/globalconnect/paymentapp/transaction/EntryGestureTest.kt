package one.globalconnect.paymentapp.transaction

import one.globalconnect.paymentapp.cardreader.CardEntryInterfaces
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryGestureTest {
    @Test
    fun `guide matches all eight reader interface combinations`() {
        for (mask in 0..7) {
            val interfaces = CardEntryInterfaces(
                chip = mask and 1 != 0,
                contactless = mask and 2 != 0,
                swipe = mask and 4 != 0,
            )
            val gestures = enabledEntryGestures(interfaces)
            assertEquals(interfaces.chip, EntryGesture.INSERT in gestures)
            assertEquals(interfaces.contactless, EntryGesture.TAP in gestures)
            assertEquals(interfaces.swipe, EntryGesture.SWIPE in gestures)
            assertEquals(Integer.bitCount(mask), gestures.size)
        }
    }
}
