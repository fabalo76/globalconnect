package one.globalconnect.paymentapp.ecr

import android.content.Context
import android.os.Build
import android.os.SystemClock
import one.globalconnect.paymentapp.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** One transaction owner; disconnects never cancel or implicitly repeat a payment. */
object EcrRuntime {
    val settings = MutableStateFlow(EcrSettings())
    val status = MutableStateFlow("ECR disabled")
    val sale = MutableStateFlow<EcrSale?>(null)
    val reporting = MutableStateFlow(false)
    @Volatile private var reportKey: String? = null
    val busy get() = sale.value != null || reporting.value
    val unlocked = MutableStateFlow(false)
    val passwordRequested = MutableStateFlow(false)
    @Volatile var ready = false
    @Volatile var foreground = false
    @Volatile private var lastActivity = 0L
    private val resetRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var clearCount = 0
    private var tapCount = 0
    private var lastGesture = 0L
    private lateinit var app: Context
    private var server: ServerSocket? = null
    @Volatile private var serial: EcrConnection? = null
    @Volatile private var transportGeneration = 0L
    private val workers = Executors.newCachedThreadPool()
    private val clients = Semaphore(2)
    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val journal get() = app.getSharedPreferences("ecr_journal",Context.MODE_PRIVATE)
    val locked get() = settings.value.enabled && settings.value.kiosk && !unlocked.value

