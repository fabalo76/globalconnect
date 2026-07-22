package one.globalconnect.paymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountEntryStateTest {

    // ── Display value ─────────────────────────────────────────────────

    @Test
    fun `initial state displays 0_00`() {
        assertEquals("0.00", AmountEntryState().displayValue)
    }

    @Test
    fun `integer only displays with padded decimals`() {
        assertEquals("123.00", AmountEntryState("123", null).displayValue)
    }

    @Test
    fun `decimal mode with empty fraction shows two zeros`() {
        assertEquals("123.00", AmountEntryState("123", "").displayValue)
    }

    @Test
    fun `decimal mode with one digit pads to two`() {
        assertEquals("123.40", AmountEntryState("123", "4").displayValue)
    }

    @Test
    fun `decimal mode with two digits`() {
        assertEquals("123.45", AmountEntryState("123", "45").displayValue)
    }

    // ── Transaction value ─────────────────────────────────────────────

    @Test
    fun `transactionValue pads decimals to two digits`() {
        assertEquals("0.00", AmountEntryState().transactionValue)
        assertEquals("123.00", AmountEntryState("123", null).transactionValue)
        assertEquals("123.00", AmountEntryState("123", "").transactionValue)
        assertEquals("123.40", AmountEntryState("123", "4").transactionValue)
        assertEquals("123.45", AmountEntryState("123", "45").transactionValue)
    }

    // ── isZero ────────────────────────────────────────────────────────

    @Test
    fun `isZero returns true for default state`() {
        assertTrue(AmountEntryState().isZero)
    }

    @Test
    fun `isZero returns true for explicit zeros`() {
        assertTrue(AmountEntryState("0", "00").isZero)
    }

    @Test
    fun `isZero returns false when integer is non-zero`() {
        assertFalse(AmountEntryState("1", null).isZero)
    }

    @Test
    fun `isZero returns false when decimal is non-zero`() {
        assertFalse(AmountEntryState("0", "01").isZero)
    }

    // ── appendDigit ───────────────────────────────────────────────────

    @Test
    fun `appendDigit replaces leading zero`() {
        val state = AmountEntryLogic.appendDigit(AmountEntryState(), 1)
        assertEquals("1", state.integerPart)
    }

    @Test
    fun `appendDigit builds integer part`() {
        var s = AmountEntryState()
        s = AmountEntryLogic.appendDigit(s, 1)
        s = AmountEntryLogic.appendDigit(s, 2)
        s = AmountEntryLogic.appendDigit(s, 3)
        assertEquals("123", s.integerPart)
    }

    @Test
    fun `appendDigit in decimal mode adds to decimal part`() {
        var s = AmountEntryState("5", "")
        s = AmountEntryLogic.appendDigit(s, 1)
        assertEquals("1", s.decimalPart)
        s = AmountEntryLogic.appendDigit(s, 0)
        assertEquals("10", s.decimalPart)
    }

    @Test
    fun `appendDigit stops at 2 decimal digits`() {
        val s = AmountEntryState("5", "12")
        val result = AmountEntryLogic.appendDigit(s, 3)
        assertEquals("12", result.decimalPart)
    }

    @Test
    fun `appendDigit stops at 9 integer digits`() {
        val s = AmountEntryState("123456789", null)
        val result = AmountEntryLogic.appendDigit(s, 0)
        assertEquals("123456789", result.integerPart)
    }

    @Test
    fun `appending zero to zero stays zero`() {
        val result = AmountEntryLogic.appendDigit(AmountEntryState(), 0)
        assertEquals("0", result.integerPart)
    }

    // ── appendDoubleZero ──────────────────────────────────────────────

    @Test
    fun `appendDoubleZero in integer mode`() {
        val s = AmountEntryState("12", null)
        val result = AmountEntryLogic.appendDoubleZero(s)
        assertEquals("1200", result.integerPart)
    }

    @Test
    fun `appendDoubleZero on zero stays zero`() {
        val result = AmountEntryLogic.appendDoubleZero(AmountEntryState())
        assertEquals("0", result.integerPart)
    }

    @Test
    fun `appendDoubleZero in decimal mode with empty fraction`() {
        val s = AmountEntryState("5", "")
        val result = AmountEntryLogic.appendDoubleZero(s)
        assertEquals("00", result.decimalPart)
    }

    @Test
    fun `appendDoubleZero in decimal mode with 1 digit adds only 1 zero`() {
        val s = AmountEntryState("5", "1")
        val result = AmountEntryLogic.appendDoubleZero(s)
        assertEquals("10", result.decimalPart)
    }

    @Test
    fun `appendDoubleZero in decimal mode with 2 digits does nothing`() {
        val s = AmountEntryState("5", "12")
        val result = AmountEntryLogic.appendDoubleZero(s)
        assertEquals("12", result.decimalPart)
    }

    @Test
    fun `appendDoubleZero respects max integer digits`() {
        val s = AmountEntryState("12345678", null)  // 8 digits
        val result = AmountEntryLogic.appendDoubleZero(s)
        // Adding 00 would make 10 digits, exceeds 9 → unchanged
        assertEquals("12345678", result.integerPart)
    }

    // ── enterDecimalMode ──────────────────────────────────────────────

    @Test
    fun `enterDecimalMode switches to decimal entry`() {
        val s = AmountEntryState("123", null)
        val result = AmountEntryLogic.enterDecimalMode(s)
        assertEquals("", result.decimalPart)
    }

    @Test
    fun `enterDecimalMode is idempotent`() {
        val s = AmountEntryState("123", "4")
        val result = AmountEntryLogic.enterDecimalMode(s)
        assertEquals("4", result.decimalPart)
    }

    // ── backspace ─────────────────────────────────────────────────────

    @Test
    fun `backspace removes last decimal digit`() {
        val s = AmountEntryState("1200", "50")
        val result = AmountEntryLogic.backspace(s)
        assertEquals("5", result.decimalPart)
    }

    @Test
    fun `backspace exits decimal mode when fraction is empty`() {
        val s = AmountEntryState("1200", "")
        val result = AmountEntryLogic.backspace(s)
        assertEquals(null, result.decimalPart)
        assertEquals("1200", result.integerPart)
    }

    @Test
    fun `backspace removes last integer digit`() {
        val s = AmountEntryState("1200", null)
        val result = AmountEntryLogic.backspace(s)
        assertEquals("120", result.integerPart)
    }

    @Test
    fun `backspace on single digit integer returns to zero`() {
        val s = AmountEntryState("5", null)
        val result = AmountEntryLogic.backspace(s)
        assertEquals("0", result.integerPart)
    }

    @Test
    fun `backspace on zero stays at zero`() {
        val result = AmountEntryLogic.backspace(AmountEntryState())
        assertEquals("0", result.integerPart)
        assertEquals(null, result.decimalPart)
    }

    // ── reset ─────────────────────────────────────────────────────────

    @Test
    fun `reset returns initial state`() {
        val result = AmountEntryLogic.reset()
        assertEquals(AmountEntryState(), result)
    }

    // ── fromPercentage ────────────────────────────────────────────────

    @Test
    fun `fromPercentage calculates correctly`() {
        // 15% of $100.00 (10000 cents) = $15.00
        val result = AmountEntryLogic.fromPercentage(10000, 15)
        assertEquals("15", result.integerPart)
        assertEquals("00", result.decimalPart)
    }

    @Test
    fun `fromPercentage rounds correctly`() {
        // 7% of $10.00 (1000 cents) = $0.70
        val result = AmountEntryLogic.fromPercentage(1000, 7)
        assertEquals("0", result.integerPart)
        assertEquals("70", result.decimalPart)
    }

    @Test
    fun `fromPercentage with fractional cents rounds`() {
        // 15% of $33.33 (3333 cents) = 499.95 → rounds to 500 cents = $5.00
        val result = AmountEntryLogic.fromPercentage(3333, 15)
        assertEquals("5", result.integerPart)
        assertEquals("00", result.decimalPart)
    }

    // ── fromTransactionString ─────────────────────────────────────────

    @Test
    fun `fromTransactionString parses standard format`() {
        val result = AmountEntryLogic.fromTransactionString("123.45")
        assertEquals("123", result.integerPart)
        assertEquals("45", result.decimalPart)
    }

    @Test
    fun `fromTransactionString parses zero`() {
        val result = AmountEntryLogic.fromTransactionString("0.00")
        assertEquals("0", result.integerPart)
        assertEquals("00", result.decimalPart)
    }

    // ── Full scenario from plan ───────────────────────────────────────

    @Test
    fun `full entry scenario`() {
        var s = AmountEntryState()
        assertEquals("0.00", s.displayValue)

        s = AmountEntryLogic.appendDigit(s, 1)
        assertEquals("1.00", s.displayValue)

        s = AmountEntryLogic.appendDigit(s, 2)
        assertEquals("12.00", s.displayValue)

        s = AmountEntryLogic.appendDoubleZero(s)
        assertEquals("1200.00", s.displayValue)

        s = AmountEntryLogic.enterDecimalMode(s)
        assertEquals("1200.00", s.displayValue)

        s = AmountEntryLogic.appendDigit(s, 5)
        assertEquals("1200.50", s.displayValue)

        s = AmountEntryLogic.appendDigit(s, 0)
        assertEquals("1200.50", s.displayValue)

        s = AmountEntryLogic.backspace(s)
        assertEquals("1200.50", s.displayValue)

        s = AmountEntryLogic.backspace(s)
        assertEquals("1200.00", s.displayValue)

        s = AmountEntryLogic.backspace(s)
        assertEquals("1200.00", s.displayValue)

        s = AmountEntryLogic.backspace(s)
        assertEquals("120.00", s.displayValue)
    }

    @Test
    fun `double zero in decimal mode with one digit`() {
        var s = AmountEntryState("5", "1")
        s = AmountEntryLogic.appendDoubleZero(s)
        assertEquals("5.10", s.displayValue)
    }
}
