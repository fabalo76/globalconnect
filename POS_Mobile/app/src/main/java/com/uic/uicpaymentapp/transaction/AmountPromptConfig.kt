package com.uic.uicpaymentapp.transaction

/**
 * Describes the amounts that should be requested before starting a transaction.
 *
 * @property askAmount Whether the user should be prompted for a base/total amount.
 * @property askTax1 Whether an additional Tax 1 amount should be requested.
 * @property askTax2 Whether an additional Tax 2 amount should be requested.
 * @property askTip Whether a tip amount should be requested.
 * @property tax1ZeroAmountAllowed When true the Tax 1 amount can be left as zero.
 */
data class AmountPromptConfig(
    val askAmount: Boolean,
    val askTax1: Boolean,
    val askTax2: Boolean,
    val askTip: Boolean,
    val tax1ZeroAmountAllowed: Boolean = false,
    val tax1Percentages: List<Int> = emptyList(),
    val tax2Percentages: List<Int> = emptyList(),
    val tipPercentages: List<Int> = emptyList(),
    val currencySymbol: String? = null,
)

val AmountPromptConfig.requiresAmountEntry: Boolean
    get() = askAmount || askTax1 || askTax2 || askTip
