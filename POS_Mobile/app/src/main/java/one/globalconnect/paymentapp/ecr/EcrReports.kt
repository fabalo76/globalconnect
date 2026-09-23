package one.globalconnect.paymentapp.ecr

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.profile.Profile
import one.globalconnect.paymentapp.transaction.*

internal object EcrReports {
    suspend fun execute(context: Context, request: EcrMessage): List<EcrMessage> {
        val app = GlobalConnectPaymentApplication.instance
        if (request.command == "P4") return lastSettlement(context, request)
        val selectedId = request.fields["AI"]
        if (selectedId != null && app.tmsDatabase.Acquirer.none { it.acquirer_id == selectedId })
            return EcrReportData.frames(request, emptyList(), false, "ND", "Acquirer not found")
        val repository = app.container.transactionRepository
        val storedTransactions = repository.getAllTransactionsStream().first().filter { selectedId == null || it.acquirerId == selectedId }
        val replies = context.getSharedPreferences("ecr_journal", Context.MODE_PRIVATE).all.values
            .filterIsInstance<String>().mapNotNull { stored ->
                runCatching { EcrMessage.decode(java.util.Base64.getDecoder().decode(stored.substringAfter('|'))) }.getOrNull()
            }
        val transactions = storedTransactions.map { transaction ->
            val recovered = EcrPosTransactionId.recover(transaction, replies)
            if (transaction.posTransactionId.isEmpty() && recovered != null &&
                storedTransactions.count { EcrPosTransactionId.recover(it.copy(posTransactionId = ""), replies) == recovered } == 1) {
                transaction.copy(posTransactionId = recovered).also { repository.update(it) }
            } else transaction
        }
        val database = if (selectedId == null) app.tmsDatabase else app.tmsDatabase.copy(
            terminal = app.tmsDatabase.terminal.map { it.copy(acquirer = it.acquirer.filter { acquirer -> acquirer.AcqID == selectedId }) })
        val invoice = request.fields["65"]
        val matches = if (invoice == null) listOfNotNull(transactions.maxByOrNull { it.id })
            else transactions.filter { it.invoiceId.trimStart('0') == invoice.trimStart('0') }
        val transaction = if (request.command == "P1") {
            if (matches.isEmpty()) return EcrReportData.frames(request, emptyList(), false, "ND", "Transaction not found in current batch")
            if (matches.size > 1) return EcrReportData.frames(request, emptyList(), false, "94", "Invoice is ambiguous across acquirers")
            matches.single()
        } else null
        val fields = if (transaction != null) listOf(EcrReportData.receiptFields(transaction, database))
            else EcrReportData.reportFields(transactions, database, request.command == "P2")
        // Validate the complete snapshot before starting an irreversible print job.
        EcrReportData.frames(request, fields, false).forEach { it.encode() }
        var printed = false
        if (request.fields["P1"] != "0") {
            val profile = app.container.profileRepository.get() ?: Profile()
            val result = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                when (request.command) {
                    "P1" -> printTransactionReceipt(transaction!!, app.container.profileRepository,
                        app.container.signatureRepository, context = context, onPrintResult = { result.complete(it) })
                    "P2" -> NexGoPaymentPrinter.printReport(context, transactions, true, profile, database,
                        onPrintResult = { result.complete(it) })
                    "P3", "P5" -> NexGoPaymentPrinter.printTotalsReport(context,
                        createPrintableTotalsReport(context, calcTotals(transactions.filter { it.type != TransactionType.CHECKIN }, database), selectedId),
                        profile, database, onPrintResult = { result.complete(it) })
                }
            }
            printed = withTimeoutOrNull(90_000) { result.await() } == true
        }
        return EcrReportData.frames(request, fields, printed,
            message = if (request.fields["P1"] != "0" && !printed) "Data ready; printing failed or timed out" else "Completed")
    }
    private suspend fun lastSettlement(context: Context, request: EcrMessage): List<EcrMessage> {
        val app = GlobalConnectPaymentApplication.instance
        val snapshots = app.container.settlementStateRepository.observeStates().first()
            .filter { request.fields["AI"] == null || it.acquirerId == request.fields["AI"] }.mapNotNull { it.lastSnapshot }
        if (snapshots.isEmpty()) return EcrReportData.frames(request, emptyList(), false, "ND", "No saved settlement")
        val snapshotDatabase = app.tmsDatabase.copy(terminal = listOf(
            (app.tmsDatabase.Terminal.firstOrNull() ?: one.globalconnect.tms.paymentapp.TMS_Terminal()).copy(
                acquirer = snapshots.map { snapshot ->
                    (app.tmsDatabase.Acquirer.firstOrNull { it.AcqID == snapshot.acquirerId }
                        ?: one.globalconnect.tms.paymentapp.TMS_Acquirer(acquirer_id = snapshot.acquirerId)).copy(
                        acquirerName = snapshot.acquirerName, currencySymbol = snapshot.currencySymbol,
                        merchantId = snapshot.merchantId.orEmpty(), terminalId = snapshot.terminalId.orEmpty())
                })))
        val fields = EcrReportData.reportFields(snapshots.flatMap { it.transactions }, snapshotDatabase, true,
            snapshots.associate { it.acquirerId to it.batchNumber.orEmpty() })
        EcrReportData.frames(request, fields, false).forEach { it.encode() }
        var printed = request.fields["P1"] != "0"
        if (printed) for (snapshot in snapshots) {
            val result = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                NexGoPaymentPrinter.printSettlementReceipt(context, snapshot, snapshotDatabase,
                    onPrintResult = { result.complete(it) })
            }
            if (withTimeoutOrNull(90_000) { result.await() } != true) { printed = false; break }
        }
        return EcrReportData.frames(request, fields, printed)
    }

}
