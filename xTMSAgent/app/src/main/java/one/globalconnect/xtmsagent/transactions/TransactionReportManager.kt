package one.globalconnect.xtmsagent.transactions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import org.json.JSONObject

private const val TAG = "TxnReportManager"
private const val ACTION_PAY_APP = "android.intent.action.PAY_APP"

object TransactionReportManager {
    fun report(
        context: Context,
        payloadText: String?,
        transactionId: String?,
        batchId: String?,
    ) {
        if (payloadText.isNullOrBlank()) {
            notifyPaymentAppFailed(context, transactionId, batchId, "Missing transaction payload")
            return
        }

        val payload = try {
            JSONObject(payloadText)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid transaction report JSON: ${e.message}", e)
            notifyPaymentAppFailed(context, transactionId, batchId, "Invalid transaction JSON")
            return
        }

        TmsMqttManager.publishTransactionReport(payload) { success, error ->
            if (success) {
                notifyPaymentAppAck(context, transactionId, batchId)
            } else {
                notifyPaymentAppFailed(context, transactionId, batchId, error ?: "MQTT publish failed")
            }
        }
    }

    private fun notifyPaymentAppAck(context: Context, transactionId: String?, batchId: String?) {
        val paymentPkg = findPaymentAppPackage(context) ?: return
        context.sendBroadcast(
            Intent(TransactionReportConstants.ACTION_TRANSACTION_REPORT_ACK).apply {
                `package` = paymentPkg
                putOptionalIds(transactionId, batchId)
            }
        )
        Log.i(TAG, "ACTION_TRANSACTION_REPORT_ACK -> $paymentPkg transactionId=${transactionId ?: "(none)"} batchId=${batchId ?: "(none)"}")
    }

    private fun notifyPaymentAppFailed(context: Context, transactionId: String?, batchId: String?, error: String) {
        val paymentPkg = findPaymentAppPackage(context) ?: return
        context.sendBroadcast(
            Intent(TransactionReportConstants.ACTION_TRANSACTION_REPORT_FAILED).apply {
                `package` = paymentPkg
                putOptionalIds(transactionId, batchId)
                putExtra(TransactionReportConstants.EXTRA_ERROR_MESSAGE, error)
            }
        )
        Log.i(TAG, "ACTION_TRANSACTION_REPORT_FAILED -> $paymentPkg transactionId=${transactionId ?: "(none)"} batchId=${batchId ?: "(none)"} error=$error")
    }

    private fun Intent.putOptionalIds(transactionId: String?, batchId: String?) {
        if (!transactionId.isNullOrBlank()) putExtra(TransactionReportConstants.EXTRA_TRANSACTION_ID, transactionId)
        if (!batchId.isNullOrBlank()) putExtra(TransactionReportConstants.EXTRA_BATCH_ID, batchId)
    }

    @Suppress("DEPRECATION")
    private fun findPaymentAppPackage(context: Context): String? {
        val intent = Intent(ACTION_PAY_APP)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            context.packageManager.queryIntentActivities(intent, 0)
        }
        val pkg = matches.firstOrNull()?.activityInfo?.packageName
        if (pkg == null) Log.d(TAG, "queryIntentActivities(PAY_APP) returned no results")
        return pkg
    }
}
