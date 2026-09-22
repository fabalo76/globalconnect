package one.globalconnect.pinpad.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CardEntryGuideTest {
    @Test
    fun recognizesTerminalModelSpellings() {
        for (name in listOf("N6ProLite", "N6 Pro Lite", "N6_PRO_LITE", "NEXGO N6 Pro")) {
            assertEquals(CardEntryModel.N6_PRO_LITE, cardEntryModel(name))
        }
        assertEquals(CardEntryModel.CT20, cardEntryModel("CT20P"))
        assertEquals(CardEntryModel.CT20, cardEntryModel("NEXGO CT20"))
        assertEquals(CardEntryModel.GENERIC, cardEntryModel("unknown"))
    }

    @Test
    fun neverAnimatesDisabledReaders() {
        for (mask in 0..7) {
            val chip = mask and 1 != 0
            val tap = mask and 2 != 0
            val swipe = mask and 4 != 0
            val gestures = entryGestures(chip, tap, swipe)
            assertEquals(chip, CardEntryGesture.INSERT in gestures)
            assertEquals(tap, CardEntryGesture.TAP in gestures)
            assertEquals(swipe, CardEntryGesture.SWIPE in gestures)
            assertEquals(Integer.bitCount(mask), gestures.size)
        }
    }
}
