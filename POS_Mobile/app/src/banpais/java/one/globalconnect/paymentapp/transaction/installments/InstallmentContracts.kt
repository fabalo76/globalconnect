package one.globalconnect.paymentapp.transaction.installments

import one.globalconnect.paymentapp.transaction.TransactionType

object InstallmentContracts {
    fun forTransaction(type: TransactionType): InstallmentContract? = when (type) {
        TransactionType.QUOTA_SALE, TransactionType.EXTRAS_SALE -> BanpaisInstallmentContract
        else -> null
    }
}

/** Banpais PayPlan DE63/45 requests and concatenated DE63/46 plan records. */
object BanpaisInstallmentContract : InstallmentContract {
    override fun savedDetails(paymentPlan: String, queryResponse: String): InstallmentDetails? {
        if (paymentPlan.length < 6) return null
        val count = paymentPlan.take(2).toIntOrNull()?.takeIf { it in 1..99 } ?: return null
        val code = paymentPlan.substring(2, 6)
        val name = runCatching { parsePlans(queryResponse).firstOrNull { it.code == code }?.name }.getOrNull()
        return InstallmentDetails(code, name ?: code, count)
    }

    override fun queryCode(type: TransactionType) = when (type) {
        TransactionType.QUOTA_SALE -> "InstallmentQuery"
        TransactionType.EXTRAS_SALE -> "ExtrasQuery"
        else -> error("Unsupported installment transaction")
    }
    override fun queryField45(type: TransactionType): String = "0".repeat(22) + when (type) {
        TransactionType.QUOTA_SALE -> "S"
        TransactionType.EXTRAS_SALE -> "C"
        else -> error("Unsupported installment transaction")
    }
    override fun parsePlans(response: String): List<InstallmentPlan> {
        require(response.length in 1..255 && response.all { it.code in 32..126 })
        val plans = mutableListOf<InstallmentPlan>()
        var offset = 0
        while (offset < response.length) {
            require(response.length - offset >= 31)
            val code = response.substring(offset, offset + 4)
            val name = response.substring(offset + 4, offset + 29).trim()
            val size = response.substring(offset + 29, offset + 31)
                .also { require(it.all { c -> c in '0'..'9' }) }.toInt()
            require(code.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } && name.isNotEmpty() && size in 1..99)
            val end = offset + 31 + size * 2
            require(end <= response.length)
            val counts = response.substring(offset + 31, end).chunked(2).map { value ->
                require(value.all { it in '0'..'9' })
                value.toInt().also { require(it in 1..99) }
            }
            require(counts.distinct().size == counts.size)
            require(plans.none { it.code == code })
            plans += InstallmentPlan(code, name, counts)
            offset = end
        }
        return plans
    }
    override fun saleField45(type: TransactionType, selection: InstallmentSelection): String {
        require(selection.count in selection.plan.installments)
        require(selection.plan.code.length == 4 && selection.plan.code.all { it.isLetterOrDigit() })
        // PayPlan specification distinguishes quota sale (S) from extrafinancing (C).
        return selection.count.toString().padStart(2, '0') + selection.plan.code + "0".repeat(16) + queryField45(type).last()
    }
}
