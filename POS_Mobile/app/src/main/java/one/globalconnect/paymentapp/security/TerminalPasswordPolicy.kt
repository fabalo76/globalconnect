package one.globalconnect.paymentapp.security

import one.globalconnect.tms.paymentapp.TMS_Terminal

enum class TerminalPasswordAction {
    BANK,
    CONFIGURATION,
    VOID,
    CLEAR,
    ADJUST,
    REFUND,
    REPORT,
    OFFLINE,
    SETTLEMENT,
    CASH_ADVANCE,
}

object TerminalPasswordPolicy {
    private const val NO_PASSWORD = "0000"

    fun passwordFor(
        terminal: TMS_Terminal?,
        action: TerminalPasswordAction,
    ): String {
        if (terminal == null) return NO_PASSWORD
        val actionPassword = when (action) {
            TerminalPasswordAction.BANK -> terminal.bankPassword
            TerminalPasswordAction.CONFIGURATION -> terminal.bankPassword
            TerminalPasswordAction.VOID -> terminal.voidPassword
            TerminalPasswordAction.CLEAR -> terminal.clearPassword
            TerminalPasswordAction.ADJUST -> terminal.adjustPassword
            TerminalPasswordAction.REFUND -> terminal.refundPassword
            TerminalPasswordAction.REPORT -> terminal.reportPassword
            TerminalPasswordAction.OFFLINE -> terminal.offlinePassword
            TerminalPasswordAction.SETTLEMENT -> terminal.settlementPassword
            TerminalPasswordAction.CASH_ADVANCE -> terminal.cashAdvancePassword
        }.trim()
        return actionPassword
            .ifBlank { terminal.bankPassword.trim() }
            .ifBlank { NO_PASSWORD }
    }

    fun requiresPassword(
        terminal: TMS_Terminal?,
        action: TerminalPasswordAction,
    ): Boolean = passwordFor(terminal, action) != NO_PASSWORD

    fun actionForDestination(destination: String): TerminalPasswordAction = when {
        destination == "EndOfDay" -> TerminalPasswordAction.SETTLEMENT
        destination == "Refund" -> TerminalPasswordAction.REFUND
        destination == "Cash" -> TerminalPasswordAction.CASH_ADVANCE
        destination == "SystemSettings" -> TerminalPasswordAction.CONFIGURATION
        destination == "Transactions" || destination.startsWith("Reports") -> {
            TerminalPasswordAction.REPORT
        }
        else -> TerminalPasswordAction.BANK
    }
}
