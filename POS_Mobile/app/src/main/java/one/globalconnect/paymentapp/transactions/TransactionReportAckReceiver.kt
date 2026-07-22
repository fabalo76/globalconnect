package one.globalconnect.paymentapp.transactions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "TxnReportAckReceiver"

class TransactionReportAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TransactionReportConstants.ACTION_TRANSACTION_REPORT_ACK -> {
                Log.i(TAG, "Transaction report ACK transactionId=${intent.transactionId()} batchId=${intent.batchId()}")
            }
            TransactionReportConstants.ACTION_TRANSACTION_REPORT_FAILED -> {
                Log.w(
                    TAG,
                    "Transaction report failed transactionId=${intent.transactionId()} batchId=${intent.batchId()} " +
                        "error=${intent.getStringExtra(TransactionReportConstants.EXTRA_ERROR_MESSAGE) ?: "(none)"}"
                )
            }
        }
    }

    private fun Intent.transactionId(): String =
        getStringExtra(TransactionReportConstants.EXTRA_TRANSACTION_ID) ?: "(none)"

    private fun Intent.batchId(): String =
        getStringExtra(TransactionReportConstants.EXTRA_BATCH_ID) ?: "(none)"
}
