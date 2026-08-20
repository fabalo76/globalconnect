package one.globalconnect.paymentapp.transactions

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.paymentapp.security.EncryptionUtil
import one.globalconnect.paymentapp.settlement.SettlementTarget
import one.globalconnect.paymentapp.transaction.ReturnStatus
import one.globalconnect.paymentapp.transaction.Transaction
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.Locale

private const val TAG = "TxnReportBridge"
private const val PREFS_NAME = "transaction_reporting"
private const val KEY_QUEUE = "pending_transactions"
private const val KEY_OLDEST_AT = "oldest_pending_at"
private const val METHOD_BATCHING = "Batching"
private const val METHOD_DISABLED = "Disabled"
private const val DEFAULT_BATCH_SIZE = 5
private const val DEFAULT_INTERVAL_SECONDS = 300L
private val localDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

object TransactionReportBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var flushRunnable: Runnable? = null

    fun reportTransaction(
        context: Context,
        transaction: Transaction,
        tmsDatabase: TMSDATA,
        event: TransactionReportEvent = TransactionReportEvent.Auto,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            reportTransactionInternal(appContext, transaction, tmsDatabase, event)
        }
    }

    private fun reportTransactionInternal(
        context: Context,
        transaction: Transaction,
        tmsDatabase: TMSDATA,
        event: TransactionReportEvent,
    ) {
        val appContext = context.applicationContext
        val policy = TransactionReportingPolicy.from(tmsDatabase)
        if (!policy.isEnabled) {
            Log.i(TAG, "Transaction reporting is disabled; skipping transaction report")
            return
        }
        val payload = transaction.toReportJson(event, tmsDatabase)
        if (!policy.isBatching) {
            sendPayload(appContext, payload, payload.optString("transactionId"), null)
            return
        }

        enqueue(appContext, payload)
        val queue = readQueue(appContext)
        val oldestAt = oldestQueuedAt(appContext)
        val elapsedSeconds = ((System.currentTimeMillis() - oldestAt).coerceAtLeast(0L)) / 1000L
        if (queue.length() >= policy.batchSize || elapsedSeconds >= policy.intervalSeconds) {
            flush(appContext, policy)
        } else {
            scheduleFlush(appContext, policy)
        }
    }

    fun flush(context: Context, policy: TransactionReportingPolicy) {
        val appContext = context.applicationContext
        scope.launch {
            flushInternal(appContext, policy)
        }
    }

    fun reportSettlement(
        context: Context,
        target: SettlementTarget,
        acquirer: TMS_Acquirer,
        batchNumber: String?,
        responseCode: String?,
        tmsDatabase: TMSDATA,
    ) {
        if (!TransactionReportingPolicy.from(tmsDatabase).isEnabled) {
            Log.i(TAG, "Transaction reporting is disabled; skipping settlement report")
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            val payload = target.toSettlementReportJson(acquirer, batchNumber, responseCode)
            sendPayload(appContext, payload, null, payload.optString("batchId").takeIf { it.isNotBlank() })
        }
    }

    fun onParametersUpdated(context: Context, tmsDatabase: TMSDATA) {
        val policy = TransactionReportingPolicy.from(tmsDatabase)
        if (policy.isEnabled) return

        flushRunnable?.let(handler::removeCallbacks)
        flushRunnable = null
        val pendingCount = readQueue(context.applicationContext).length()
        clearQueue(context.applicationContext)
        Log.i(TAG, "Transaction reporting disabled by parameters; cleared $pendingCount pending report(s)")
    }

    private fun flushInternal(context: Context, policy: TransactionReportingPolicy) {
        val appContext = context.applicationContext
        val queue = readQueue(appContext)
        if (queue.length() == 0) return

        val batchId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("batchId", batchId)
            .put("transactions", queue)

        clearQueue(appContext)
        sendPayload(appContext, payload, null, batchId)
    }

    private fun sendPayload(context: Context, payload: JSONObject, transactionId: String?, batchId: String?) {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(TransactionReportConstants.ACTION_REPORT_TRANSACTION),
            0,
        )
        if (receivers.isEmpty()) {
            Log.w(TAG, "ACTION_REPORT_TRANSACTION: no xTMSAgent app installed")
            return
        }

        val payloadText = payload.toString()
        for (receiver in receivers) {
            val homePackage = receiver.activityInfo.packageName
            Log.i(
                TAG,
                "Broadcasting ACTION_REPORT_TRANSACTION -> $homePackage transactionId=${transactionId ?: "(none)"} " +
                    "batchId=${batchId ?: "(none)"} payloadBytes=${payloadText.toByteArray(Charsets.UTF_8).size}"
            )
            context.sendBroadcast(Intent(TransactionReportConstants.ACTION_REPORT_TRANSACTION).apply {
                `package` = homePackage
                putExtra(TransactionReportConstants.EXTRA_TRANSACTION_JSON, payloadText)
                if (!transactionId.isNullOrBlank()) putExtra(TransactionReportConstants.EXTRA_TRANSACTION_ID, transactionId)
                if (!batchId.isNullOrBlank()) putExtra(TransactionReportConstants.EXTRA_BATCH_ID, batchId)
            })
        }
    }

    private fun enqueue(context: Context, payload: JSONObject) {
        val queue = readQueue(context)
        queue.put(payload)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_QUEUE, queue.toString())
        if (!prefs.contains(KEY_OLDEST_AT) || queue.length() == 1) {
            editor.putLong(KEY_OLDEST_AT, System.currentTimeMillis())
        }
        editor.apply()
        Log.i(TAG, "Queued transaction report transactionId=${payload.optString("transactionId")} pending=${queue.length()}")
    }

    private fun scheduleFlush(context: Context, policy: TransactionReportingPolicy) {
        flushRunnable?.let(handler::removeCallbacks)
        val delayMs = policy.intervalSeconds.coerceAtLeast(1L) * 1000L
        flushRunnable = Runnable {
            scope.launch {
                flushInternal(context.applicationContext, policy)
            }
        }
        handler.postDelayed(flushRunnable!!, delayMs)
    }

    private fun readQueue(context: Context): JSONArray {
        val text = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_QUEUE, null)
        return try {
            if (text.isNullOrBlank()) JSONArray() else JSONArray(text)
        } catch (e: Exception) {
            Log.w(TAG, "Pending transaction queue is invalid; clearing it", e)
            clearQueue(context)
            JSONArray()
        }
    }

    private fun oldestQueuedAt(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_OLDEST_AT, System.currentTimeMillis())

    private fun clearQueue(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_QUEUE)
            .remove(KEY_OLDEST_AT)
            .apply()
    }
}