    init {
        scope.launch {
            while (isActive) {
                delay(500)
                if (unlocked.value && SystemClock.elapsedRealtime()-lastActivity >= 60_000) {
                    unlocked.value = false
                    passwordRequested.value = false
                }
            }
        }
    }
    @Synchronized fun configure(context: Context, requestedConfig: EcrSettings = EcrSettings.read(context)) {
        val config = requestedConfig.forDevice(android.os.Build.MODEL)
        app = context.applicationContext
        EcrDebugLog.event { "Configure enabled=${config.enabled} transport=${config.transport} port=${config.port} kiosk=${config.kiosk}" }
        if (settings.value == config && (server != null || serial != null)) return
        check(!busy) { "Finish the active ECR operation before changing settings" }
        transportGeneration++
        serial?.close(); serial = null
        server?.close(); server = null
        sessions.forEach { runCatching { it.close() } }
        settings.value = config
        unlocked.value = false
        if (!config.enabled) { status.value = app.getString(R.string.ecr_disabled); return }
        if (config.transport != "TCP/IP") {
            val generation = transportGeneration
            status.value = app.getString(R.string.ecr_serial_waiting, config.transport, config.serialPort, config.baudRate)
            workers.execute {
                while (generation == transportGeneration && settings.value.enabled) {
                    try {
                        val connection = synchronized(this@EcrRuntime) {
                            if (generation != transportGeneration) null else
                                EcrSerialConnection(com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(app), config).also { serial = it }
                        } ?: break
                        if (!busy) status.value = app.getString(R.string.ecr_serial_waiting, config.transport, config.serialPort, config.baudRate)
                        serve(connection)
                    } catch (e: Exception) {
                        EcrDebugLog.event { "Serial connection failed type=${e.javaClass.simpleName}" }
                        if (generation == transportGeneration && !busy) status.value = app.getString(R.string.ecr_serial_failed, config.transport, config.serialPort)
                    }
                    if (generation == transportGeneration) Thread.sleep(1000)
                }
            }
            return
        }
        try {
            val listener = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(config.port)) }
            server = listener
            EcrDebugLog.event { "TCP listening port=${config.port}" }
            status.value = app.getString(R.string.ecr_waiting, config.port)
            workers.execute {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: Exception) { break }
                    if (!clients.tryAcquire()) { EcrDebugLog.event { "Connection rejected: client limit" }; socket.close(); continue }
                    sessions.add(socket)
                    workers.execute { try { serve(EcrTcpConnection(socket)) } finally { sessions.remove(socket); clients.release() } }
                }
            }
        } catch (e: Exception) { EcrDebugLog.event { "Listen failed type=${e.javaClass.simpleName}" }; status.value = app.getString(R.string.ecr_listen_failed, config.port) }
    }
    fun activity(clear: Boolean = false, tap: Boolean = false) {
        lastActivity = SystemClock.elapsedRealtime()
        if (!locked || !ready || busy || passwordRequested.value) { clearCount=0; tapCount=0; return }
        if (lastActivity-lastGesture > 5_000) { clearCount=0; tapCount=0 }
        lastGesture = lastActivity
        if (clear) { clearCount++; tapCount=0 } else if (tap) { tapCount++; clearCount=0 } else { clearCount=0; tapCount=0 }
        if (clearCount >= 10 || tapCount >= 10) {
            clearCount=0; tapCount=0; passwordRequested.value = true
        }
    }
    fun unlock() { lastActivity=SystemClock.elapsedRealtime(); unlocked.value=true; passwordRequested.value=false }
    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun fingerprint(msg: EcrMessage) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(msg.encode()))
    private fun journalKey(msg: EcrMessage) = (if ("RQ" in msg.fields) "request:" else "") + key(msg.journalIdentity())
    private fun response(msg: EcrMessage, code: String, text: String) = EcrMessage(msg.command, code,1,
        mapOf("80" to msg.fields["80"].orEmpty(), "00" to code, "02" to text)).withRequestIdFrom(msg).also { EcrDebugLog.message("Response prepared", it) }
    @Synchronized private fun accept(msg: EcrMessage): EcrMessage? {
        EcrDebugLog.message("RX request", msg)
        EcrDebugLog.event { "State busy=$busy ready=$ready foreground=$foreground kioskUnlocked=${unlocked.value} operationInProgress=${one.globalconnect.paymentapp.PendingUpdateManager.isOperationInProgress}" }
        try { msg.validateRequestId() } catch (_: IllegalArgumentException) { return response(msg, "30", "Invalid RequestID") }
        if (msg.command == "D0" && msg.indicator == 0 && msg.response == "00" && !msg.more &&
            msg.fields.keys.all { it in setOf("80", "RQ") }) return response(msg, "00", "Communications OK")
        if (msg.command == "D1" && msg.indicator == 0 && msg.fields.isEmpty()) return EcrMessage("D1",indicator=1,fields=mapOf(
            "50" to Build.MODEL.take(60),
            "51" to one.globalconnect.paymentapp.GlobalConnectPaymentApplication.serialNumber.take(60),
            "52" to if(Regex("^(CT20P|N6([ _-]?PRO)?([ _-]?LITE)?)$", RegexOption.IGNORE_CASE).matches(Build.MODEL.trim())) "0" else "1"))
        if (!settings.value.enabled) return response(msg,"91","ECR disabled")
        if (msg.command in setOf("P1", "P2", "P3", "P4", "P5", "42", "61", "F2", "00")) return acceptReport(msg)
        if (msg.command !in setOf("20", "31", "33", "32", "35", "36", "34", "26", "38", "E7", "E6", "10", "CI", "CO")) return response(msg,"30","Unsupported command")
        val request = try { EcrSale.from(msg) } catch (_: Exception) { return response(msg,"30","Invalid transaction request") }
        val contracts = one.globalconnect.paymentapp.transaction.installments.InstallmentContracts
        if ((msg.command in setOf("32", "35") && contracts.forTransaction(request.transactionType) == null) ||
            (msg.command in setOf("34", "36") && contracts.extrasBalanceRequest == null)) return response(msg, "58", "Operation unavailable for this flavor")
        val idKey = journalKey(msg)
        val prior = journal.getString(idKey,null)
        if (prior != null) {
            EcrDebugLog.message(if (prior.substringAfter('|') == "PENDING") "Journal pending request" else "Journal cached reply; no new device interaction", msg)
            val parts = prior.split('|',limit=2)
            if (parts[0] != fingerprint(msg)) return response(msg,"94","Request ID already used with different fields")
            if (parts.getOrNull(1) != "PENDING") return EcrMessage.decode(Base64.getDecoder().decode(parts[1]))
            if (sale.value?.message?.let { journalKey(it) } == idKey) return null
            return response(msg,"TO","Previous result unknown. Reconcile terminal before retrying")
        }
        if (busy || one.globalconnect.paymentapp.PendingUpdateManager.isOperationInProgress || !ready || !foreground || (settings.value.kiosk && unlocked.value)) return response(msg,"BZ","Terminal busy or not ready")
        if (!journal.edit().putString(idKey,fingerprint(msg)+"|PENDING").commit()) return response(msg,"96","Unable to record request")
        EcrDebugLog.message("Starting transaction UI", msg)
        sale.value=request
        status.value=app.getString(R.string.ecr_sale_in_progress)
        return null
    }
    private fun acceptReport(msg: EcrMessage): EcrMessage? {
        try { when (msg.command) { "42" -> EcrVoid.validate(msg); "61" -> EcrSettlement.validate(msg); "00", "F2" -> EcrMaintenance.validate(msg); else -> EcrReportData.validate(msg) } } catch (_: IllegalArgumentException) {
            return response(msg, "30", "Invalid operation request")
        }
        val id = journalKey(msg)
        val prior = journal.getString(id, null)
        if (prior != null) {
            EcrDebugLog.message(if (prior.substringAfter('|') == "PENDING") "Journal pending request" else "Journal cached reply; no new device interaction", msg)
            if (prior.substringBefore('|') != fingerprint(msg)) return response(msg, "94", "Request ID already used with different fields")
            if (prior.substringAfter('|') != "PENDING" || reportKey == id) return null
            return response(msg, "TO", "Previous operation outcome unknown; reconcile terminal before issuing another request")
        }
        if (busy || one.globalconnect.paymentapp.PendingUpdateManager.isOperationInProgress || !ready || !foreground || (settings.value.kiosk && unlocked.value)) return response(msg, "BZ", "Terminal busy or not ready")
        if (!journal.edit().putString(id, fingerprint(msg) + "|PENDING").commit()) return response(msg, "96", "Unable to record request")
        EcrDebugLog.message("Starting operation", msg)
        reportKey = id
        if (msg.command == "61") EcrSettlementInteraction.active.value = true
        reporting.value = true
        status.value = app.getString(when (msg.command) { "42" -> R.string.trans_void; "61" -> R.string.settlement_processing_title; else -> R.string.ecr_report_in_progress })
        one.globalconnect.paymentapp.PendingUpdateManager.isOperationInProgress = true
        scope.launch(Dispatchers.IO) {
            try {
                var settlementResults = emptyList<one.globalconnect.paymentapp.settlement.SettlementResult>()
                val operationReplies = try { when (msg.command) { "42" -> EcrVoid.execute(app, msg) { status.value = it }; "61" -> EcrSettlement.execute(app, msg, onResults = { settlementResults = it }) { status.value = it }; "00", "F2" -> EcrMaintenance.execute(app, msg); else -> EcrReports.execute(app, msg) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { EcrDebugLog.event { "Operation failed type=${e.javaClass.simpleName}" }; if (msg.command == "42") listOf(response(msg, "TO", "Void outcome unknown; reconcile terminal")) else EcrReportData.frames(msg, emptyList(), false, if (msg.command == "61") "TO" else "96", if (msg.command == "61") "Settlement outcome unknown; reconcile terminal before retrying" else "Report could not be completed") }
                val replies = operationReplies.map { it.withRequestIdFrom(msg) }
                replies.forEach { EcrDebugLog.message("Operation completed", it) }
                val stored = replies.joinToString(",") { Base64.getEncoder().encodeToString(it.encode()) }
                if (msg.command == "00") resetRequests.add(id)
                val saved = journal.edit().putString(id, fingerprint(msg) + "|" + stored).commit()
                if (!saved && msg.command == "00") resetRequests.remove(id)
                if (msg.command == "42") {
                    status.value = if (saved && replies.single().response == "00") app.getString(R.string.successful_void)
                        else app.getString(R.string.trans_error)
                    EcrVoidInteraction.result(saved && replies.single().response == "00",
                        app.getString(when (replies.single().response) {
                            "00" -> R.string.successful_void
                            "UC" -> R.string.ecr_void_cancelled
                            "TO" -> R.string.ecr_void_timeout
                            "30", "94" -> if (replies.single().fields["02"] == "Transaction already voided")
                                R.string.ecr_void_already_voided else R.string.ecr_void_invalid
                            "58" -> R.string.ecr_void_disabled
                            else -> R.string.void_failed_title
                        }))
                }
                if (msg.command == "61") {
                    val finalReply = replies.last()
                    val success = saved && finalReply.response == "00"
                    val detail = when {
                        !saved || finalReply.response == "TO" -> app.getString(R.string.ecr_settlement_unknown)
                        finalReply.response == "ND" -> app.getString(R.string.settlement_no_acquirers)
                        success && finalReply.fields["02"]?.contains("printing failed") == true -> app.getString(R.string.ecr_settlement_print_failed)
                        else -> null
                    }
                    EcrSettlementInteraction.show(EcrSettlementResultState(success, settlementResults, detail))
                }
                status.value = app.getString(if (saved) R.string.ecr_waiting else R.string.ecr_reconcile, settings.value.port)
            } finally {
                if (msg.command == "42") EcrVoidInteraction.clear()
                if (msg.command == "61") EcrSettlementInteraction.clear()
                reportKey = null
                reporting.value = false
                one.globalconnect.paymentapp.PendingUpdateManager.isOperationInProgress = false
            }
        }
        return null
    }
    @Synchronized fun finish(code: String, text: String, fields: Map<String,String> = emptyMap()) {
        val active = sale.value ?: return
        val reply = response(active.message,code,text).copy(fields=(fields + mapOf("80" to active.id,"00" to code,"02" to text.take(120))).mapValues { (_,v) -> v.map { if(it.code in 32..126) it else '?' }.joinToString("").take(999) }).withRequestIdFrom(active.message)
        if (!journal.edit().putString(journalKey(active.message),fingerprint(active.message)+"|"+Base64.getEncoder().encodeToString(reply.encode())).commit()) {
            status.value=app.getString(R.string.ecr_reconcile)
        } else status.value=app.getString(R.string.ecr_waiting, settings.value.port)
        sale.value=null
    }
    fun receiptRequested(transactionId: Int): Boolean? = receiptRequested(transactionId.toString())
    fun receiptRequested(transactionId: String): Boolean? = if(::app.isInitialized && journal.contains("receipt_$transactionId")) journal.getBoolean("receipt_$transactionId",false) else null
    suspend fun approved(transaction: one.globalconnect.paymentapp.transaction.Transaction, batch: String = "", resultToken: String = transaction.id.toString()) {
        val active=sale.value ?: return
        // ECR prints here before returning P2; suppress the result screen's automatic second copy.
        journal.edit().putBoolean("receipt_$resultToken",false).commit()
        val printed = if(active.printReceipt) {
            try {
                val application=one.globalconnect.paymentapp.GlobalConnectPaymentApplication.instance
                val result=CompletableDeferred<Boolean>()
                val profile=application.container.profileRepository.get() ?: one.globalconnect.paymentapp.profile.Profile()
                withContext(Dispatchers.Main) {
                    one.globalconnect.paymentapp.printer.NexGoPaymentPrinter.printReceipt(
                        context=app,transaction=transaction,profile=profile,tmsDatabase=application.tmsDatabase,
                        onPrintResult={result.complete(it)})
                }
                withTimeoutOrNull(15_000) { result.await() } == true
            } catch(e: CancellationException) { throw e }
              catch(e: Exception) { false }
        } else false
        fun cents(value: String) = EcrReportData.cents(value).padStart(12, '0')
        val acquirer = one.globalconnect.paymentapp.GlobalConnectPaymentApplication.instance.tmsDatabase.Acquirer
            .firstOrNull { it.acquirer_id == transaction.acquirerId }
        val timestamp = runCatching { java.time.LocalDateTime.parse(transaction.localDateTime,
            one.globalconnect.paymentapp.records.dateTimeFormatter) }.getOrNull()
        finish(if(transaction.ARC=="10") "10" else "00", "Approved", mapOf(
            "01" to transaction.authCode,"30" to transaction.masked_cardNumber,
            "82" to transaction.cardholderName.trim(),
            "03" to timestamp?.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")).orEmpty(),
            "04" to timestamp?.format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")).orEmpty(),
            "67" to batch,
            "47" to (one.globalconnect.paymentapp.transaction.installments.InstallmentContracts.forTransaction(transaction.type)
                ?.savedDetails(transaction.paymentPlan, transaction.paymentPlanQueryResponse)?.count?.toString()?.padStart(2, '0') ?: "00"),
            "PN" to (one.globalconnect.paymentapp.transaction.installments.InstallmentContracts.forTransaction(transaction.type)
                ?.savedDetails(transaction.paymentPlan, transaction.paymentPlanQueryResponse)?.planName.orEmpty()),
            "PD" to transaction.additionalHostPrintData,
            "40" to if (transaction.type in setOf(one.globalconnect.paymentapp.transaction.TransactionType.EXTRAS_BALANCE, one.globalconnect.paymentapp.transaction.TransactionType.BALANCE) && transaction.totalAmount.isBlank()) "" else cents(transaction.totalAmount),"41" to cents(transaction.tipAmount), "42" to cents(transaction.cashbackAmount),
            "44" to cents(transaction.tax1Amount),"45" to cents(transaction.tax2Amount),
            "46" to cents(transaction.tax1DiscountAmount),
            "48" to cents(transaction.loyaltyBalancePoints),
            "16" to acquirer?.terminalId.orEmpty(),"17" to acquirer?.merchantId.orEmpty(),
            "05" to when(transaction.cardEntryMethod) { "MANUAL" -> "01"; "SWIPE" -> "02"; "EMV" -> "05"; "EMV_CONTACTLESS" -> "07"; "FALLBACK_SWIPE" -> "80"; else -> "00" },
            "54" to if(transaction.signatureRequired) "1" else if(transaction.CVM==one.globalconnect.paymentapp.transaction.CVMType.PinVerified) "4" else "2",
            "65" to transaction.invoiceId,"78" to transaction.stan,"79" to transaction.retrievalReferenceNumber,
            "70" to active.message.command,"90" to transaction.AID,"91" to transaction.applicationLabel,
            "92" to transaction.TVR,"93" to transaction.TSI,"P2" to if(printed) "1" else "0",
            "53" to ((active.currency ?: acquirer?.currencyCode?.toString()?.padStart(3,'0').orEmpty())+"|"+acquirer?.currencySymbol.orEmpty())).filter { (tag, value) -> tag != "40" || value.isNotBlank() })
    }
    private fun completed(id: String): List<EcrMessage>? {
        val stored = journal.getString(id,null)?.substringAfter('|') ?: return null
        return if (stored == "PENDING") null else stored.split(',').map {
            val original = EcrMessage.decode(Base64.getDecoder().decode(it))
            original.normalizeLegacyVoidCancellation().also { normalized ->
                if (normalized != original) EcrDebugLog.message("Legacy confirmation timeout returned as cancellation; no new operation", normalized)
            }
        }
    }
    private fun serve(connection: EcrConnection) {
        EcrDebugLog.event { "Connected ${connection.description}" }
        connection.use {
            val input=connection.input; val output=connection.output
            var awaitingId: String?=null
            var lastFrame: ByteArray?=null
            val pendingFrames = java.util.ArrayDeque<EcrMessage>()
            var sentReply: EcrMessage? = null
            var sentAt=0L; var attempts=0
            fun send(reply: EcrMessage) {
                EcrDebugLog.message("TX reply", reply)
                sentReply = reply
                val frame=EcrMessage.frame(reply.encode()); output.write(frame); output.flush()
                lastFrame=frame; sentAt=SystemClock.elapsedRealtime(); attempts=1
            }
            try {
                while (!connection.closed && settings.value.enabled) {
                    if (awaitingId != null && lastFrame == null && pendingFrames.isEmpty()) completed(awaitingId!!)?.let { pendingFrames.addAll(it); awaitingId=null }
                    if (lastFrame == null && pendingFrames.isNotEmpty()) send(pendingFrames.removeFirst())
                    if (lastFrame != null && SystemClock.elapsedRealtime()-sentAt > 2_000) {
                        if (attempts >= 3) { EcrDebugLog.event { "ACK timeout; retry limit reached" }; return }
                        EcrDebugLog.event { "TX retry attempt=${attempts + 1}" }
                        output.write(lastFrame!!); output.flush(); attempts++; sentAt=SystemClock.elapsedRealtime()
                    }
                    val b=try { input.read() } catch (_: SocketTimeoutException) { continue }
                    if (b < 0) return
                    when(b) {
                        6 -> {
                            EcrDebugLog.event { "RX ACK" }; lastFrame=null
                            sentReply?.takeIf { it.command == "00" && it.response == "00" && !it.more }?.let { reply ->
                                if (resetRequests.remove(journalKey(reply.copy(indicator = 0)))) scope.launch(Dispatchers.Main) {
                                    while (isActive) {
                                        val restarted = synchronized(this@EcrRuntime) {
                                            if (busy || one.globalconnect.paymentapp.PendingUpdateManager.isOperationInProgress) false
                                            else {
                                                ready = false
                                                unlocked.value = false; passwordRequested.value = false
                                                app.startActivity(android.content.Intent(app, one.globalconnect.paymentapp.MainActivity::class.java)
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                                true
                                            }
                                        }
                                        if (restarted) break
                                        delay(100)
                                    }
                                }
                            }
                            sentReply = null
                        }
                        21 -> { EcrDebugLog.event { "RX NAK" }; sentAt=0L }
                        4 -> { EcrDebugLog.event { "RX EOT" }; return }
                        2 -> {
                            // Partial-frame timeout closes this session; no half-message is executed.
                            val payload=try { EcrMessage.readPayload(input) } catch (_: IllegalArgumentException) { EcrDebugLog.event { "Invalid frame/LRC; TX NAK and close" }; output.write(21); return }
                            val msg=try { EcrMessage.decode(payload) } catch (_: IllegalArgumentException) { EcrDebugLog.event { "Invalid payload; TX NAK" }; output.write(21); continue }
                            output.write(6); output.flush()
                            val reply=accept(msg)
                            pendingFrames.clear()
                            lastFrame = null
                            awaitingId = null
                            if (reply != null) send(reply) else awaitingId=journalKey(msg)
                        }
                        else -> { output.write(21); output.flush() }
                    }
                }
            } catch (e: Exception) { EcrDebugLog.event { "Session ended type=${e.javaClass.simpleName}" } }
            finally { EcrDebugLog.event { "Disconnected ${connection.description}" } }
        }
    }
}
