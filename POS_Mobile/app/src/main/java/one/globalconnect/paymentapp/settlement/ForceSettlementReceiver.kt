package one.globalconnect.paymentapp.settlement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import one.globalconnect.paymentapp.PendingUpdateManager
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ForceSettlementReceiver"
private const val ACTION_FORCE_SETTLEMENT = "one.globalconnect.xtmsagent.ACTION_FORCE_SETTLEMENT"
private const val EXTRA_TASK_ID = "task_id"
private const val INIT_WAIT_MS = 30_000L

class ForceSettlementReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FORCE_SETTLEMENT) {
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as GlobalConnectPaymentApplication
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val initialized = withTimeoutOrNull(INIT_WAIT_MS) {
                    app.appInitialized.first { it }
                    app.paramsReadyFlow.first { it }
                    true
                } ?: false

                if (!initialized) {
                    Log.w(TAG, "Force settlement ignored; app is not initialized taskId=$taskId")
                    return@launch
                }

                if (PendingUpdateManager.isOperationInProgress) {
                    Log.w(TAG, "Force settlement ignored; payment operation is already in progress taskId=$taskId")
                    return@launch
                }

                PendingUpdateManager.isOperationInProgress = true
                try {
                    val transactions = app.container.transactionRepository.getAllTransactionsStream().first()
                    val request = buildAllAcquirersSettlementRequest(app.container.tmsDatabase, transactions)
                    if (request == null) {
                        Log.i(TAG, "Force settlement skipped; no pending transactions taskId=$taskId")
                        return@launch
                    }

                    Log.i(TAG, "Force settlement started taskId=$taskId targets=${request.targets.size}")
                    val results = SettlementCoordinator().execute(request)

                    results.filterIsInstance<SettlementResult.Success>()
                        .mapNotNull(SettlementResult.Success::snapshot)
                        .forEach { snapshot ->
                            NexGoPaymentPrinter.printSettlementReceipt(
                                context = app.applicationContext,
                                snapshot = snapshot,
                                tmsDatabase = app.container.tmsDatabase,
                            )
                        }

                    PendingUpdateManager.isOperationInProgress = false
                    if (results.any { it is SettlementResult.Success }) {
                        if (PendingUpdateManager.hasPendingParamUpdate(app.applicationContext)) {
                            PendingUpdateManager.applyPendingParamUpdateIfBatchEmpty(app.applicationContext)
                        }
                        if (PendingUpdateManager.hasPendingAppUpdate(app.applicationContext)) {
                            PendingUpdateManager.notifyXtmsAgentCanProceed(app.applicationContext)
                            PendingUpdateManager.clearPendingAppUpdate(app.applicationContext)
                        }
                    }

                    val failures = results.count { it is SettlementResult.Failure }
                    Log.i(TAG, "Force settlement finished taskId=$taskId results=${results.size} failures=$failures")
                } finally {
                    PendingUpdateManager.isOperationInProgress = false
                }
            } catch (error: Exception) {
                Log.e(TAG, "Force settlement failed taskId=$taskId message=${error.message}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
