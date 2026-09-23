package one.globalconnect.paymentapp.ecr

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.settlement.*

/** Command 61 owns the entire all-acquirer operation; no confirmation or navigation is required. */
internal object EcrSettlement {
    fun validate(request: EcrMessage) {
        require(request.command == "61" && request.indicator == 0 && request.response == "00" && !request.more)
        request.validateRequestId()
        require(request.fields.keys.all { it in setOf("80", "RQ", "P1", "P3") })
        require(!request.fields["80"].isNullOrBlank() && request.fields.getValue("80").length <= 64)
        require(request.fields["P1"] in listOf(null, "0", "1"))
        require(request.fields["P3"] in listOf(null, "0", "1"))
    }

    fun resultFields(results: List<SettlementResult>): List<Map<String, String>> = results.flatMap { result ->
        val code = if (result is SettlementResult.Success) "00" else "96"
        val message = if (result is SettlementResult.Failure) result.reason else "Settled"
        val row = listOf(result.acquirerName, code, message).joinToString("|") { EcrReportData.text(it) } + "^"
        row.chunked(900).map { mapOf("SD" to it) }
    }

    suspend fun execute(context: Context, message: EcrMessage, onResults: (List<SettlementResult>) -> Unit, status: (String) -> Unit): List<EcrMessage> {
        val app = GlobalConnectPaymentApplication.instance
        app.nexgoApi.beepAttentionRequired()
        val database = app.tmsDatabase
        val transactions = app.container.transactionRepository.getAllTransactionsStream().first()
        val request = buildAllAcquirersSettlementRequest(database, transactions, includeEmpty = true)
            ?: return EcrReportData.frames(message, emptyList(), false, "ND", "No acquirers configured")
        // Capture and validate report data before settlement removes successful batches.
        val batchNumbers = database.Acquirer.associate { acquirer ->
            acquirer.AcqID to one.globalconnect.paymentapp.uicpos.pos.host.BatchNumberProvider.currentBatchNumber(
                acquirer.AcqID, acquirer.InitBatchNo.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt())
        }
        val fields = EcrReportData.reportFields(transactions, database, audit = true, batchNumbers = batchNumbers)
        EcrReportData.frames(message, fields, false).forEach { it.encode() }
        val results = SettlementCoordinator().execute(request,
            onTargetStart = { status(context.getString(R.string.settlement_processing_acquirer, it.option.name)) },
            onBatchUploadProgress = { current, total -> status(context.getString(R.string.settlement_batch_upload_progress, current, total)) })
        onResults(results)
        val printReceipt = message.fields["P1"] != "0"
        val printAudit = message.fields["P3"] == "1"
        var printed = printReceipt || printAudit
        suspend fun printJob(start: (CompletableDeferred<Boolean>) -> Unit): Boolean {
            val done = CompletableDeferred<Boolean>()
            return try {
                withContext(Dispatchers.Main) { start(done) }
                withTimeoutOrNull(90_000) { done.await() } == true
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { false }
        }
        if (printReceipt) {
            val snapshots = results.filterIsInstance<SettlementResult.Success>().mapNotNull { it.snapshot }
            if (snapshots.isEmpty()) printed = false
            for (snapshot in snapshots) {
                val ok = printJob { done -> NexGoPaymentPrinter.printSettlementReceipt(context, snapshot, database,
                    includeAudit = printAudit, onPrintResult = { done.complete(it) }) }
                if (!ok) { printed = false; break }
            }
        } else if (printAudit) {
            val profile = app.container.profileRepository.get() ?: Profile()
            printed = printJob { done -> NexGoPaymentPrinter.printReport(context, transactions, true, profile, database,
                onPrintResult = { done.complete(it) }) }
        }
        val failed = results.any { it is SettlementResult.Failure }
        val text = if (failed) "One or more acquirers failed; review settlement results"
            else if ((printReceipt || printAudit) && !printed) "Settled; printing failed or timed out"
            else "Settlement completed"
        return EcrReportData.frames(message, fields + resultFields(results), printed, if (failed) "96" else "00", text)
    }
}
