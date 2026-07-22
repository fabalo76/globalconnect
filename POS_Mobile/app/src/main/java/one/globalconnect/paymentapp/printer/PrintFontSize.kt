package one.globalconnect.paymentapp.printer

import one.globalconnect.paymentapp.transaction.PrintableTotalsReport

/**
 * Represents a printable totals report with pre-formatted sections and lines. The
 * [PrintableTotalsReport] structure is consumed by the different printer
 * implementations to produce consistent Totals reports across devices.
 */
enum class PrintFontSize {
    MIN,
    TINY,
    SMALL,
    MEDIUM,
    LARGE,
    MASSIVE,
}
