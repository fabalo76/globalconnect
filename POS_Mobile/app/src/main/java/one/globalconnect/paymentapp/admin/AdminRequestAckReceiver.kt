package one.globalconnect.paymentapp.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "AdminRequestAckReceiver"

class AdminRequestAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AdminRequestConstants.ACTION_ADMIN_REQUEST_ACK -> {
                Log.i(TAG, "Admin request ACK requestId=${intent.requestId()}")
                AdminRequestStatusActivity.start(context, success = true)
            }
            AdminRequestConstants.ACTION_ADMIN_REQUEST_FAILED -> {
                val error = intent.getStringExtra(AdminRequestConstants.EXTRA_ERROR_MESSAGE).orEmpty()
                Log.w(TAG, "Admin request failed requestId=${intent.requestId()} error=$error")
                AdminRequestStatusActivity.start(context, success = false, errorMessage = error)
            }
        }
    }

    private fun Intent.requestId(): String =
        getStringExtra(AdminRequestConstants.EXTRA_ADMIN_REQUEST_ID) ?: "(none)"
}
