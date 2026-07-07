package com.uic.uicpaymentapp.transaction

import androidx.compose.runtime.saveable.Saver

/**
 * Represents the state of an integer-first amount entry field.
 *
 * @property integerPart The digits before the decimal point (never empty — at least "0").
 * @property decimalPart `null` while the user is typing integer digits.  After pressing
 *   the decimal-point key it becomes an empty string, then accumulates up to 2 fraction digits.
 */
data class AmountEntryState(
    val integerPart: String = "0",
    val decimalPart: String? = null,
) {
    /** Human-readable value, always with two decimal places: "0.00", "123.00", "123.45". */
    val displayValue: String
        get() {
            val dp = (decimalPart ?: "").padEnd(2, '0').take(2)
            return "$integerPart.$dp"
        }

    /** Always returns "X.XX" for the transaction system. */
    val transactionValue: String
        get() {
            val dp = decimalPart ?: ""
            val padded = dp.padEnd(2, '0').take(2)
            return "$integerPart.$padded"
        }

    val isZero: Boolean
        get() {
            val intZero = integerPart.all { it == '0' }
            val decZero = decimalPart?.all { it == '0' } ?: true
            return intZero && decZero
        }
}

/**
 * Pure functions that produce the next [AmountEntryState] from user actions.
 */
object AmountEntryLogic {

    private const val MAX_INTEGER_DIGITS = 9
    private const val MAX_DECIMAL_DIGITS = 2

    fun appendDigit(state: AmountEntryState, digit: Int): AmountEntryState {
        require(digit in 0..9)
        val d = digit.toString()
        return if (state.decimalPart != null) {
            if (state.decimalPart.length >= MAX_DECIMAL_DIGITS) state
            else state.copy(decimalPart = state.decimalPart + d)
        } else {
            val newInt = appendToInteger(state.integerPart, d)
            if (newInt.length > MAX_INTEGER_DIGITS) state
            else state.copy(integerPart = newInt)
        }
    }

    fun appendDoubleZero(state: AmountEntryState): AmountEntryState {
        return if (state.decimalPart != null) {
            val remaining = MAX_DECIMAL_DIGITS - state.decimalPart.length
            if (remaining <= 0) state
            else {
                val zeros = "00".take(remaining)
                state.copy(decimalPart = state.decimalPart + zeros)
            }
        } else {
            val newInt = appendToInteger(state.integerPart, "00")
            if (newInt.length > MAX_INTEGER_DIGITS) state
            else state.copy(integerPart = newInt)
        }
    }

    fun enterDecimalMode(state: AmountEntryState): AmountEntryState {
        return if (state.decimalPart != null) state
        else state.copy(decimalPart = "")
    }

    fun backspace(state: AmountEntryState): AmountEntryState {
        return when {
            // In decimal mode with digits → remove last decimal digit
            state.decimalPart != null && state.decimalPart.isNotEmpty() ->
                state.copy(decimalPart = state.decimalPart.dropLast(1))
            // In decimal mode with no digits → exit decimal mode
            state.decimalPart != null ->
                state.copy(decimalPart = null)
            // In integer mode → remove last integer digit
            else -> {
                val newInt = state.integerPart.dropLast(1)
                state.copy(integerPart = if (newInt.isEmpty()) "0" else newInt)
            }
        }
    }

    fun reset(): AmountEntryState = AmountEntryState()

    /**
     * Calculates a percentage of [baseAmountCents] and returns the result as an [AmountEntryState].
     *
     * @param baseAmountCents The base amount in cents (e.g. 12345 = $123.45).
     * @param percentage The percentage to calculate (e.g. 15 = 15%).
     */
    fun fromPercentage(baseAmountCents: Long, percentage: Int): AmountEntryState {
        val resultCents = (baseAmountCents * percentage + 50) / 100   // rounded
        val intPart = (resultCents / 100).toString()
        val decPart = (resultCents % 100).let { frac ->
            buildString {
                if (frac < 10) append('0')
                append(frac)
            }
        }
        return AmountEntryState(integerPart = intPart, decimalPart = decPart)
    }

    /**
     * Parses a transaction-format string ("123.45") back into an [AmountEntryState].
     * The returned state is in decimal mode so the display shows cents.
     */
    fun fromTransactionString(value: String): AmountEntryState {
        val parts = value.split('.')
        val intPart = parts.getOrElse(0) { "0" }.ifEmpty { "0" }
        val decPart = if (parts.size > 1) parts[1].take(MAX_DECIMAL_DIGITS) else "00"
        return AmountEntryState(integerPart = intPart, decimalPart = decPart)
    }

    private fun appendToInteger(current: String, digits: String): String {
        return if (current == "0") {
            val trimmed = digits.trimStart('0')
            trimmed.ifEmpty { "0" }
        } else {
            current + digits
        }
    }
}

/**
 * [Saver] for use with `rememberSaveable` so the amount entry state survives
 * configuration changes and process death.
 */
val AmountEntryStateSaver: Saver<AmountEntryState, List<String?>> = Saver(
    save = { listOf(it.integerPart, it.decimalPart) },
    restore = { AmountEntryState(integerPart = it[0] ?: "0", decimalPart = it[1]) },
)
