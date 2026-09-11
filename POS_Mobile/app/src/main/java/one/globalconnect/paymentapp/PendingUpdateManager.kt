package one.globalconnect.paymentapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import one.globalconnect.tms.paymentapp.TMSDATA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG                = "PendingUpdateManager"
private const val PREFS_NAME         = "tms_pending_update_prefs"
private const val KEY_PARAM_JSON     = "param_json"
private const val KEY_PARAM_TASK_ID  = "param_task_id"
private const val KEY_PARAM_RESULT_PACKAGE = "param_result_package"
private const val KEY_APP_PKG        = "app_update_pkg"
private const val KEY_APP_VER        = "app_update_ver"
private const val KEY_APP_VER_CODE   = "app_update_ver_code"

// Sent by xTMSAgent before installing an APK — payment app responds with ACTION_PRE_INSTALL_RESPONSE.
const val ACTION_PRE_INSTALL_CHECK    = "one.globalconnect.xtmsagent.ACTION_PRE_INSTALL_CHECK"
const val EXTRA_PRE_INSTALL_PKG       = "pkg"
const val EXTRA_PRE_INSTALL_VER       = "ver"
const val EXTRA_PRE_INSTALL_VER_CODE  = "verCode"
const val EXTRA_PRE_INSTALL_SENDER    = "senderPkg"

// Sent by payment app back to xTMSAgent.
const val ACTION_PRE_INSTALL_RESPONSE = "one.globalconnect.paymentapp.ACTION_PRE_INSTALL_RESPONSE"
const val EXTRA_PRE_INSTALL_PROCEED   = "proceed"

// Sent by xTMSAgent when it has no pending install task for a package (dismisses stale flag).
const val ACTION_PRE_INSTALL_DISMISS  = "one.globalconnect.xtmsagent.ACTION_PRE_INSTALL_DISMISS"

/**
 * Singleton that tracks deferred update state across the payment application.
 *
 * Two kinds of deferred update:
 *  1. **Param update** — TMS pushed new parameters while there were unsettled transactions.
 *     The new JSON is persisted and applied after the next successful settlement.
 *  2. **App update** — xTMSAgent wants to install a new APK but the terminal is busy.
 *     The install is postponed; when the terminal becomes idle xTMSAgent is notified to proceed.
 *
 * [isOperationInProgress] must be set to `true` by transaction/settlement code before starting
 * an operation and back to `false` when it ends so receivers can gate responses correctly.
 */
object PendingUpdateManager {

    /** True while a card transaction or settlement is in progress. Set by callers. */
    private val _operationInProgress = MutableStateFlow(false)
    val operationInProgress: StateFlow<Boolean> = _operationInProgress.asStateFlow()

    var isOperationInProgress: Boolean
        get() = _operationInProgress.value
        set(value) {
            _operationInProgress.value = value
        }

    // ── Param update ─────────────────────────────────────────────────────────

    private val _paramUpdatePending = MutableStateFlow(false)
    val paramUpdatePending: StateFlow<Boolean> = _paramUpdatePending.asStateFlow()
    private val paramUpdateMutex = Mutex()

    internal fun storePendingParamUpdate(
        context: Context,
        jsonText: String,
        task: TmsParamTaskContext? = null,
    ) {
        val previousTask = pendingParamTask(context)
        if (previousTask != null && previousTask.taskId != task?.taskId) {
            TmsParamTaskReporter.failed(
                context,
                previousTask,
                "Parameter update was superseded by a newer update",
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_PARAM_JSON, jsonText)
            if (task == null) {
                remove(KEY_PARAM_TASK_ID)
                remove(KEY_PARAM_RESULT_PACKAGE)
            } else {
                putString(KEY_PARAM_TASK_ID, task.taskId)
                putString(KEY_PARAM_RESULT_PACKAGE, task.resultPackage)
            }
        }.apply()
        _paramUpdatePending.value = true
        TmsParamsUpdateNotifier.notifyPendingSettlementWhenMainActivityClosed(context)
        Log.i(TAG, "Param update stored — pending settlement")
    }

