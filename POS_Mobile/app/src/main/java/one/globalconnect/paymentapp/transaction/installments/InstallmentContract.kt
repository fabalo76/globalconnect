package one.globalconnect.paymentapp.transaction.installments

import one.globalconnect.paymentapp.transaction.TransactionType

/** Host-specific installment rules; shared screens never parse bank wire formats. */
interface InstallmentContract {
    fun savedDetails(paymentPlan: String, queryResponse: String): InstallmentDetails?
    fun queryCode(type: TransactionType): String
    fun queryField45(type: TransactionType): String
    fun parsePlans(response: String): List<InstallmentPlan>
    fun saleField45(type: TransactionType, selection: InstallmentSelection): String
}

data class InstallmentPlan(val code: String, val name: String, val installments: List<Int>)
data class InstallmentSelection(val plan: InstallmentPlan, val count: Int)

data class InstallmentDetails(val planCode: String, val planName: String, val count: Int)
