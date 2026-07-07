package one.globalconnect.xtmsagent.settlement

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.time.Instant

private const val TAG = "ForceSettlementManager"
private const val ACTION_PAY_APP = "android.intent.action.PAY_APP"

object ForceSettlementManager {
    fun request(context: Context, taskId: String, payload: JSONObject?): Boolean {
        val paymentPkg = findPaymentAppPackage(context)
        if (paymentPkg.isNullOrBlank()) {
            Log.w(TAG, "Force settlement skipped: no payment app declares $ACTION_PAY_APP")
            return false
        }

        context.sendBroadcast(
            Intent(ForceSettlementConstants.ACTION_FORCE_SETTLEMENT).apply {
                `package` = paymentPkg
                putExtra(ForceSettlementConstants.EXTRA_TASK_ID, taskId)
                putExtra(ForceSettlementConstants.EXTRA_REQUESTED_AT, Instant.now().toString())
                putExtra(ForceSettlementConstants.EXTRA_SOURCE, "Global Connect ONE")
                payload?.optString("serialNumber")?.takeIf { it.isNotBlank() }?.let {
                    putExtra(ForceSettlementConstants.EXTRA_SERIAL_NUMBER, it)
                }
                payload?.optString("acquirerCode")?.takeIf { it.isNotBlank() }?.let {
                    putExtra(ForceSettlementConstants.EXTRA_ACQUIRER_CODE, it)
                }
                payload?.optString("batchNumber")?.takeIf { it.isNotBlank() }?.let {
                    putExtra(ForceSettlementConstants.EXTRA_BATCH_NUMBER, it)
                }
            }
        )
        Log.i(TAG, "ACTION_FORCE_SETTLEMENT -> $paymentPkg taskId=$taskId")
        return true
    }

    @Suppress("DEPRECATION")
    private fun findPaymentAppPackage(context: Context): String? {
        val intent = Intent(ACTION_PAY_APP)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            context.packageManager.queryIntentActivities(intent, 0)
        }
        return matches.firstOrNull()?.activityInfo?.packageName
    }
}
