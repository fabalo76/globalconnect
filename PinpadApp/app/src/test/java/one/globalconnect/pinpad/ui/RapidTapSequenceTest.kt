package one.globalconnect.pinpad.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidTapSequenceTest {
    @Test
    fun opensOnTenthRapidTap() {
        val sequence = RapidTapSequence(requiredTaps = 10, maximumGapMs = 650)

        repeat(9) { index ->
            assertFalse(sequence.registerTap(index * 100L))
        }
        assertTrue(sequence.registerTap(900L))
    }

    @Test
    fun slowGapRestartsSequence() {
        val sequence = RapidTapSequence(requiredTaps = 3, maximumGapMs = 650)

        assertFalse(sequence.registerTap(0L))
        assertFalse(sequence.registerTap(100L))
        assertFalse(sequence.registerTap(1_000L))
        assertFalse(sequence.registerTap(1_100L))
        assertTrue(sequence.registerTap(1_200L))
    }

    @Test
    fun resetDiscardsPartialSequence() {
        val sequence = RapidTapSequence(requiredTaps = 2, maximumGapMs = 650)

        assertFalse(sequence.registerTap(0L))
        sequence.reset()
        assertFalse(sequence.registerTap(100L))
        assertTrue(sequence.registerTap(200L))
    }
}
