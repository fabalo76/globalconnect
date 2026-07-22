package one.globalconnect.paymentapp.utils

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object FormatterUtils {

    /**
     * Formats an integer with leading zeros to match the desired length.
     * Example: paddedInteger(42, 6) -> "000042"
     *
     * @param value The integer to format.
     * @param length The total length of the formatted string (default is 6).
     * @return A zero-padded string representation of the integer.
     */
    fun paddedInteger(value: Int, length: Int = 6): String {
        return String.format(Locale.US, "%0${length}d", value)
    }

    /**
     * Formats a currency amount with the specified currency symbol and decimal places.
     * - `formatAmount("us$", 12.25, 2)  -> "us$ 12.25"`
     * - `formatAmount("Lps", 1234.567, 3) -> "Lps 1,234.567"`
     *
     * @param currencySymbol The currency symbol (e.g., "$", "€").
     * @param amount The monetary amount.
     * @param decimals The number of decimal places (default is 2).
     * @return A formatted currency string.
     */
    fun formatAmount(currencySymbol: String, amount: Double, decimals: Int = 2): String {
        val numberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return "$currencySymbol ${numberFormat.format(amount)}"
    }

    /**
     * Overloaded function that formats a currency amount from a BigDecimal value.
     * - `formatAmount("€", BigDecimal("1234.567"), 3) -> "€ 1,234.567"`
     *
     * @param currencySymbol The currency symbol (e.g., "$", "€").
     * @param amount The monetary amount as a BigDecimal.
     * @param decimals The number of decimal places (default is 2).
     * @return A formatted currency string.
     */
    fun formatAmount(currencySymbol: String, amount: BigDecimal, decimals: Int = 2): String {
        val roundedAmount = amount.setScale(decimals, RoundingMode.HALF_UP) // ✅ Ensures proper rounding
        val numberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return "$currencySymbol ${numberFormat.format(roundedAmount)}"
    }
    /**
     * Formats a currency amount from a string input, applying the specified currency symbol and decimal places.
     *
     * This function safely converts the amount from a string to a double, ensuring that invalid inputs do not cause crashes.
     * If the input is not a valid number, it defaults to `0.0`. The formatted output includes thousand separators based on
     * the US locale.
     *
     * Example usages:
     * - `formatAmount("us$", "12.25", 2)  -> "us$ 12.25"`
     * - `formatAmount("Lps", "1234.567", 3) -> "Lps 1,234.567"`
     * - `formatAmount("Crc", "invalid", 2) -> "Crc 0.00"` (Handles invalid input gracefully)
     *
     * @param currencySymbol The currency symbol to prepend (e.g., "$", "€", "¥").
     * @param amountStr The monetary amount as a string (e.g., "12.25").
     * @param decimals The number of decimal places to format the amount (default is 2).
     * @return A formatted currency string with the specified currency symbol and decimal places.
     */
    fun formatAmount(currencySymbol: String, amountStr: String, decimals: Int = 2): String {
        val amount = amountStr.toDoubleOrNull() ?: 0.0 // ✅ Safe conversion, defaults to 0.0 if invalid
        val numberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return "$currencySymbol ${numberFormat.format(amount)}"
    }
}