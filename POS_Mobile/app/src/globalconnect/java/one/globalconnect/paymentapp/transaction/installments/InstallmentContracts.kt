package one.globalconnect.paymentapp.transaction.installments

import one.globalconnect.paymentapp.transaction.TransactionType

/** Other hosts retain their existing flow until their own query contract is implemented. */
object InstallmentContracts {
    fun forTransaction(type: TransactionType): InstallmentContract? = null
}
