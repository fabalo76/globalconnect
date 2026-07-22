package one.globalconnect.paymentapp.transactions

object TransactionReportConstants {
    const val ACTION_REPORT_TRANSACTION = "one.globalconnect.xtmsagent.ACTION_REPORT_TRANSACTION"
    const val ACTION_TRANSACTION_REPORT_ACK = "one.globalconnect.xtmsagent.ACTION_TRANSACTION_REPORT_ACK"
    const val ACTION_TRANSACTION_REPORT_FAILED = "one.globalconnect.xtmsagent.ACTION_TRANSACTION_REPORT_FAILED"

    const val EXTRA_TRANSACTION_JSON = "transaction_json"
    const val EXTRA_TRANSACTION_ID = "transaction_id"
    const val EXTRA_BATCH_ID = "batch_id"
    const val EXTRA_ERROR_MESSAGE = "error_message"
}
