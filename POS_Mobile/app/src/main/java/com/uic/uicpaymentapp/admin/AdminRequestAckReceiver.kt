package com.uic.uicpaymentapp.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.uic.uicpaymentapp.R

private const val TAG = "AdminRequestAckReceiver"

class AdminRequestAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AdminRequestConstants.ACTION_ADMIN_REQUEST_ACK -> {
                Log.i(TAG, "Admin request ACK requestId=${intent.requestId()}")
                Toast.makeText(context, R.string.admin_request_sent, Toast.LENGTH_LONG).show()
            }
            AdminRequestConstants.ACTION_ADMIN_REQUEST_FAILED -> {
                val error = intent.getStringExtra(AdminRequestConstants.EXTRA_ERROR_MESSAGE).orEmpty()
                Log.w(TAG, "Admin request failed requestId=${intent.requestId()} error=$error")
                Toast.makeText(context, context.getString(R.string.admin_request_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun Intent.requestId(): String =
        getStringExtra(AdminRequestConstants.EXTRA_ADMIN_REQUEST_ID) ?: "(none)"
}
