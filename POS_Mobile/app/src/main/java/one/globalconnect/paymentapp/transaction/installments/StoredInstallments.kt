package one.globalconnect.paymentapp.transaction.installments

import one.globalconnect.paymentapp.transaction.Transaction

/** Uses the persisted host selection and plan list, never current TMS plan names. */
fun Transaction.installmentDetails(): InstallmentDetails? =
    InstallmentContracts.forTransaction(type)?.savedDetails(paymentPlan, paymentPlanQueryResponse)
