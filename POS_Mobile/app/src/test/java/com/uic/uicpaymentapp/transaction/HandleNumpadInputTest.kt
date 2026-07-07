package com.uic.uicpaymentapp.transaction

import org.junit.Assert.assertEquals
import org.junit.Test

class HandleNumpadInputTest {

    @Test
    fun `appending digits builds amount correctly`() {
        var value = "0.00"
        value = handleNumpadInput(value, "1")
        assertEquals("0.01", value)

        value = handleNumpadInput(value, "2")
        assertEquals("0.12", value)

        value = handleNumpadInput(value, "3")
        assertEquals("1.23", value)
    }

    @Test
    fun `clear removes least significant digit`() {
        var value = "12.34"
        value = handleNumpadInput(value, "C")
        assertEquals("1.23", value)

        value = handleNumpadInput(value, "C")
        assertEquals("0.12", value)
    }

    @Test
    fun `reset clears the amount`() {
        val value = handleNumpadInput("12.34", "R")
        assertEquals("", value)
    }

    @Test
    fun `max length is respected`() {
        var value = "999999999.99"
        // Attempting to add another digit should keep the existing value
        value = handleNumpadInput(value, "9")
        assertEquals("999999999.99", value)
    }
}
