package one.globalconnect.paymentapp

internal sealed interface ParameterRequestReadiness {
    data object Ready : ParameterRequestReadiness
    data object OperationInProgress : ParameterRequestReadiness
    data class LiveTransactions(val count: Int) : ParameterRequestReadiness
    data object UnableToVerify : ParameterRequestReadiness
}

/**
 * Fails closed unless the application remains idle and the complete live batch remains empty.
 * The batch is read twice and the operation flag is checked around both reads so a transaction
 * transition during the check cannot normally result in a parameter request.
 */
internal suspend fun checkParameterRequestReadiness(
    operationInProgress: () -> Boolean,
    transactionCount: suspend () -> Int,
): ParameterRequestReadiness {
    if (operationInProgress()) return ParameterRequestReadiness.OperationInProgress

    repeat(2) {
        val count = try {
            transactionCount()
        } catch (_: Exception) {
            return ParameterRequestReadiness.UnableToVerify
        }
        if (count > 0) return ParameterRequestReadiness.LiveTransactions(count)
        if (operationInProgress()) return ParameterRequestReadiness.OperationInProgress
    }

    return ParameterRequestReadiness.Ready
}
