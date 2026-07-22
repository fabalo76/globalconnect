package one.globalconnect.paymentapp.transaction

import java.math.BigDecimal
import java.math.RoundingMode

data class Tax1DiscountBreakdown(
    val originalTaxAmount: BigDecimal,
    val discountedTaxAmount: BigDecimal,
    val discountAmount: BigDecimal,
) {
    val originalTaxAmountText: String get() = originalTaxAmount.setScale(2, RoundingMode.DOWN).toPlainString()
    val discountedTaxAmountText: String get() = discountedTaxAmount.setScale(2, RoundingMode.DOWN).toPlainString()
    val discountAmountText: String get() = discountAmount.setScale(2, RoundingMode.DOWN).toPlainString()
    val hasDiscount: Boolean get() = discountAmount > BigDecimal.ZERO
}

object Tax1DiscountCalculator {
    fun calculate(tax1Amount: String, discountPercentage: Double): Tax1DiscountBreakdown {
        val originalTax = tax1Amount.toMoneyAmount()
        if (discountPercentage <= 0.0 || originalTax <= BigDecimal.ZERO) {
            return Tax1DiscountBreakdown(
                originalTaxAmount = originalTax,
                discountedTaxAmount = originalTax,
                discountAmount = BigDecimal.ZERO.setScale(MONEY_SCALE),
            )
        }

        val rawDiscount = originalTax
            .multiply(BigDecimal.valueOf(discountPercentage))
            .divide(HUNDRED, CALCULATION_SCALE, RoundingMode.DOWN)
            .setScale(MONEY_SCALE, RoundingMode.DOWN)
        val discount = rawDiscount.coerceAtMost(originalTax)
        val discountedTax = originalTax.subtract(discount).setScale(MONEY_SCALE, RoundingMode.DOWN)

        return Tax1DiscountBreakdown(
            originalTaxAmount = originalTax,
            discountedTaxAmount = discountedTax,
            discountAmount = discount,
        )
    }

    fun originalTaxAmountFromDiscounted(discountedTaxAmount: String, discountAmount: String): String {
        return discountedTaxAmount.toMoneyAmount()
            .add(discountAmount.toMoneyAmount())
            .toMoneyText()
    }

    private fun String.toMoneyAmount(): BigDecimal {
        return replace(",", "")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.toBigDecimalOrNull()
            ?.setScale(MONEY_SCALE, RoundingMode.DOWN)
            ?: BigDecimal.ZERO.setScale(MONEY_SCALE)
    }

    private fun BigDecimal.toMoneyText(): String = setScale(MONEY_SCALE, RoundingMode.DOWN).toPlainString()

    private const val MONEY_SCALE = 2
    private const val CALCULATION_SCALE = 8
    private val HUNDRED = BigDecimal("100")
}