    fun hasPendingParamUpdate(context: Context): Boolean {
        val json    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PARAM_JSON, null)
        val pending = !json.isNullOrBlank()
        _paramUpdatePending.value = pending
        return pending
    }

    /**
     * Applies the stored param JSON via [GlobalConnectPaymentApplication.applyTmsUpdate] and clears the flag.
     * Must be called from a background thread (disk + parsing).
     */
    fun applyPendingParamUpdate(context: Context): Boolean {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonText = prefs.getString(KEY_PARAM_JSON, null) ?: return false
        val task = pendingParamTask(context)
        return try {
            val db = TMSDATA.parse(jsonText)
            val app = GlobalConnectPaymentApplication.instanceOrNull
            if (app == null) {
                Log.e(TAG, "GlobalConnectPaymentApplication not ready — cannot apply deferred param update")
                TmsParamTaskReporter.failed(context, task, "Payment application is not ready")
                clearPendingParamUpdate(context)
                return false
            }
            val applied = app.applyTmsUpdate(db)
            if (applied) {
                clearPendingParamUpdate(context)
                Log.i(TAG, "Deferred param update applied")
                TmsParamTaskReporter.completed(context, task)
            } else {
                Log.e(TAG, "applyTmsUpdate rejected deferred PAYMENT_APP params")
                TmsParamTaskReporter.failed(context, task, "Could not apply deferred PAYMENT_APP params")
                clearPendingParamUpdate(context)
            }
            applied
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply deferred param update: ${e.message}", e)
            TmsParamTaskReporter.failed(
                context,
                task,
                "Failed to apply deferred parameters: ${e.message}",
            )
            clearPendingParamUpdate(context)
            false
        }
    }

    /**
     * Re-checks the complete live batch before applying a deferred parameter update.
     * A database error is deliberately treated as blocked: parameters must never be
     * applied merely because the application could not prove that the batch is empty.
     */
    suspend fun applyPendingParamUpdateIfBatchEmpty(
        context: Context,
    ): PendingParamUpdateResult = paramUpdateMutex.withLock {
        if (!hasPendingParamUpdate(context)) {
            return@withLock PendingParamUpdateResult.NoPendingUpdate
        }

        val app = GlobalConnectPaymentApplication.instanceOrNull
            ?: return@withLock PendingParamUpdateResult.WaitingForIdleBatch
        val liveTransactionCount = withContext(Dispatchers.IO) {
            try {
                app.container.transactionRepository
                    .getTransactionCount()
                    .first()
            } catch (error: Exception) {
                Log.e(TAG, "Could not verify that the transaction batch is empty", error)
                null
            }
        }

        if (shouldDeferParameterUpdate(isOperationInProgress, liveTransactionCount)) {
            Log.i(
                TAG,
                "Deferred params remain pending " +
                    "(operationInProgress=$isOperationInProgress liveTransactions=$liveTransactionCount)",
            )
            return@withLock PendingParamUpdateResult.WaitingForIdleBatch
        }

        val applied = withContext(Dispatchers.Main) {
            applyPendingParamUpdate(context)
        }
        if (applied) {
            PendingParamUpdateResult.Applied
        } else {
            PendingParamUpdateResult.Failed
        }
    }

    fun clearPendingParamUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_PARAM_JSON)
            .remove(KEY_PARAM_TASK_ID)
            .remove(KEY_PARAM_RESULT_PACKAGE)
            .apply()
        _paramUpdatePending.value = false
        TmsParamsUpdateNotifier.cancelPendingSettlementNotification(context)
    }

    private fun pendingParamTask(context: Context): TmsParamTaskContext? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taskId = prefs.getString(KEY_PARAM_TASK_ID, null)?.takeIf { it.isNotBlank() }
        val resultPackage = prefs.getString(KEY_PARAM_RESULT_PACKAGE, null)?.takeIf { it.isNotBlank() }
        return if (taskId != null && resultPackage != null) {
            TmsParamTaskContext(taskId, resultPackage)
        } else {
            null
        }
    }

    // ── App update ────────────────────────────────────────────────────────────

    private val _appUpdatePending = MutableStateFlow(false)
    val appUpdatePending: StateFlow<Boolean> = _appUpdatePending.asStateFlow()

    fun storePendingAppUpdate(context: Context, pkg: String, ver: String, verCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_APP_PKG, pkg)
            .putString(KEY_APP_VER, ver)
            .putInt(KEY_APP_VER_CODE, verCode)
            .apply()
        _appUpdatePending.value = true
        Log.i(TAG, "App update postponed: pkg=$pkg ver=$ver verCode=$verCode")
    }

    fun hasPendingAppUpdate(context: Context): Boolean {
        val pkg     = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_PKG, null)
        val pending = !pkg.isNullOrBlank()
        _appUpdatePending.value = pending
        return pending
    }

    fun getPendingAppUpdatePackage(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_PKG, null)

    fun clearPendingAppUpdate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_APP_PKG).remove(KEY_APP_VER).remove(KEY_APP_VER_CODE).apply()
        _appUpdatePending.value = false
    }

    /**
     * Broadcasts [ACTION_PRE_INSTALL_RESPONSE] with `proceed = true` to all installed xTMSAgent
     * flavors, signalling that the terminal is now idle and the pending APK install can proceed.
     */
    fun notifyXtmsAgentCanProceed(context: Context) {
        val pkg = getPendingAppUpdatePackage(context) ?: return
        val probe     = Intent(ACTION_PRE_INSTALL_CHECK)
        val receivers = context.packageManager.queryBroadcastReceivers(probe, PackageManager.GET_META_DATA)
        if (receivers.isEmpty()) {
            Log.w(TAG, "No xTMSAgent receivers found — clearing stale app update pending flag")
            clearPendingAppUpdate(context)
            return
        }
        receivers.forEach { ri ->
            val homePkg = ri.activityInfo.packageName
            Log.i(TAG, "Notifying xTMSAgent $homePkg: app update can proceed for $pkg")
            context.sendBroadcast(Intent(ACTION_PRE_INSTALL_RESPONSE).apply {
                `package`               = homePkg
                putExtra(EXTRA_PRE_INSTALL_PKG,     pkg)
                putExtra(EXTRA_PRE_INSTALL_PROCEED, true)
            })
        }
    }

    /**
     * Re-checks the live batch and releases every deferred operation that is safe only when
     * the terminal is idle and the batch is empty.
     *
     * Parameter updates are applied through the normal guarded update path. Pending application
     * installs are released only after the same fresh transaction-count check succeeds.
     *
     * @param context application context used to load deferred state and send update broadcasts.
     * @return a summary describing whether the batch was empty and which operations were released.
     */
    suspend fun processPendingOperationsIfBatchEmpty(
        context: Context,
    ): PendingBatchOperationsResult {
        val app = GlobalConnectPaymentApplication.instanceOrNull
            ?: return PendingBatchOperationsResult(batchEmpty = false)
        val liveTransactionCount = withContext(Dispatchers.IO) {
            try {
                app.container.transactionRepository.getTransactionCount().first()
            } catch (error: Exception) {
                Log.e(TAG, "Could not verify the live batch before processing pending operations", error)
                null
            }
        }
        if (shouldDeferParameterUpdate(isOperationInProgress, liveTransactionCount)) {
            Log.i(
                TAG,
                "Pending operations remain deferred " +
                    "(operationInProgress=$isOperationInProgress liveTransactions=$liveTransactionCount)",
            )
            return PendingBatchOperationsResult(batchEmpty = false)
        }

        val parameterResult = if (hasPendingParamUpdate(context)) {
            applyPendingParamUpdateIfBatchEmpty(context)
        } else {
            PendingParamUpdateResult.NoPendingUpdate
        }
        val appUpdateReleased = if (hasPendingAppUpdate(context)) {
            notifyXtmsAgentCanProceed(context)
            clearPendingAppUpdate(context)
            true
        } else {
            false
        }
        Log.i(
            TAG,
            "Pending-operation check completed " +
                "(parameterResult=$parameterResult appUpdateReleased=$appUpdateReleased)",
        )
        return PendingBatchOperationsResult(
            batchEmpty = true,
            parameterResult = parameterResult,
            appUpdateReleased = appUpdateReleased,
        )
    }

    /** Restore in-memory StateFlow values from persisted state on app start. */
    fun restoreFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _paramUpdatePending.value = !prefs.getString(KEY_PARAM_JSON, null).isNullOrBlank()
        _appUpdatePending.value   = !prefs.getString(KEY_APP_PKG,    null).isNullOrBlank()
    }
}

internal fun shouldDeferParameterUpdate(
    operationInProgress: Boolean,
    liveTransactionCount: Int?,
): Boolean = operationInProgress || liveTransactionCount == null || liveTransactionCount > 0

enum class PendingParamUpdateResult {
    NoPendingUpdate,
    WaitingForIdleBatch,
    Applied,
    Failed,
}

/**
 * Outcome of checking operations that must wait for the active transaction batch to become empty.
 */
data class PendingBatchOperationsResult(
    val batchEmpty: Boolean,
    val parameterResult: PendingParamUpdateResult = PendingParamUpdateResult.NoPendingUpdate,
    val appUpdateReleased: Boolean = false,
)
