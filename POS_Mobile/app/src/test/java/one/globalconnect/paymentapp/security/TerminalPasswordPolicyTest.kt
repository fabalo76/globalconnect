package one.globalconnect.paymentapp.security

import one.globalconnect.tms.paymentapp.TMS_Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPasswordPolicyTest {

    @Test
    fun `uses action password when explicitly configured`() {
        val terminal = TMS_Terminal(
            bankPassword = "1111",
            settlementPassword = "2222",
        )

        assertEquals(
            "2222",
            TerminalPasswordPolicy.passwordFor(terminal, TerminalPasswordAction.SETTLEMENT),
        )
    }

    @Test
    fun `falls back to bank password when action password is absent`() {
        val terminal = TMS_Terminal(
            bankPassword = "1111",
            reportPassword = "",
        )

        assertEquals(
            "1111",
            TerminalPasswordPolicy.passwordFor(terminal, TerminalPasswordAction.REPORT),
        )
    }

    @Test
    fun `explicit 0000 disables prompt even when bank password is protected`() {
        val terminal = TMS_Terminal(
            bankPassword = "1111",
            voidPassword = "0000",
        )

        assertFalse(
            TerminalPasswordPolicy.requiresPassword(terminal, TerminalPasswordAction.VOID),
        )
    }

    @Test
    fun `non-0000 effective password requires prompt`() {
        val terminal = TMS_Terminal(
            bankPassword = "1111",
            reportPassword = "",
        )

        assertTrue(
            TerminalPasswordPolicy.requiresPassword(terminal, TerminalPasswordAction.REPORT),
        )
    }

    @Test
    fun `maps protected destinations to their action passwords`() {
        assertEquals(
            TerminalPasswordAction.SETTLEMENT,
            TerminalPasswordPolicy.actionForDestination("EndOfDay"),
        )
        assertEquals(
            TerminalPasswordAction.REPORT,
            TerminalPasswordPolicy.actionForDestination("Reports/Audit"),
        )
        assertEquals(
            TerminalPasswordAction.REFUND,
            TerminalPasswordPolicy.actionForDestination("Refund"),
        )
    }
}
