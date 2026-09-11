package one.globalconnect.paymentapp.transaction

import java.math.BigDecimal
import java.math.RoundingMode

private const val MONEY_SCALE = 2

/** Monetary components for one authorization request or one approved portion. */
data class PartialApprovalAmounts(
    val base: BigDecimal,
    val tax1: BigDecimal,
    val tax1Discount: BigDecimal,
    val tax2: BigDecimal,
    val tip: BigDecimal,
) {
    val total: BigDecimal
        get() = base.add(tax1).add(tax2).add(tip).money()

    fun normalized(): PartialApprovalAmounts = copy(
        base = base.money(),
        tax1 = tax1.money(),
        tax1Discount = tax1Discount.money(),
        tax2 = tax2.money(),
        tip = tip.money(),
    )

    companion object {
        fun fromStrings(
            base: String,
            tax1: String,
            tax1Discount: String,
            tax2: String,
            tip: String,
        ): PartialApprovalAmounts = PartialApprovalAmounts(
            base = base.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tax1 = tax1.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tax1Discount = tax1Discount.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tax2 = tax2.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tip = tip.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        ).normalized()
    }
}

data class PartialApprovalAllocation(
    val original: PartialApprovalAmounts,
    val approved: PartialApprovalAmounts,
    val remaining: PartialApprovalAmounts,
) {
    val approvedPercentage: BigDecimal
        get() = approved.total.divide(original.total, 8, RoundingMode.HALF_UP)
}

/** Mastercard response-code 10 handling shared by the transaction flow and tests. */
object PartialApprovalContract {
    const val RESPONSE_CODE = "10"

    fun isSupported(transactionType: TransactionType): Boolean = transactionType in setOf(
        TransactionType.SALE,
        TransactionType.PAYMENT,
        TransactionType.CASH,
        TransactionType.LOYALTY_SALE,
        TransactionType.QUOTA_SALE,
        TransactionType.EXTRAS_SALE,
        TransactionType.CHECKIN,
        TransactionType.CHECKOUT,
    )

    /** ISO field 4 is a fixed-width amount in minor currency units. */
    fun parseApprovedAmount(field4: String?): BigDecimal? {
        val value = field4?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (value.all(Char::isDigit)) {
            value.toBigDecimalOrNull()?.movePointLeft(MONEY_SCALE)?.money()
        } else {
            value.toBigDecimalOrNull()?.money()
        }
    }

    fun allocate(
        original: PartialApprovalAmounts,
        approvedTotal: BigDecimal,
    ): PartialApprovalAllocation? {
        val normalizedOriginal = original.normalized()
        val normalizedApprovedTotal = approvedTotal.money()
        if (normalizedApprovedTotal <= BigDecimal.ZERO ||
            normalizedApprovedTotal >= normalizedOriginal.total
        ) {
            return null
        }

        val ratio = normalizedApprovedTotal.divide(
            normalizedOriginal.total,
            12,
            RoundingMode.HALF_UP,
        )
        val approvedTax1 = normalizedOriginal.tax1.multiply(ratio).money()
        val approvedTax2 = normalizedOriginal.tax2.multiply(ratio).money()
        val approvedTip = normalizedOriginal.tip.multiply(ratio).money()
        val approvedDiscount = normalizedOriginal.tax1Discount.multiply(ratio).money()

        // Put the final rounding cent on base so the authorization amount always reconciles.
        val approvedBase = normalizedApprovedTotal
            .subtract(approvedTax1)
            .subtract(approvedTax2)
            .subtract(approvedTip)
            .money()
        if (approvedBase < BigDecimal.ZERO) return null

        val approved = PartialApprovalAmounts(
            base = approvedBase,
            tax1 = approvedTax1,
            tax1Discount = approvedDiscount,
            tax2 = approvedTax2,
            tip = approvedTip,
        )
        val remaining = PartialApprovalAmounts(
            base = normalizedOriginal.base.subtract(approved.base),
            tax1 = normalizedOriginal.tax1.subtract(approved.tax1),
            tax1Discount = normalizedOriginal.tax1Discount.subtract(approved.tax1Discount),
            tax2 = normalizedOriginal.tax2.subtract(approved.tax2),
            tip = normalizedOriginal.tip.subtract(approved.tip),
        ).normalized()
        if (remaining.base < BigDecimal.ZERO || remaining.total <= BigDecimal.ZERO) return null

        return PartialApprovalAllocation(normalizedOriginal, approved, remaining)
    }
}

internal fun BigDecimal.money(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_UP)

internal fun BigDecimal.moneyText(): String = money().toPlainString()
