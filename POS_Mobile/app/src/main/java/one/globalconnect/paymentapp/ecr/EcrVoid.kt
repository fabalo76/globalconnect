package one.globalconnect.paymentapp.ecr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.transaction.*
import one.globalconnect.paymentapp.transactions.TransactionReportBridge
import one.globalconnect.paymentapp.transactions.TransactionReportEvent

internal object EcrVoid {
    fun validate(request: EcrMessage) {
        require(request.command == "42" && request.indicator == 0 && request.response == "00" && !request.more)
        require(request.fields.keys.all { it in setOf("RQ", "80", "65", "P1") })
        request.validateRequestId()
        val id = request.fields["80"].orEmpty()
        require(id.isNotBlank() && id.length <= 64 && id.all { it.code in 32..126 })
        val invoice = request.fields["65"].orEmpty()
        require(invoice.length in 1..12 && invoice.all(Char::isDigit) && invoice.any { it != '0' })
        require(request.fields["P1"] in listOf(null, "0", "1"))
    }

    fun select(request: EcrMessage, transactions: List<Transaction>): Transaction {
        validate(request)
        val invoice = request.fields.getValue("65").trimStart('0')
        val matches = transactions.filter { it.invoiceId.trimStart('0') == invoice }
        require(matches.size == 1) { if (matches.isEmpty()) "Invoice not found in current batch" else "Invoice is ambiguous across acquirers" }
        return matches.single().also {
            require(it.returnStatus != ReturnStatus.Voided) { "Transaction already voided" }
            require(it.returnStatus == ReturnStatus.None) { "Transaction already returned or voided" }
            require(it.type in setOf(TransactionType.SALE, TransactionType.MANUALSALE, TransactionType.REFUND,
                TransactionType.MANUALREFUND, TransactionType.LOYALTY_SALE, TransactionType.QUOTA_SALE,
                TransactionType.EXTRAS_SALE, TransactionType.CASH, TransactionType.PAYMENT)) { "Transaction type cannot be voided through ECR" }
            require(it.ARC in setOf("00", "10")) { "Original transaction is not approved" }
        }
    }

    suspend fun execute(context: Context, request: EcrMessage, onStatus: (String) -> Unit): List<EcrMessage> {
        fun reply(code: String, message: String, fields: Map<String, String> = emptyMap()) = listOf(
            EcrMessage("42", code, 1, fields + mapOf("80" to request.fields.getValue("80"),
                "00" to code, "02" to EcrReportData.text(message).take(120))))
        val app = GlobalConnectPaymentApplication.instance
        val repository = app.container.transactionRepository
        val original = try { select(request, repository.getAllTransactionsStream().first()) }
            catch (e: IllegalArgumentException) { return reply("30", e.message.orEmpty()) }
        val issuer = app.tmsDatabase.Issuer.firstOrNull { it.IssuID == original.issuerId }
        if (issuer?.Void != true) return reply("58", "Void is not enabled for the original issuer")
        // Never send another host void after an uncertain outcome, even with a new ECR ID.
        val journal = context.getSharedPreferences("ecr_void_journal", Context.MODE_PRIVATE)
        val recordKey = "${original.acquirerId}:${original.id}:${original.invoiceId}:${original.localDateTime}"
        if (journal.contains(recordKey)) return reply("TO", "Previous void outcome unknown; reconcile terminal")
        when (EcrVoidInteraction.confirm(original)) {
            false -> return reply("UC", "Void cancelled")
            true -> Unit
        }
        // Recheck the selected record after the operator's confirmation.
        val current = try { select(request, repository.getAllTransactionsStream().first()) }
            catch (e: IllegalArgumentException) { return reply("30", e.message.orEmpty()) }
        if (current != original) return reply("94", "Original transaction changed; submit a new request")
        EcrVoidInteraction.processing(context.getString(one.globalconnect.paymentapp.R.string.msg_processing))
        if (!journal.edit().putBoolean(recordKey, true).commit()) return reply("96", "Unable to record void request")
        EcrDebugLog.message("Void host processing starting", request)
        val result = TransactionReturnProcessor(context).execute(original, ReturnAction.VOID) { onStatus(it.message); EcrVoidInteraction.processing(it.message, (it as? ReturnUiState.Loading)?.processingStatus) }
        if (!result.isSuccess) {
            if (result.responseCode != "TO") journal.edit().remove(recordKey).commit()
            return reply(result.responseCode, result.message)
        }
        repository.update(result.transaction)
        journal.edit().remove(recordKey).commit()
        runCatching {
            TransactionReportBridge.reportTransaction(context, result.transaction, app.tmsDatabase, TransactionReportEvent.Void)
        }.onFailure { Log.w("EcrVoid", "Unable to publish void audit event", it) }
        var printed = false
        if (request.fields["P1"] != "0") {
            try {
                val completion = CompletableDeferred<Boolean>()
                withContext(Dispatchers.Main) {
                    printTransactionReceipt(result.transaction, app.container.profileRepository,
                        app.container.signatureRepository, context = context, onPrintResult = { completion.complete(it) })
                }
                printed = withTimeoutOrNull(90_000) { completion.await() } == true
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { Log.w("EcrVoid", "Void approved but receipt failed", e) }
        }
        return reply("00", "Void approved", EcrReportData.receiptFields(result.transaction, app.tmsDatabase) +
            mapOf("P2" to if (printed) "1" else "0"))
    }
}
