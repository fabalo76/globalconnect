package one.globalconnect.paymentapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import one.globalconnect.tms.paymentapp.TMSDATA
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "TmsParamsReceiver"

// Actions sent by xTMSAgent
private const val ACTION_PARAMS_READY   = "one.globalconnect.xtmsagent.ACTION_PARAMS_READY"
private const val ACTION_PARAMS_FAILED  = "one.globalconnect.xtmsagent.ACTION_PARAMS_FAILED"
private const val EXTRA_PARAMS_URI      = "params_uri"
private const val EXTRA_ERROR_MSG       = "error_message"

internal suspend fun awaitTmsApplicationInitialization(
    appInitialized: StateFlow<Boolean>,
    timeoutMillis: Long,
): Boolean = withTimeoutOrNull(timeoutMillis) {
    appInitialized.first { it }
    true
} ?: false

/**
 * Internal broadcast fired when the xTMSAgent param download failed.
 * The waiting screen shows the error and offers a retry button.
 */
const val ACTION_PARAMS_DOWNLOAD_FAILED = "one.globalconnect.paymentapp.ACTION_PARAMS_DOWNLOAD_FAILED"

/**
 * Receives parameter download results from the xTMSAgent application.
 *
 * Two scenarios invoke this receiver:
 *  1. **Pull** — The payment app broadcasts [ACTION_REQUEST_PARAMS] to xTMSAgent on first
 *     install (triggered by [GlobalConnectPaymentApplication.requestParamsFromXtmsAgent]).  xTMSAgent downloads
 *     from the TMS server and sends [ACTION_PARAMS_READY] back here.
 *  2. **Push** — The TMS server notifies a parameter update via MQTT.  xTMSAgent downloads
 *     and sends [ACTION_PARAMS_READY] here without the payment app initiating anything.
 *
 * On [ACTION_PARAMS_READY]:
 *   - Reads the decompressed params JSON through the FileProvider content:// URI.
 *   - Parses the PAYMENT_APP catalogTables/tree payload via [GlobalConnectPaymentApplication.applyTmsUpdate].
 *   - Persists the new params to internal storage (handled inside applyTmsUpdate).
 *   - [GlobalConnectPaymentApplication.applyTmsUpdate] sets [GlobalConnectPaymentApplication.paramsReadyFlow] to `true`,
 *     which triggers Compose recomposition in MainActivity without an Activity recreate().
 *
 * On [ACTION_PARAMS_FAILED]:
 *   - Logs the error and broadcasts [ACTION_PARAMS_DOWNLOAD_FAILED] with the error
 *     message so the waiting screen can display it to the operator.
 */
class TmsParamsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PARAMS_READY   -> handleParamsReady(context, intent)
            ACTION_PARAMS_FAILED  -> handleParamsFailed(context, intent)
        }
    }

    private fun handleParamsReady(context: Context, intent: Intent) {
        val task = TmsParamTaskReporter.from(intent)
        val uriString = intent.getStringExtra(EXTRA_PARAMS_URI)
        if (uriString.isNullOrBlank()) {
            Log.e(TAG, "ACTION_PARAMS_READY received but '$EXTRA_PARAMS_URI' extra is missing")
            notifyFailed(context, "Parameter URI is missing", task)
            return
        }

        val uri = Uri.parse(uriString)
        Log.i(TAG, "Params ready — reading from $uri")

        val jsonText = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open params URI $uri: ${e.message}", e)
            notifyFailed(context, "Could not read params file: ${e.message}", task)
            return
        }

        if (jsonText.isNullOrBlank()) {
            Log.e(TAG, "Params content from URI $uri is empty")
            notifyFailed(context, "Params file is empty", task)
            return
        }

        val newDatabase: TMSDATA = try {
            TMSDATA.parse(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse TMS params JSON: ${e.message}", e)
            notifyFailed(context, "Invalid params format: ${e.message}", task)
            return
        }

        val app = GlobalConnectPaymentApplication.instanceOrNull
        if (app == null) {
            Log.e(TAG, "GlobalConnectPaymentApplication not initialized — cannot apply TMS params")
            notifyFailed(context, "Payment application is not initialized", task)
            return
        }

        // A manifest receiver can start this process before Application.onCreate's background
        // initialization is complete. Wait before inspecting paramsReadyFlow or accessing the
        // repository; otherwise a normal push can be mistaken for a first-install response and
        // race startup EMV/PIN-key initialization.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!awaitApplicationInitialization(app)) {
                    Log.e(TAG, "Application initialization did not complete before the params timeout")
                    notifyFailed(context, "Payment application initialization timed out", task)
                    return@launch
                }

                val liveTransactionCount = try {
                    app.container.transactionRepository
                        .getTransactionCount()
                        .first()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not verify that the transaction batch is empty: ${e.message}", e)
                    null
                }

                if (shouldDeferParameterUpdate(
                        operationInProgress = PendingUpdateManager.isOperationInProgress,
                        liveTransactionCount = liveTransactionCount,
                    )
                ) {
                    Log.i(
                        TAG,
                        "TMS params received but the payment app is not safe to update " +
                            "(operationInProgress=${PendingUpdateManager.isOperationInProgress} " +
                            "liveTransactions=$liveTransactionCount) — deferring",
                    )
                    PendingUpdateManager.storePendingParamUpdate(context, jsonText, task)
                    TmsParamTaskReporter.deferred(
                        context,
                        task,
                        "Payment application parameter update pending; settlement required",
                    )
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    applyDatabase(context, app, newDatabase, task)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun awaitApplicationInitialization(
        app: GlobalConnectPaymentApplication,
    ): Boolean {
        if (!app.appInitialized.value) {
            Log.i(TAG, "Waiting for payment application initialization before applying params")
        }
        return awaitTmsApplicationInitialization(
            appInitialized = app.appInitialized,
            timeoutMillis = APPLICATION_INIT_TIMEOUT_MS,
        )
    }

    private fun applyDatabase(
        context: Context,
        app: GlobalConnectPaymentApplication,
        newDatabase: TMSDATA,
        task: TmsParamTaskContext?,
    ) {
        val applied = app.applyTmsUpdate(newDatabase)
        if (applied) {
            Log.i(TAG, "TMS params applied (${newDatabase.Terminal.size} terminal(s))")
            TmsParamTaskReporter.completed(context, task)
        } else {
            Log.e(TAG, "applyTmsUpdate() rejected the downloaded params")
            notifyFailed(context, "Could not apply PAYMENT_APP params", task)
        }
    }

    private fun handleParamsFailed(context: Context, intent: Intent) {
        val error = intent.getStringExtra(EXTRA_ERROR_MSG) ?: "unknown error"
        Log.e(TAG, "xTMSAgent reported params download failure: $error")
        notifyFailed(context, error, task = null)
    }

    private fun notifyFailed(
        context: Context,
        error: String,
        task: TmsParamTaskContext?,
    ) {
        context.sendBroadcast(
            Intent(ACTION_PARAMS_DOWNLOAD_FAILED).apply {
                `package` = context.packageName
                putExtra(EXTRA_ERROR_MSG, error)
            }
        )
        TmsParamTaskReporter.failed(context, task, error)
    }

    private companion object {
        const val APPLICATION_INIT_TIMEOUT_MS = 30_000L
    }
}
