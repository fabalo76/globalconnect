package com.uic.uicpaymentapp.settlement

import android.util.Log
import com.uic.tms.payment_app.TMS_Acquirer
import com.uic.tms.payment_app.TMS_HostConnectionInfo
import com.uic.tms.payment_app.TMS_Terminal
import com.uic.tms.payment_app.TMSDATA
import com.uic.uicpaymentapp.UICApplication
import com.uic.uicpaymentapp.dao.TransactionRepository
import com.uic.uicpaymentapp.records.dateTimeFormatter
import com.uic.uicpaymentapp.security.EncryptionUtil
import com.uic.uicpaymentapp.settlement.storage.SettlementSnapshot
import com.uic.uicpaymentapp.settlement.storage.SettlementStateRepository
import com.uic.uicpaymentapp.transaction.Transaction
import com.uic.uicpaymentapp.transaction.TransactionType
import com.uic.uicpaymentapp.transaction.toPrinterString
import com.uic.uicpaymentapp.transaction.toTransactionString
import com.uic.uicpaymentapp.transactions.TransactionReportBridge
import com.uic.pos.iso8583.IsoMessageFactory
import com.uic.uicpaymentapp.uicpos.pos.host.BatchNumberProvider
import com.uic.uicpaymentapp.uicpos.pos.host.HostMessageBuilder
import com.uic.uicpaymentapp.uicpos.pos.host.HostProcessingEvent
import com.uic.uicpaymentapp.uicpos.pos.host.HostResponseMessageResolver
import com.uic.uicpaymentapp.uicpos.pos.host.HostSettings
import com.uic.uicpaymentapp.uicpos.pos.host.HostTransactionClient
import com.uic.uicpaymentapp.uicpos.pos.host.HostTransactionRequest
import com.uic.uicpaymentapp.uicpos.pos.host.HostTransactionSession
import com.uic.uicpaymentapp.uicpos.pos.host.HostTransactionResult
import com.uic.uicpaymentapp.uicpos.pos.host.IsoMessageFactoryProvider
import com.uic.uicpaymentapp.uicpos.pos.host.LengthPrefixRegistry
import com.uic.uicpaymentapp.uicpos.pos.host.TransactionConfigRegistry
import com.uic.uicpaymentapp.uicpos.pos.host.AcquirerSslCache
import com.uic.uicpaymentapp.uicpos.pos.host.parseHostAddress
import com.uic.uicpaymentapp.uicpos.pos.host.InvoiceNumberProvider
import com.uic.uicpaymentapp.uicpos.pos.host.StanProvider
import com.uic.uicpaymentapp.uicpos.pos.host.formatHostErrorMessage
import com.uic.uicpaymentapp.uicpos.pos.model.ProcInfo
import com.uic.uicpaymentapp.uicpos.pos.model.SysParam
import com.uic.uicpaymentapp.uicpos.pos.model.TransLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

private const val TAG = "SettlementCoordinator"
private const val DEFAULT_CONNECT_TIMEOUT_SEC = 10
private const val DEFAULT_READ_TIMEOUT_SEC = 60
private const val BATCH_UPLOAD_MESSAGE_TYPE = "0320"
private const val SETTLEMENT_CLOSE_PROCESSING_CODE = "960000"
private const val FLOW_CONTROL_MORE = '1'
private const val FLOW_CONTROL_LAST = '0'

/**
 * Coordinates the transmission of settlement messages for the selected acquirers.
 */
