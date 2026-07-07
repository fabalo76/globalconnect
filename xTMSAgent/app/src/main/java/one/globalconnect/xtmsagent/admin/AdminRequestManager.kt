package one.globalconnect.xtmsagent.admin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import org.json.JSONObject

private const val TAG = "AdminRequestManager"
private const val ACTION_PAY_APP = "android.intent.action.PAY_APP"

object AdminRequestManager {
    fun report(context: Context, payloadText: String?, requestId: String?) {
        if (payloadText.isNullOrBlank()) {
            notifyPaymentAppFailed(context, requestId, "Missing admin request payload")
            return
        }

        val payload = try {
            JSONObject(payloadText)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid admin request JSON: ${e.message}", e)
            notifyPaymentAppFailed(context, requestId, "Invalid admin request JSON")
            return
        }

        TmsMqttManager.publishAdminRequest(payload) { success, error ->
            if (success) {
                notifyPaymentAppAck(context, requestId)
            } else {
                notifyPaymentAppFailed(context, requestId, error ?: "MQTT publish failed")
            }
        }
    }

    private fun notifyPaymentAppAck(context: Context, requestId: String?) {
        val paymentPkg = findPaymentAppPackage(context) ?: return
        context.sendBroadcast(
            Intent(AdminRequestConstants.ACTION_ADMIN_REQUEST_ACK).apply {
                `package` = paymentPkg
                putOptionalRequestId(requestId)
            }
        )
        Log.i(TAG, "ACTION_ADMIN_REQUEST_ACK -> $paymentPkg requestId=${requestId ?: "(none)"}")
    }

    private fun notifyPaymentAppFailed(context: Context, requestId: String?, error: String) {
        val paymentPkg = findPaymentAppPackage(context) ?: return
        context.sendBroadcast(
            Intent(AdminRequestConstants.ACTION_ADMIN_REQUEST_FAILED).apply {
                `package` = paymentPkg
                putOptionalRequestId(requestId)
                putExtra(AdminRequestConstants.EXTRA_ERROR_MESSAGE, error)
            }
        )
        Log.i(TAG, "ACTION_ADMIN_REQUEST_FAILED -> $paymentPkg requestId=${requestId ?: "(none)"} error=$error")
    }

    private fun Intent.putOptionalRequestId(requestId: String?) {
        if (!requestId.isNullOrBlank()) putExtra(AdminRequestConstants.EXTRA_ADMIN_REQUEST_ID, requestId)
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