enum class TransactionReportEvent {
    Auto,
    Void,
    Reversal,
    TipAdjust,
}

data class TransactionReportingPolicy(
    val method: String,
    val batchSize: Int,
    val intervalSeconds: Long,
) {
    val isEnabled: Boolean get() = !method.equals(METHOD_DISABLED, ignoreCase = true)
    val isBatching: Boolean get() = method.equals(METHOD_BATCHING, ignoreCase = true)

    companion object {
        fun from(tmsDatabase: TMSDATA): TransactionReportingPolicy {
            val terminal = tmsDatabase.Terminal.firstOrNull()
            return TransactionReportingPolicy(
                method = terminal?.tranReportingMethod?.trim()?.ifBlank { "Online" } ?: "Online",
                batchSize = terminal?.tranReportingBatchSize?.toInt()?.takeIf { it > 0 } ?: DEFAULT_BATCH_SIZE,
                intervalSeconds = terminal?.tranReportingIntervalSeconds?.toLong()?.takeIf { it > 0L }
                    ?: DEFAULT_INTERVAL_SECONDS,
            )
        }
    }
}

internal fun Transaction.toReportJson(event: TransactionReportEvent, tmsDatabase: TMSDATA): JSONObject {
    val transactionGuid = stableTransactionGuid()
    val reportEvent = resolveReportEvent(event)
    val eventAt = Instant.now().toString()
    val currencyCode = reportCurrencyCode(tmsDatabase)
    val transactionType = if (reportEvent == TransactionReportEvent.TipAdjust) {
        "tip_adjust"
    } else {
        type.name.lowercase()
    }
    val isVoided = reportEvent == TransactionReportEvent.Void
    val isReversed = reportEvent == TransactionReportEvent.Reversal
    val rawData = JSONObject()
        .put("posTransactionId", transactionId)
        .put("rowId", id)
        .put("authorizationId", authorizationId)
        .put("cardRangeId", cardRangeId)
        .put("cardRangeName", cardRangeName)
        .put("currencyCode", currencyCode)
        .put("externalReferenceNumber", externalReferenceNumber)
        .put("folioNumber", folioNumber)
        .put("originalTransactionId", originalTransactionId)
        .put("tipProcessingInformation", tipProcessingInformation)
        .put("signatureRequired", signatureRequired)
        .put("signatureCaptured", signatureCaptured)
        .put("reportEvent", reportEvent.name.lowercase())

    return JSONObject()
        .put("transactionId", transactionGuid)
        .put("acquirerCode", acquirerId)
        .put("issuerCode", issuerId)
        .put("transactionType", transactionType)
        .put("amount", totalAmount.toDecimal())
        .put("currencyCode", currencyCode)
        .put("approvalCode", authCode)
        .put("responseCode", ARC.ifBlank { "00" })
        .put("isApproved", authCode.isNotBlank() && !authCode.equals("Err", ignoreCase = true))
        .put("isVoided", isVoided)
        .put("isReversed", isReversed)
        .put("originalTransactionType", originalTransactionId.takeIf { it.isNotBlank() }?.let { "sale" })
        .put("localDate", localDateTime.take(10))
        .put("localTime", localDateTime.drop(11).take(8))
        .put("rrn", retrievalReferenceNumber)
        .put("stan", stan)
        .put("invoice", invoiceId)
        .put("maskedPan", reportMaskedPan())
        .put("cardBrand", cardType)
        .put("entryMode", cardEntryMethod)
        .put("emvAid", AID)
        .put("emvAppName", applicationLabel.ifBlank { applicationName })
        .put("emvCvmr", CVMText)
        .put("emvTvr", TVR)
        .put("emvTsi", TSI)
        .put("tipAmount", tipAmount.toDecimal())
        .put("tax1Amount", tax1Amount.toDecimal())
        .put("tax1DiscountAmount", tax1DiscountAmount.toDecimal())
        .put("tax2Amount", tax2Amount.toDecimal())
        .put("transactionAt", transactionAtIso())
        .apply {
            when (reportEvent) {
                TransactionReportEvent.Void -> put("voidedAt", eventAt)
                TransactionReportEvent.Reversal -> put("reversedAt", eventAt)
                TransactionReportEvent.TipAdjust -> put("tipAdjustedAt", eventAt)
                TransactionReportEvent.Auto -> Unit
            }
        }
        .put("rawData", rawData)
}

