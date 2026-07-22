package one.globalconnect.paymentapp.admin

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import one.globalconnect.paymentapp.R

class AdminRequestStatusActivity : Activity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showStatusDialog(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showStatusDialog(intent)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun showStatusDialog(intent: Intent) {
        val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
        val errorMessage = intent.getStringExtra(AdminRequestConstants.EXTRA_ERROR_MESSAGE).orEmpty()
        val message = if (success) {
            getString(R.string.admin_request_sent_detail)
        } else if (errorMessage.isBlank()) {
            getString(R.string.admin_request_failed_detail)
        } else {
            getString(R.string.admin_request_failed_detail_with_error, errorMessage)
        }

        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(if (success) R.string.admin_request_sent else R.string.admin_request_failed)
            .setMessage(message)
            .setPositiveButton(R.string.admin_request_dismiss) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
            .also {
                it.setCancelable(false)
                it.show()
            }
    }

    companion object {
        private const val EXTRA_SUCCESS = "admin_request_success"

        fun start(context: Context, success: Boolean, errorMessage: String = "") {
            val intent = Intent(context, AdminRequestStatusActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SUCCESS, success)
                .putExtra(AdminRequestConstants.EXTRA_ERROR_MESSAGE, errorMessage)
            context.startActivity(intent)
        }
    }
}
