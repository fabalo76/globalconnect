package one.globalconnect.paymentapp.navigation

import android.content.Context
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.PendingBatchOperationsResult
import one.globalconnect.paymentapp.PendingUpdateManager
import one.globalconnect.paymentapp.transaction.PendingReversalProcessingResult
import one.globalconnect.paymentapp.transaction.PendingReversalProcessor
import one.globalconnect.paymentapp.transaction.PendingReversalReceiptPrinter
import kotlinx.coroutines.flow.first

/**
 * Result returned after the active batch is erased and deferred operations are re-evaluated.
 *
 * @property erasedTransactions number of active transaction rows removed.
 * @property pendingOperations outcome of checking deferred parameter and application updates.
 */
internal data class BatchEraseResult(
    val erasedTransactions: Int,
    val pendingOperations: PendingBatchOperationsResult,
)

/**
 * Performs destructive terminal-maintenance operations requested from the Technical Menu.
 */
internal object TerminalMaintenanceOperations {

    /**
     * Checks the complete reversal queue and retries every entry whose acquirer and host are
     * still configured. Host-confirmed rows are removed; failed rows remain queued.
     */
    suspend fun sendPendingReversals(): PendingReversalProcessingResult {
        val app = GlobalConnectPaymentApplication.instance
        val processor = PendingReversalProcessor(app.container.transactionRepository)
        val receiptPrinter = PendingReversalReceiptPrinter(
            context = app.applicationContext,
            profileRepository = app.container.profileRepository,
            tmsDatabase = app.tmsDatabase,
        )

        PendingUpdateManager.isOperationInProgress = true
        return try {
            processor.processAll(
                tmsDatabase = app.tmsDatabase,
                onApproved = receiptPrinter::print,
            )
        } finally {
            PendingUpdateManager.isOperationInProgress = false
        }
    }

    /**
     * Erases every queued reversal.
     *
     * @return the number of queued reversal rows removed.
     */
    suspend fun erasePendingReversals(): Int {
        val app = GlobalConnectPaymentApplication.instance
        return app.container.transactionRepository.deleteAllPendingReversals()
    }

    /**
     * Erases the active transaction batch, resets the local order sequence, clears settlement
     * pending markers, and then checks operations that were waiting for an empty batch.
     *
     * The operation flag prevents a parameter or application update from being released while
     * the database cleanup is still in progress.
     *
     * @param context application context used by the deferred-update manager.
     * @return the number of removed transactions and the deferred-operation check outcome.
     */
    suspend fun eraseActiveBatch(context: Context): BatchEraseResult {
        val app = GlobalConnectPaymentApplication.instance
        PendingUpdateManager.isOperationInProgress = true
        val erasedTransactions = try {
            val erased = app.container.transactionRepository.deleteAllTransactions()
            app.container.transactionRepository.resetOrderNumber()
            app.container.settlementStateRepository.observeStates().first().forEach { state ->
                app.container.settlementStateRepository.clearPending(state.acquirerId)
            }
            erased
        } finally {
            PendingUpdateManager.isOperationInProgress = false
        }

        val pendingOperations = PendingUpdateManager.processPendingOperationsIfBatchEmpty(
            context.applicationContext,
        )
        return BatchEraseResult(
            erasedTransactions = erasedTransactions,
            pendingOperations = pendingOperations,
        )
    }
}
