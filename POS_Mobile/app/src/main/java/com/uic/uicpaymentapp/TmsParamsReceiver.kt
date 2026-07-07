package com.uic.uicpaymentapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.uic.tms.payment_app.TMSDATA
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "TmsParamsReceiver"

// Actions sent by UIC Home (com.uic.home)
private const val ACTION_PARAMS_READY   = "com.uic.home.ACTION_PARAMS_READY"
private const val ACTION_PARAMS_FAILED  = "com.uic.home.ACTION_PARAMS_FAILED"
private const val EXTRA_PARAMS_URI      = "params_uri"
private const val EXTRA_ERROR_MSG       = "error_message"

/**
 * Internal broadcast fired when the UIC Home param download failed.
 * The waiting screen shows the error and offers a retry button.
 */
const val ACTION_PARAMS_DOWNLOAD_FAILED = "com.uic.uicpaymentapp.ACTION_PARAMS_DOWNLOAD_FAILED"

/**
 * Receives parameter download results from the UIC Home application.
 *
 * Two scenarios invoke this receiver:
 *  1. **Pull** — The payment app broadcasts [ACTION_REQUEST_PARAMS] to UIC Home on first
 *     install (triggered by [UICApplication.requestParamsFromUicHome]).  UIC Home downloads
 *     from the TMS server and sends [ACTION_PARAMS_READY] back here.
 *  2. **Push** — The TMS server notifies a parameter update via MQTT.  UIC Home downloads
 *     and sends [ACTION_PARAMS_READY] here without the payment app initiating anything.
 *
 * On [ACTION_PARAMS_READY]:
 *   - Reads the decompressed params JSON through the FileProvider content:// URI.
 *   - Parses the PAYMENT_APP catalogTables/tree payload via [UICApplication.applyTmsUpdate].
 *   - Persists the new params to internal storage (handled inside applyTmsUpdate).
 *   - [UICApplication.applyTmsUpdate] sets [UICApplication.paramsReadyFlow] to `true`,
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
        val uriString = intent.getStringExtra(EXTRA_PARAMS_URI)
        if (uriString.isNullOrBlank()) {
            Log.e(TAG, "ACTION_PARAMS_READY received but '$EXTRA_PARAMS_URI' extra is missing")
            return
        }

        val uri = Uri.parse(uriString)
        Log.i(TAG, "Params ready — reading from $uri")

        val jsonText = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open params URI $uri: ${e.message}", e)
            notifyFailed(context, "Could not read params file: ${e.message}")
            return
        }

        if (jsonText.isNullOrBlank()) {
            Log.e(TAG, "Params content from URI $uri is empty")
            notifyFailed(context, "Params file is empty")
            return
        }

        val newDatabase: TMSDATA = try {
            TMSDATA.parse(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse TMS params JSON: ${e.message}", e)
            notifyFailed(context, "Invalid params format: ${e.message}")
            return
        }

        val app = UICApplication.instanceOrNull
        if (app == null) {
            Log.e(TAG, "UICApplication not initialized — cannot apply TMS params")
            return
        }

        // First install: no params yet means no transactions possible — apply immediately on
        // main thread so Compose collectAsState() sees the StateFlow update synchronously.
        if (!app.paramsReadyFlow.value) {
            applyDatabase(context, app, newDatabase)
            return
        }

        // Push update while app is running: check unsettled transactions on IO thread,
        // but post the actual apply back to main thread to keep Compose recomposition reliable.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val unsettledCount = try {
                app.container.transactionRepository
                    .getOpenAndNeedTipTransactionNumber()
                    .first()
            } catch (e: Exception) {
                Log.e(TAG, "Could not query unsettled transactions: ${e.message}", e)
                0
            }

            if (unsettledCount > 0) {
                Log.i(TAG, "TMS params received but $unsettledCount unsettled transaction(s) pending — deferring")
                PendingUpdateManager.storePendingParamUpdate(context, jsonText)
                pendingResult.finish()
                return@launch
            }

            Handler(Looper.getMainLooper()).post {
                applyDatabase(context, app, newDatabase)
                pendingResult.finish()
            }
        }
    }

    private fun applyDatabase(context: Context, app: UICApplication, newDatabase: TMSDATA) {
        val applied = app.applyTmsUpdate(newDatabase)
        if (applied) {
            Log.i(TAG, "TMS params applied (${newDatabase.Terminal.size} terminal(s))")
        } else {
            Log.e(TAG, "applyTmsUpdate() rejected the downloaded params")
            notifyFailed(context, "Could not apply PAYMENT_APP params")
        }
    }

    private fun handleParamsFailed(context: Context, intent: Intent) {
        val error = intent.getStringExtra(EXTRA_ERROR_MSG) ?: "unknown error"
        Log.e(TAG, "UIC Home reported params download failure: $error")
        notifyFailed(context, error)
    }

    private fun notifyFailed(context: Context, error: String) {
        context.sendBroadcast(
            Intent(ACTION_PARAMS_DOWNLOAD_FAILED).apply {
                `package` = context.packageName
                putExtra(EXTRA_ERROR_MSG, error)
            }
        )
    }
}