private fun Transaction.resolveReportEvent(event: TransactionReportEvent): TransactionReportEvent {
    if (event != TransactionReportEvent.Auto) return event
    return when {
        returnStatus == ReturnStatus.Voided || type.name.equals("VOID", ignoreCase = true) -> TransactionReportEvent.Void
        type.name.equals("REVERSAL", ignoreCase = true) -> TransactionReportEvent.Reversal
        else -> TransactionReportEvent.Auto
    }
}

private fun Transaction.stableTransactionGuid(): String =
    runCatching { UUID.fromString(transactionId).toString() }
        .getOrElse {
            UUID.nameUUIDFromBytes("pos-mobile:$id:$transactionId:$authCode:$localDateTime".toByteArray()).toString()
        }

private fun SettlementTarget.toSettlementReportJson(
    acquirer: TMS_Acquirer,
    batchNumber: String?,
    responseCode: String?,
): JSONObject {
    val settledAt = Instant.now().toString()
    val transactionRefs = JSONArray()
    transactions.forEachIndexed { index, transaction ->
        transactionRefs.put(transaction.toSettlementTransactionRef(index))
    }

    return JSONObject()
        .put("messageType", "settlement_report")
        .put("type", "settlement")
        .put("batchId", UUID.randomUUID().toString())
        .put("acquirerCode", acquirer.AcqID.ifBlank { option.id })
        .put("acquirerName", acquirer.AcquirerName.ifBlank { option.name })
        .put("terminalId", option.terminalId ?: "")
        .put("merchantId", option.merchantId ?: "")
        .put("paymentMerchantId", option.merchantId ?: "")
        .put("currencyCode", acquirer.reportCurrencyCode())
        .put("settledAt", settledAt)
        .put("settlementDate", settledAt.take(10))
        .put("batchNumber", batchNumber.orEmpty())
        .put("deviceBatchNumber", batchNumber.orEmpty())
        .put("transactionCount", transactions.size)
        .put("transactionIdCount", transactionRefs.length())
        .put("totalAmount", settlementTotalAmount())
        .put("responseCode", responseCode ?: "")
        .put("transactions", transactionRefs)
        .put("totals", totalsToJson())
}

