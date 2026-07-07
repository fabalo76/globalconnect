package com.uic.uicpaymentapp.admin

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.uic.uicpaymentapp.UICApplication
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

private const val TAG = "AdminRequestBridge"

object AdminRequestBridge {
    fun report(context: Context, requestType: String, requestLabel: String) {
        val appContext = context.applicationContext
        val requestId = UUID.randomUUID().toString()
        val terminal = UICApplication.instance.tmsDatabase.Terminal.firstOrNull()
        val terminalId = terminal?.TermID.orEmpty()
        val payload = JSONObject()
            .put("type", "admin_request")
            .put("messageType", "admin_request")
            .put("requestId", requestId)
            .put("requestType", requestType)
            .put("requestCode", requestType)
            .put("requestLabel", requestLabel)
            .put("terminalId", terminalId)
            .put("requestedAt", Instant.now().toString())
            .put("deviceModel", Build.MODEL.orEmpty())
            .put("source", "android_payment_app_admin_menu")

        val receivers = appContext.packageManager.queryBroadcastReceivers(
            Intent(AdminRequestConstants.ACTION_REPORT_ADMIN_REQUEST),
            0,
        )
        if (receivers.isEmpty()) {
            Log.w(TAG, "ACTION_REPORT_ADMIN_REQUEST: no UIC Home app installed")
            return
        }

        val payloadText = payload.toString()
        for (receiver in receivers) {
            val homePackage = receiver.activityInfo.packageName
            Log.i(
                TAG,
                "Broadcasting ACTION_REPORT_ADMIN_REQUEST -> $homePackage requestId=$requestId " +
                    "requestType=$requestType payloadBytes=${payloadText.toByteArray(Charsets.UTF_8).size}"
            )
            appContext.sendBroadcast(Intent(AdminRequestConstants.ACTION_REPORT_ADMIN_REQUEST).apply {
                `package` = homePackage
                putExtra(AdminRequestConstants.EXTRA_ADMIN_REQUEST_JSON, payloadText)
                putExtra(AdminRequestConstants.EXTRA_ADMIN_REQUEST_ID, requestId)
            })
        }
    }
}
