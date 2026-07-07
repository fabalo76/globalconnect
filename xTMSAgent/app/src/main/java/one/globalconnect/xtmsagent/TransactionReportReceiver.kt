package one.globalconnect.xtmsagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import one.globalconnect.xtmsagent.transactions.TransactionReportConstants
import one.globalconnect.xtmsagent.transactions.TransactionReportManager

private const val TAG = "TxnReportReceiver"

class TransactionReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TransactionReportConstants.ACTION_REPORT_TRANSACTION) return

        val payload = intent.getStringExtra(TransactionReportConstants.EXTRA_TRANSACTION_JSON)
        val transactionId = intent.getStringExtra(TransactionReportConstants.EXTRA_TRANSACTION_ID)
        val batchId = intent.getStringExtra(TransactionReportConstants.EXTRA_BATCH_ID)
        Log.i(
            TAG,
            "Transaction report request received action=${intent.action} targetHomePackage=${context.packageName} " +
                "transactionId=${transactionId ?: "(none)"} batchId=${batchId ?: "(none)"} " +
                "payloadBytes=${payload?.toByteArray(Charsets.UTF_8)?.size ?: 0}"
        )

        TransactionReportManager.report(
            context = context.applicationContext,
            payloadText = payload,
            transactionId = transactionId,
            batchId = batchId,
        )
    }
}