private fun Transaction.reportCurrencyCode(tmsDatabase: TMSDATA): String =
    tmsDatabase.Acquirer
        .firstOrNull { it.AcqID.equals(acquirerId, ignoreCase = true) }
        ?.reportCurrencyCode()
        ?: SysParam.getInstance().CurrCode.toReportCurrencyCodeFallback()

private fun TMS_Acquirer.reportCurrencyCode(): String =
    CurrencyCode
        .takeIf { it > 0L }
        ?.toString()
        ?.padStart(3, '0')
        ?: SysParam.getInstance().CurrCode.toReportCurrencyCodeFallback()

private fun String.toReportCurrencyCodeFallback(): String {
    val normalized = trim().uppercase(Locale.US)
    if (normalized.isBlank()) return "840"
    return when (normalized) {
        "USD", "US$" -> "840"
        "HNL", "LPS", "L" -> "340"
        "CRC" -> "188"
        "GTQ" -> "320"
        "PAB" -> "591"
        "SVC" -> "222"
        "MXN" -> "484"
        "BZD" -> "084"
        else -> if (normalized.all { it.isDigit() }) normalized.padStart(3, '0') else normalized
    }
}

private fun SettlementTarget.settlementTotalAmount(): BigDecimal =
    transactions.fold(BigDecimal.ZERO) { acc, transaction -> acc + transaction.totalAmount.toDecimal() }

private fun SettlementTarget.totalsToJson(): JSONObject {
    val json = JSONObject()
    totals.orderedEntries().forEach { (kind, metric) ->
        json.put(
            kind.name.lowercase(Locale.US),
            JSONObject()
                .put("count", metric.count)
                .put("amount", metric.amount)
        )
    }
    return json
}

private fun Transaction.toSettlementTransactionRef(logIndex: Int): JSONObject =
    JSONObject()
        .put("transactionId", stableTransactionGuid())
        .put("logIndex", logIndex)
        .put("transactionType", type.name.lowercase(Locale.US))
        .put("amount", totalAmount.toDecimal())
        .put("transactionAt", transactionAtIso())
        .apply {
            invoiceId.takeIf { it.isNotBlank() }?.let { put("invoice", it) }
            stan.takeIf { it.isNotBlank() }?.let { put("stan", it) }
            retrievalReferenceNumber.takeIf { it.isNotBlank() }?.let { put("rrn", it) }
            authCode.takeIf { it.isNotBlank() }?.let { put("approvalCode", it) }
        }

private fun Transaction.transactionAtIso(): String {
    val parsed = try {
        LocalDateTime.parse(localDateTime, localDateTimeFormatter)
    } catch (_: DateTimeParseException) {
        LocalDateTime.now()
    }
    return parsed.atZone(ZoneId.systemDefault()).toInstant().toString()
}

private fun String.toDecimal(): BigDecimal =
    runCatching { BigDecimal(replace(",", "").ifBlank { "0" }) }.getOrDefault(BigDecimal.ZERO)

private fun Transaction.reportMaskedPan(): String {
    masked_cardNumber.firstSixLastFourMasked()?.let { return it }
    val decryptedPan = runCatching {
        cardNumber.takeIf { it.isNotBlank() }?.let(EncryptionUtil::decryptData)
    }.getOrNull()
    return decryptedPan?.firstSixLastFourMasked() ?: masked_cardNumber
}

private fun String.firstSixLastFourMasked(): String? {
    val digits = filter { it.isDigit() }
    if (digits.length < 10) return null
    return buildString {
        append(digits.take(6))
        repeat((digits.length - 10).coerceAtLeast(0)) { append('*') }
        append(digits.takeLast(4))
    }
}
