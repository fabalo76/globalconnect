package one.globalconnect.xtmsagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import one.globalconnect.xtmsagent.admin.AdminRequestConstants
import one.globalconnect.xtmsagent.admin.AdminRequestManager

private const val TAG = "AdminRequestReceiver"

class AdminRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            handleRequest(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Admin request broadcast was rejected", e)
        }
    }

    private fun handleRequest(context: Context, intent: Intent) {
        if (intent.action != AdminRequestConstants.ACTION_REPORT_ADMIN_REQUEST) return

        val payload = intent.getStringExtra(AdminRequestConstants.EXTRA_ADMIN_REQUEST_JSON)
        val requestId = intent.getStringExtra(AdminRequestConstants.EXTRA_ADMIN_REQUEST_ID)
        Log.i(
            TAG,
            "Admin request received targetHomePackage=${context.packageName} " +
                "requestId=${requestId ?: "(none)"} payloadBytes=${payload?.toByteArray(Charsets.UTF_8)?.size ?: 0}"
        )

        AdminRequestManager.report(
            context = context.applicationContext,
            payloadText = payload,
            requestId = requestId,
        )
    }
}