class SettlementCoordinator(
    private val transactionRepository: TransactionRepository =
        UICApplication.instance.container.transactionRepository,
    private val settlementStateRepository: SettlementStateRepository =
        UICApplication.instance.container.settlementStateRepository,
    private val hostMessageBuilder: HostMessageBuilder = HostMessageBuilder(),
    private val hostClient: HostTransactionClient = HostTransactionClient(),
) {

    private val mutex = Mutex()

    suspend fun execute(
        request: SettlementRequest,
        onTargetStart: (SettlementTarget) -> Unit = {},
        onHostEvent: (HostProcessingEvent) -> Unit = {},
        onTargetResult: (message: String, success: Boolean) -> Unit = { _, _ -> },
        onTargetError: (message: String) -> Unit = {},
    ): List<SettlementResult> = mutex.withLock {
        if (request.targets.isEmpty()) {
            Log.d(TAG, "execute called with no targets; returning empty result")
            return emptyList()
        }
        withContext(Dispatchers.IO) {
            val isoFactory = IsoMessageFactoryProvider.factoryFor()
            val database = UICApplication.instance.container.tmsDatabase
            val results = mutableListOf<SettlementResult>()
            request.targets.forEach { target ->
                onTargetStart(target)
                val acquirerId = target.option.id
                val acquirer = database.Acquirer.firstOrNull { it.AcqID == acquirerId }
                if (acquirer == null) {
                    Log.w(TAG, "Missing acquirer configuration for id=$acquirerId")
                    val reason = "Missing acquirer configuration"
                    onTargetError(reason)
                    results += SettlementResult.Failure(
                        acquirerId = acquirerId,
                        acquirerName = target.option.name.ifBlank { acquirerId },
                        reason = reason,
                    )
                    return@forEach
                }

                val ipProfile = database.IPTab.firstOrNull { it.IPTabID == acquirer.IPTabTran }
                if (ipProfile == null) {
                    Log.w(TAG, "Missing IP profile ${acquirer.IPTabTran} for acquirer=${acquirer.AcqID}")
                    val reason = "Missing host profile"
                    onTargetError(reason)
                    results += SettlementResult.Failure(
                        acquirerId = acquirer.AcqID,
                        acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                        reason = reason,
                    )
                    return@forEach
                }

                val terminal = selectTerminal(database, acquirer)
                if (terminal == null) {
                    Log.w(TAG, "Missing terminal configuration for acquirer=${acquirer.AcqID}")
                    val reason = "Missing terminal configuration"
                    onTargetError(reason)
                    results += SettlementResult.Failure(
                        acquirerId = acquirer.AcqID,
                        acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                        reason = reason,
                    )
                    return@forEach
                }

                val hostSettings = buildHostSettings(acquirer, ipProfile, terminal)
                if (hostSettings.primary == null && hostSettings.secondary == null) {
                    Log.w(TAG, "No host endpoints configured for acquirer=${acquirer.AcqID}")
                    val reason = "Host address not configured"
                    onTargetError(reason)
                    results += SettlementResult.Failure(
                        acquirerId = acquirer.AcqID,
                        acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                        reason = reason,
                    )
                    return@forEach
                }

                val sslSocketFactory = if (hostSettings.isTls) AcquirerSslCache.get(ipProfile.IPTabID.toString()) else null

                markSettlementPending(target, acquirer)

                val stan = StanProvider.nextStan()
                val invoiceId = InvoiceNumberProvider.nextInvoiceNumber()
                val procInfo = buildProcInfo(target, acquirer, stan, invoiceId)

                val openMessage = try {
                    hostMessageBuilder.build(
                        acquirer = acquirer,
                        procInfo = procInfo,
                        isoFactory = isoFactory,
                        stanSupplier = { stan },
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to build ISO8583 request for acquirer=${acquirer.AcqID}", error)
                    val reason = error.localizedMessage ?: "Unable to build host message"
                    onTargetError(reason)
                    results += SettlementResult.Failure(
                        acquirerId = acquirer.AcqID,
                        acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                        reason = reason,
                    )
                    return@forEach
                }

                val connectTimeoutMs = secondsToMillis(hostSettings.connectTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SEC)
                val readTimeoutMs = secondsToMillis(hostSettings.readTimeoutSeconds, DEFAULT_READ_TIMEOUT_SEC)

                val requestFrame = HostTransactionRequest(
                    acquirer = acquirer,
                    ipProfile = ipProfile,
                    terminal = terminal,
                    message = openMessage,
                    clearPan = procInfo.TransLog.PAN,
                    track1 = procInfo.TransLog.Track1,
                    track2 = procInfo.TransLog.Track2,
                    track3 = procInfo.TransLog.Track3,
                    lengthConfig = hostSettings.length,
                    primaryEndpoint = hostSettings.primary,
                    secondaryEndpoint = hostSettings.secondary,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    attempts = hostSettings.attempts,
                    primaryRetries = hostSettings.primaryRetries,
                    secondaryRetries = hostSettings.secondaryRetries,
                    useTls = hostSettings.isTls,
                    sslSocketFactory = sslSocketFactory,
                    isoFactory = isoFactory,
                    onStatusChanged = onHostEvent,
                )

                val session = hostClient.createSession()
                try {
                    Log.d(TAG, "Transmitting settlement for acquirer=${acquirer.AcqID} stan=$stan")
                    val openResult = hostClient.execute(requestFrame, session)
                    val responseCode = openResult.isoMessage.getFieldValue(39)
                    recordResponse(procInfo, openResult)
                    val responseMessage = HostResponseMessageResolver.resolveOrFallback(responseCode)

                    when (responseCode) {
                        "00" -> {
                            val snapshot = buildSettlementSnapshot(target, acquirer, responseCode)
                            val clearError = clearLocalBatch(target, acquirer, onTargetError)
                            if (clearError != null) {
                                results += SettlementResult.Failure(
                                    acquirerId = acquirer.AcqID,
                                    acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                                    reason = clearError,
                                )
                                return@forEach
                            }
                            TransactionReportBridge.reportSettlement(
                                context = UICApplication.instance.applicationContext,
                                target = target,
                                acquirer = acquirer,
                                batchNumber = currentBatchNumber(acquirer),
                                responseCode = responseCode,
                            )
                            snapshot?.let { storeSettlementSnapshot(it) }
                            advanceBatchNumber(acquirer)
                            clearPendingState(acquirer.AcqID)
                            onTargetResult(responseMessage, true)
                            results += SettlementResult.Success(
                                acquirerId = acquirer.AcqID,
                                acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                                transactionCount = target.transactions.size,
                                totalAmount = procInfo.TransLog.TotalAmt ?: "0.00",
                                currencySymbol = target.option.currencySymbol,
                                responseCode = responseCode ?: "00",
                                snapshot = snapshot,
                            )
                        }
                        "95" -> {
                            Log.i(TAG, "Settlement requires batch upload for acquirer=${acquirer.AcqID}")
                            val uploadOutcome = performBatchUpload(
                                transactions = target.transactions,
                                acquirer = acquirer,
                                ipProfile = ipProfile,
                                terminal = terminal,
                                hostSettings = hostSettings,
                                isoFactory = isoFactory,
                                sslSocketFactory = sslSocketFactory,
                                connectTimeoutMs = connectTimeoutMs,
                                readTimeoutMs = readTimeoutMs,
                                onHostEvent = onHostEvent,
                                session = session,
                            )

                            if (!uploadOutcome.success) {
                                val failureReason = uploadOutcome.message
                                    ?: uploadOutcome.responseCode?.let { "Host response $it" }
                                    ?: "Batch upload failed"
                                if (uploadOutcome.fatal) {
                                    onTargetError(failureReason)
                                } else {
                                    onTargetResult(
                                        uploadOutcome.message
                                            ?: HostResponseMessageResolver.resolveOrFallback(uploadOutcome.responseCode),
                                        false
                                    )
                                }
                                results += SettlementResult.Failure(
                                    acquirerId = acquirer.AcqID,
                                    acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                                    reason = failureReason,
                                )
                                return@forEach
                            }

                            val closeStan = StanProvider.nextStan()
                            val closeProcInfo = buildProcInfo(target, acquirer, closeStan, invoiceId)
                            val closeMessage = hostMessageBuilder.build(
                                acquirer = acquirer,
                                procInfo = closeProcInfo,
                                isoFactory = isoFactory,
                                stanSupplier = { closeStan },
                            )
                            closeMessage.setFieldValue(3, SETTLEMENT_CLOSE_PROCESSING_CODE)

                            val closeRequest = requestFrame.copy(
                                message = closeMessage,
                                clearPan = closeProcInfo.TransLog.PAN,
                                track1 = closeProcInfo.TransLog.Track1,
                                track2 = closeProcInfo.TransLog.Track2,
                                track3 = closeProcInfo.TransLog.Track3,
                            )

                            Log.d(TAG, "Transmitting settlement close for acquirer=${acquirer.AcqID} stan=$closeStan")
                            val closeResult = hostClient.execute(closeRequest, session)
                            val closeResponseCode = closeResult.isoMessage.getFieldValue(39)
                            recordResponse(closeProcInfo, closeResult)
                            val closeResponseMessage = HostResponseMessageResolver.resolveOrFallback(closeResponseCode)

                            if (closeResponseCode == "00") {
                                val snapshot = buildSettlementSnapshot(target, acquirer, closeResponseCode)
                                val clearError = clearLocalBatch(target, acquirer, onTargetError)
                                if (clearError != null) {
                                    results += SettlementResult.Failure(
                                        acquirerId = acquirer.AcqID,
                                        acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                                        reason = clearError,
                                    )
                                    return@forEach
                                }
                                TransactionReportBridge.reportSettlement(
                                    context = UICApplication.instance.applicationContext,
                                    target = target,
                                    acquirer = acquirer,
                                    batchNumber = currentBatchNumber(acquirer),
                                    responseCode = closeResponseCode,
                                )
                                snapshot?.let { storeSettlementSnapshot(it) }
                                advanceBatchNumber(acquirer)
                                clearPendingState(acquirer.AcqID)
                                onTargetResult(closeResponseMessage, true)
                                results += SettlementResult.Success(
                                    acquirerId = acquirer.AcqID,
                                    acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                                    transactionCount = target.transactions.size,
                                    totalAmount = closeProcInfo.TransLog.TotalAmt ?: "0.00",
                                    currencySymbol = target.option.currencySymbol,
                                    responseCode = closeResponseCode ?: "00",
                                    snapshot = snapshot,
                                )
                            } else {
                                Log.w(
                                    TAG,
                                    "Settlement close declined for acquirer=${acquirer.AcqID} response=$closeResponseCode"
                                )
                                onTargetResult(closeResponseMessage, false)
                                results += SettlementResult.Failure(
                                    acquirerId = acquirer.AcqID,
                                    acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                                    reason = "Host response ${closeResponseCode ?: "UNKNOWN"}",
                                )
                            }
                        }
                        else -> {
                            Log.w(TAG, "Settlement declined for acquirer=${acquirer.AcqID} response=$responseCode")
                            onTargetResult(responseMessage, false)
                            results += SettlementResult.Failure(
                                acquirerId = acquirer.AcqID,
                                acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                                reason = "Host response ${responseCode ?: "UNKNOWN"}",
                            )
                        }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Host transaction failed for acquirer=${acquirer.AcqID}", error)
                    onTargetError(formatHostErrorMessage(error))
                    results += SettlementResult.Failure(
                        acquirerId = acquirer.AcqID,
                        acquirerName = acquirer.AcquirerName.ifBlank { acquirer.AcqID },
                        reason = error.localizedMessage ?: error::class.java.simpleName,
                    )
                } finally {
                    session.close()
                }
            }
            results
        }
    }

    private fun buildProcInfo(
        target: SettlementTarget,
        acquirer: TMS_Acquirer,
        stan: String,
        invoiceId: String,
    ): ProcInfo {
        val transLog = ProcInfo().TransLog
        transLog.TxnType = TransactionType.SETTLEMENT.toTransactionString()
        transLog.AccType = "Credit"
        transLog.AcquirerId = acquirer.AcqID
        transLog.AuthNtwkName = acquirer.AcquirerName
        transLog.CurrCode = SysParam.getInstance().CurrCode
        transLog.TxnId = stan
        transLog.InvoiceId = invoiceId
        transLog.TxnAmt = ZERO_AMOUNT
        transLog.BaseAmt = ZERO_AMOUNT
        transLog.Tax1Amt = ZERO_AMOUNT
        transLog.Tax2Amt = ZERO_AMOUNT
        transLog.TipAmt = ZERO_AMOUNT
        transLog.TxnCnt = target.transactions.size.toString()
        transLog.TotalAmt = computeTotalAmount(target.transactions)
        transLog.SettlementTotals = target.totals
        return ProcInfo(TransLog = transLog)
    }

    private fun computeTotalAmount(transactions: List<Transaction>): String {
        if (transactions.isEmpty()) return ZERO_AMOUNT
        val total = transactions.fold(BigDecimal.ZERO) { acc, transaction ->
            val amount = transaction.totalAmount.replace(",", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
            acc + amount
        }
        return total.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    private fun recordResponse(procInfo: ProcInfo, result: HostTransactionResult) {
        val responseCode = result.isoMessage.getFieldValue(39)
        procInfo.TransLog.RspCode = responseCode
        procInfo.TransLog.TxnResult = responseCode ?: ""
        procInfo.TransLog.RspDT = result.timestamp.toString()
        procInfo.TransLog.TxnResultMsg = responseCode ?: ""
        procInfo.TransLog.RspText = procInfo.TransLog.TxnResultMsg
    }

    private suspend fun clearLocalBatch(
        target: SettlementTarget,
        acquirer: TMS_Acquirer,
        onTargetError: (String) -> Unit,
    ): String? {
        return try {
            if (target.transactions.isNotEmpty()) {
                transactionRepository.delete(*target.transactions.toTypedArray())
            }
            null
        } catch (error: Exception) {
            Log.e(TAG, "Failed to clear settled transactions for acquirer=${acquirer.AcqID}", error)
            val reason = error.localizedMessage ?: "Unable to clear local batch"
            onTargetError(reason)
            reason
        }
    }

    private fun advanceBatchNumber(acquirer: TMS_Acquirer) {
        acquirer.AcqID?.takeIf { it.isNotBlank() }?.let { identifier ->
            BatchNumberProvider.advanceBatchNumber(identifier)
        }
    }

    private suspend fun markSettlementPending(target: SettlementTarget, acquirer: TMS_Acquirer) {
        if (acquirer.AcqID.isBlank()) return
        val name = acquirer.AcquirerName.ifBlank { target.option.name.ifBlank { acquirer.AcqID } }
        runCatching {
            settlementStateRepository.markPending(
                acquirerId = acquirer.AcqID,
                acquirerName = name,
                currencySymbol = target.option.currencySymbol,
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to flag settlement pending for acquirer=${acquirer.AcqID}", error)
        }
    }

    private fun buildSettlementSnapshot(
        target: SettlementTarget,
        acquirer: TMS_Acquirer,
        responseCode: String?,
    ): SettlementSnapshot? {
        val acquirerId = acquirer.AcqID
        if (acquirerId.isBlank()) return null
        val timestamp = LocalDateTime.now()
        val initialBatch = acquirer.InitBatchNo
            .takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val batchNumber = currentBatchNumber(acquirer, initialBatch)

        return SettlementSnapshot(
            acquirerId = acquirerId,
            acquirerName = acquirer.AcquirerName.ifBlank { target.option.name.ifBlank { acquirerId } },
            merchantId = target.option.merchantId,
            terminalId = target.option.terminalId,
            currencySymbol = target.option.currencySymbol,
            totals = target.totals,
            transactions = target.transactions.map { it.copy() },
            completedAt = timestamp.format(dateTimeFormatter),
            batchNumber = batchNumber,
            responseCode = responseCode ?: "",
        )
    }

    private suspend fun storeSettlementSnapshot(snapshot: SettlementSnapshot) {
        runCatching { settlementStateRepository.storeSnapshot(snapshot) }
            .onFailure { error ->
                Log.e(TAG, "Unable to store settlement snapshot for acquirer=${snapshot.acquirerId}", error)
            }
    }

    private fun currentBatchNumber(acquirer: TMS_Acquirer, initialBatch: Int? = null): String? {
        val acquirerId = acquirer.AcqID.takeIf { it.isNotBlank() } ?: return null
        val fallback = initialBatch
            ?: acquirer.InitBatchNo
                .takeIf { it in 1..Int.MAX_VALUE.toLong() }
                ?.toInt()
        return runCatching {
            BatchNumberProvider.currentBatchNumber(acquirerId, fallback)
        }.onFailure { error ->
            Log.e(TAG, "Unable to resolve batch number for acquirer=$acquirerId", error)
        }.getOrNull()
    }

    private suspend fun clearPendingState(acquirerId: String) {
        if (acquirerId.isBlank()) return
        runCatching { settlementStateRepository.clearPending(acquirerId) }
            .onFailure { error ->
                Log.e(TAG, "Unable to clear settlement pending flag for acquirer=$acquirerId", error)
            }
    }

    private suspend fun performBatchUpload(
        transactions: List<Transaction>,
        acquirer: TMS_Acquirer,
        ipProfile: TMS_HostConnectionInfo,
        terminal: TMS_Terminal,
        hostSettings: HostSettings,
        isoFactory: IsoMessageFactory,
        sslSocketFactory: javax.net.ssl.SSLSocketFactory?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onHostEvent: (HostProcessingEvent) -> Unit,
        session: HostTransactionSession? = null,
    ): BatchUploadResult {
        if (transactions.isEmpty()) {
            Log.d(TAG, "performBatchUpload called with no transactions")
            return BatchUploadResult(success = true)
        }

        transactions.forEachIndexed { index, transaction ->
            val uploadStan = when {
                transaction.stan.isNotBlank() -> transaction.stan
                transaction.transactionId.isNotBlank() -> transaction.transactionId
                else -> StanProvider.nextStan()
            }

            val procInfo = buildUploadProcInfo(transaction, acquirer)
            procInfo.TransLog.TxnId = uploadStan

            val timestamp = parseTransactionTimestamp(transaction.localDateTime)
            val message = try {
                hostMessageBuilder.build(
                    acquirer = acquirer,
                    procInfo = procInfo,
                    isoFactory = isoFactory,
                    stanSupplier = { uploadStan },
                    timestampSupplier = { timestamp ?: LocalDateTime.now() },
                )
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Unable to build batch upload message for acquirer=${acquirer.AcqID} transactionId=${transaction.transactionId}",
                    error
                )
                return BatchUploadResult(
                    success = false,
                    message = error.localizedMessage ?: "Unable to build batch upload message",
                    fatal = true,
                )
            }

            message.setMessageType(BATCH_UPLOAD_MESSAGE_TYPE)
            val baseProcessingCode = processingCodeFor(transaction)
            val flowDigit = if (index == transactions.lastIndex) FLOW_CONTROL_LAST else FLOW_CONTROL_MORE
            val processingCode = applyFlowControlDigit(baseProcessingCode, flowDigit)
            message.setFieldValue(3, processingCode)

            val uploadRequest = HostTransactionRequest(
                acquirer = acquirer,
                ipProfile = ipProfile,
                terminal = terminal,
                message = message,
                clearPan = procInfo.TransLog.PAN,
                track1 = procInfo.TransLog.Track1,
                track2 = procInfo.TransLog.Track2,
                track3 = procInfo.TransLog.Track3,
                lengthConfig = hostSettings.length,
                primaryEndpoint = hostSettings.primary,
                secondaryEndpoint = hostSettings.secondary,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                attempts = hostSettings.attempts,
                primaryRetries = hostSettings.primaryRetries,
                secondaryRetries = hostSettings.secondaryRetries,
                useTls = hostSettings.isTls,
                sslSocketFactory = sslSocketFactory,
                isoFactory = isoFactory,
                onStatusChanged = onHostEvent,
            )

            Log.d(
                TAG,
                "Transmitting batch upload ${index + 1}/${transactions.size} for acquirer=${acquirer.AcqID} " +
                    "stan=$uploadStan processingCode=$processingCode"
            )

            val result = try {
                hostClient.execute(uploadRequest, session)
            } catch (error: Exception) {
                Log.e(TAG, "Batch upload failed for acquirer=${acquirer.AcqID}", error)
                return BatchUploadResult(
                    success = false,
                    message = error.localizedMessage ?: "Batch upload failed",
                    fatal = true,
                )
            }

            val responseCode = result.isoMessage.getFieldValue(39)
            if (responseCode != "00") {
                Log.w(
                    TAG,
                    "Batch upload declined for acquirer=${acquirer.AcqID} response=$responseCode"
                )
                return BatchUploadResult(
                    success = false,
                    responseCode = responseCode,
                    message = HostResponseMessageResolver.resolveOrFallback(responseCode),
                    fatal = false,
                )
            }
        }

        return BatchUploadResult(success = true)
    }

    private fun buildUploadProcInfo(
        transaction: Transaction,
        acquirer: TMS_Acquirer,
    ): ProcInfo {
        val transLog = ProcInfo().TransLog
        transLog.TxnType = transaction.type.toTransactionString()
        transLog.MessageTypeOverride = BATCH_UPLOAD_MESSAGE_TYPE
        transLog.AccType = transaction.accType.ifBlank { "Credit" }
        transLog.AcquirerId = transaction.acquirerId.ifBlank { acquirer.AcqID }
        transLog.IssuerId = transaction.issuerId
        transLog.CardRangeId = transaction.cardRangeId
        transLog.CardRangeName = transaction.cardRangeName
        transLog.CurrCode = SysParam.getInstance().CurrCode
        transLog.TxnAmt = transaction.totalAmount.ifBlank { "0.00" }
        transLog.BaseAmt = transaction.baseAmount.ifBlank { transaction.totalAmount.ifBlank { "0.00" } }
        transLog.Tax1Amt = transaction.tax1Amount.ifBlank { "0.00" }
        transLog.Tax1DiscountAmt = transaction.tax1DiscountAmount.ifBlank { "0.00" }
        transLog.OriginalTax1Amt = transaction.tax1Amount.ifBlank { "0.00" }
        transLog.Tax2Amt = transaction.tax2Amount.ifBlank { "0.00" }
        transLog.TipAmt = transaction.tipAmount.ifBlank { "0.00" }
        transLog.PaymentPlan = transaction.paymentPlan
        transLog.ForceOnline = ""
        transLog.InvoiceId = transaction.invoiceId
        transLog.AuthNtwkName = transaction.authNtwkName.ifBlank { acquirer.AcquirerName }
        transLog.CardType = transaction.cardType
        transLog.CardhdrName = transaction.cardType
        transLog.FolioNumber = transaction.folioNumber
        transLog.OriginalTransactionId = transaction.originalTransactionId
        transLog.OriginalStan = sequenceOf(transaction.stan, transaction.transactionId)
            .map { candidate -> candidate.filter { it.isDigit() } }
            .firstOrNull { it.isNotEmpty() }
        transLog.OriginalMessageType = TransactionConfigRegistry
            .configFor(transaction.type.toTransactionString())
            ?.messageType
        transLog.TipProcessingInfo = transaction.tipProcessingInformation
        transLog.SignatureRequired = transaction.signatureRequired
        transLog.SignatureCaptured = transaction.signatureCaptured
        transLog.Token = transaction.token
        transLog.AuthCode = transaction.authCode
        transLog.AuthorizationId = transaction.authorizationId
        transLog.RefNbr = transaction.retrievalReferenceNumber
        transLog.ExternalRefNumber = transaction.externalReferenceNumber
        transLog.AlternateHostResponse = transaction.alternateHostResponse
        transLog.AdditionalHostPrintData = transaction.additionalHostPrintData
        transLog.PaymentPlanQueryResponse = transaction.paymentPlanQueryResponse
        val approvalMessage = HostResponseMessageResolver.resolveOrFallback("00")
        transLog.TxnResult = "00"
        transLog.RspCode = "00"
        transLog.TxnResultMsg = approvalMessage
        transLog.RspText = approvalMessage
        transLog.RspDT = transaction.localDateTime
        transLog.TotalAmt = transaction.totalAmount.ifBlank { "0.00" }

        val decryptedPan = runCatching {
            transaction.cardNumber.takeIf { it.isNotBlank() }?.let(EncryptionUtil::decryptData)
        }.onFailure { error ->
            Log.w(TAG, "Unable to decrypt PAN for transaction id=${transaction.id}", error)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        transLog.PAN = decryptedPan
        transLog.CardNbr = decryptedPan ?: ""
        transLog.MaskedCardNbr = transaction.masked_cardNumber
        transLog.HashedCardNbr = transaction.hashed_cardNumber

        transLog.CardDataSource = transaction.cardEntryMethod
        deriveInterfaceCode(transaction.cardEntryMethod)?.let { transLog.TxnInterface = it }

        applyExpiration(transaction.cardExpirationDate, transLog)

        transLog.AID = transaction.AID
        transLog.AppId = transaction.AID
        transLog.AppName = transaction.applicationName
        transLog.AppLabel = transaction.applicationLabel
        transLog.TVR = transaction.TVR
        transLog.TSI = transaction.TSI
        transLog.AC = transaction.AC
        transLog.ARC = transaction.ARC
        transLog.ATC = transaction.ATC
        transLog.IAD = transaction.IAD
        transLog.CryptoInfo = transaction.emvCryptoInformation
        transLog.Cryptogram = transaction.emvCryptogram.ifBlank { transaction.AC }
        transLog.CVMText = transaction.CVM.toPrinterString()
        transLog.CVMResult = transaction.CVM.name

        return ProcInfo(TransLog = transLog)
    }

    private fun processingCodeFor(transaction: Transaction): String {
        val config = TransactionConfigRegistry.configFor(transaction.type.toTransactionString())
        val code = config?.processingCode ?: "000000"
        return code.padEnd(6, '0').take(6)
    }

    private fun applyFlowControlDigit(processingCode: String, flowDigit: Char): String {
        if (processingCode.isEmpty()) return "00000$flowDigit"
        val normalized = processingCode.padEnd(6, '0').take(6)
        val builder = StringBuilder(normalized)
        builder[5] = flowDigit
        return builder.toString()
    }

    private fun parseTransactionTimestamp(timestamp: String): LocalDateTime? {
        if (timestamp.isBlank()) return null
        return try {
            LocalDateTime.parse(timestamp, dateTimeFormatter)
        } catch (error: DateTimeParseException) {
            Log.w(TAG, "Unable to parse transaction timestamp '$timestamp'", error)
            null
        }
    }

    private fun deriveInterfaceCode(cardEntryMethod: String): String? {
        if (cardEntryMethod.isBlank()) return null
        val source = cardEntryMethod.trim().uppercase(Locale.US)
        return when {
            source == "04" || source.contains("CONTACTLESS") -> "04"
            source == "03" || source.contains("EMV") || source.contains("CHIP") -> "03"
            source == "02" || source.contains("SWIPE") || source.contains("MAG") -> "02"
            source == "01" || source.contains("MANUAL") -> "01"
            else -> null
        }
    }

    private fun applyExpiration(expiration: String, transLog: TransLog) {
        if (expiration.isBlank()) return
        val parts = expiration.split('-')
        if (parts.size < 2) return
        val yearPart = parts[0].takeLast(4)
        val monthPart = parts[1].take(2)
        if (monthPart.length != 2) return
        transLog.ExpireMonth = monthPart
        transLog.ExpireYear = if (yearPart.length == 2) {
            "20$yearPart"
        } else {
            yearPart
        }
    }

    private data class BatchUploadResult(
        val success: Boolean,
        val responseCode: String? = null,
        val message: String? = null,
        val fatal: Boolean = false,
    )

    private fun selectTerminal(database: TMSDATA, acquirer: TMS_Acquirer): TMS_Terminal? {
        return database.Terminal.firstOrNull { it.TermID == acquirer.AcqTermID }
            ?: database.Terminal.firstOrNull()
    }

    private fun buildHostSettings(
        acquirer: TMS_Acquirer,
        ipProfile: TMS_HostConnectionInfo,
        terminal: TMS_Terminal,
    ): HostSettings {
        val length = LengthPrefixRegistry.resolve(acquirer.HostProtocol, terminal)
        val connectTimeout = ipProfile.IPConnTime.safeInt(DEFAULT_CONNECT_TIMEOUT_SEC)
        val readTimeout = ipProfile.TranTimeOut.safeInt(DEFAULT_READ_TIMEOUT_SEC)
        val attempts = ipProfile.AttemptIPT.safeInt(1)
        val primaryRetries = ipProfile.IPConnRetriesP.safeInt(1)
        val secondaryRetries = ipProfile.IPConnRetriesS.safeInt(1)
        return HostSettings(
            isTls = ipProfile.SSL,
            primary = parseHostAddress(ipProfile.PrimIpAddr),
            secondary = parseHostAddress(ipProfile.SecIpAddr),
            connectTimeoutSeconds = connectTimeout,
            readTimeoutSeconds = readTimeout,
            attempts = attempts,
            primaryRetries = primaryRetries,
            secondaryRetries = secondaryRetries,
            length = length,
        )
    }

    private fun secondsToMillis(seconds: Int, fallback: Int): Int {
        val value = if (seconds <= 0) fallback else seconds
        val capped = value.coerceAtMost(Int.MAX_VALUE / 1000)
        return capped * 1000
    }

    private fun Long.safeInt(default: Int): Int {
        if (this <= 0) return default
        return coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        private const val ZERO_AMOUNT = "0.00"
    }
}

sealed class SettlementResult {
    abstract val acquirerId: String
    abstract val acquirerName: String

    data class Success(
        override val acquirerId: String,
        override val acquirerName: String,
        val transactionCount: Int,
        val totalAmount: String,
        val currencySymbol: String,
        val responseCode: String,
        val snapshot: SettlementSnapshot?,
    ) : SettlementResult()

    data class Failure(
        override val acquirerId: String,
        override val acquirerName: String,
        val reason: String,
    ) : SettlementResult()
}
