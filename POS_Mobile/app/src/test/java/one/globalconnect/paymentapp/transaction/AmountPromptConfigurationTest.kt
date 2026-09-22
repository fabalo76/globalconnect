package one.globalconnect.paymentapp.transaction

import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_Terminal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountPromptConfigurationTest {
    private fun saleConfig(terminal: TMS_Terminal, acquirers: List<TMS_Acquirer> = emptyList()) =
        requireNotNull(TransactionConfigRegistry.amountPromptConfigFor(
            TransactionType.SALE.toTransactionString(), terminal, acquirers,
        ))

    @Test
    fun openTipUsesTerminalSettingWithoutAcquirerTipMode() {
        val config = saleConfig(TMS_Terminal(tipProcessingMode = "open", tax1Enabled = true, tax1Mandatory = true))
        assertTrue(config.askTax1)
        assertTrue(config.askTip)
        assertFalse(config.tax1ZeroAmountAllowed)
    }

    @Test
    fun explicitDisabledTipOverridesLegacyAcquirerMode() {
        assertFalse(saleConfig(TMS_Terminal(tipProcessingMode = "none"),
            listOf(TMS_Acquirer(tipProcessingMode = 1L))).askTip)
    }

    @Test
    fun absentTerminalTipModePreservesLegacyAcquirerConfiguration() {
        assertTrue(saleConfig(TMS_Terminal(), listOf(TMS_Acquirer(tipProcessingMode = 1L))).askTip)
        assertTrue(saleConfig(TMS_Terminal(tipProcessingMode = "manual")).askTip)
    }

    @Test
    fun mandatoryTaxRejectsUntouchedZeroButAcceptsExplicitZero() {
        val config = saleConfig(TMS_Terminal(tax1Enabled = true, tax1Mandatory = true))
        assertFalse(config.canConfirmTax1(AmountEntryState(), zeroEntered = false))
        assertTrue(config.canConfirmTax1(AmountEntryLogic.appendDigit(AmountEntryState(), 0), zeroEntered = true))
        assertTrue(config.canConfirmTax1(AmountEntryLogic.appendDoubleZero(AmountEntryState()), zeroEntered = true))
    }

    @Test
    fun optionalTaxAcceptsEnterWithoutDigits() {
        val config = saleConfig(TMS_Terminal(tax1Enabled = true, tax1Mandatory = false))
        assertTrue(config.canConfirmTax1(AmountEntryState(), zeroEntered = false))
    }

    @Test
    fun mandatoryTaxAcceptsPositiveAmountAndRejectsErasedAmount() {
        val config = saleConfig(TMS_Terminal(tax1Enabled = true, tax1Mandatory = true))
        val entered = AmountEntryLogic.appendDigit(AmountEntryState(), 5)
        assertTrue(config.canConfirmTax1(entered, zeroEntered = false))
        assertFalse(config.canConfirmTax1(AmountEntryLogic.backspace(entered), zeroEntered = false))
    }
}
