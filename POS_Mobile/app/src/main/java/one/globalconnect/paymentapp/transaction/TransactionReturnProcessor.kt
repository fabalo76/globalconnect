package one.globalconnect.paymentapp.transaction

import android.content.Context
import android.util.Log
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.records.dateTimeFormatter
import one.globalconnect.paymentapp.security.EncryptionUtil
import one.globalconnect.paymentapp.uicpos.pos.host.AcquirerSslCache
import one.globalconnect.paymentapp.uicpos.pos.host.HostMessageBuilder
import one.globalconnect.paymentapp.uicpos.pos.host.HostProcessingEvent
import one.globalconnect.paymentapp.uicpos.pos.host.HostResponseMessageResolver
import one.globalconnect.paymentapp.uicpos.pos.host.HostSettingsResolver
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionClient
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionRequest
import one.globalconnect.paymentapp.uicpos.pos.host.IsoMessageFactoryProvider
import one.globalconnect.paymentapp.uicpos.pos.host.InvoiceNumberProvider
import one.globalconnect.paymentapp.uicpos.pos.host.StanProvider
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.paymentapp.uicpos.pos.host.formatHostErrorMessage
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.TransLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.Locale

private const val TAG = "ReturnProcessor"
private const val DEFAULT_CONNECT_TIMEOUT_SEC = 10
private const val DEFAULT_READ_TIMEOUT_SEC = 60

internal data class ReturnMessageSpec(
    val action: ReturnAction,
    val messageType: String,
    val processingCode: String,
)

internal fun resolveReturnMessageSpec(
    originalTransactionType: TransactionType,
    requestedAction: ReturnAction,
): ReturnMessageSpec {
    val originalConfig = TransactionConfigRegistry.configFor(originalTransactionType.toTransactionString())
        ?: throw IllegalArgumentException("Unsupported original transaction type")
    val action = if (requestedAction == ReturnAction.REVERSAL || originalConfig.messageType == "0100") {
        ReturnAction.REVERSAL
    } else {
        ReturnAction.VOID
    }
    return ReturnMessageSpec(
        action = action,
        messageType = if (action == ReturnAction.REVERSAL) "0400" else originalConfig.messageType,
        processingCode = if (action == ReturnAction.REVERSAL) {
            originalConfig.processingCode
        } else {
            incrementVoidProcessingCode(originalConfig.processingCode)
        },
    )
}

internal fun incrementVoidProcessingCode(originalProcessingCode: String): String {
    require(originalProcessingCode.matches(Regex("[0-9]{6}"))) { "Invalid original processing code" }
    val voidPrefix = originalProcessingCode.take(2).toInt() + 2
    require(voidPrefix <= 99) { "Void processing code prefix exceeds two digits" }
    return String.format(Locale.US, "%02d%s", voidPrefix, originalProcessingCode.drop(2))
}

class TransactionReturnProcessor(
    private val context: Context = GlobalConnectPaymentApplication.instance,
    private val tmsDatabase: TMSDATA = GlobalConnectPaymentApplication.instance.container.tmsDatabase,
    private val hostMessageBuilder: HostMessageBuilder = HostMessageBuilder(),
    private val hostClient: HostTransactionClient = HostTransactionClient(),
) {

    suspend fun execute(
        transaction: Transaction,
        returnAction: ReturnAction,
        onStatusUpdate: (ReturnUiState) -> Unit = {},
    ): ReturnResult = withContext(Dispatchers.IO) {
        try {
            executeIsoReturn(transaction, returnAction, onStatusUpdate)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "ISO8583 ${returnAction.name.lowercase()} failed for transaction id=${transaction.id}",
                error,
            )
            ReturnResult(false, formatHostErrorMessage(error), transaction, "TO")
        }
    }

    private suspend fun executeIsoReturn(
        transaction: Transaction,
        returnAction: ReturnAction,
        onStatusUpdate: (ReturnUiState) -> Unit,
    ): ReturnResult {
        val acquirerId = transaction.acquirerId.trim()
        val acquirer = tmsDatabase.Acquirer.firstOrNull { it.AcqID == acquirerId }
            ?: return ReturnResult(false, context.getString(R.string.sale_error_no_acquirer), transaction)
        val ipProfile = tmsDatabase.IPTab.firstOrNull { it.IPTabID == acquirer.IPTabTran }
            ?: return ReturnResult(false, context.getString(R.string.err_comm_error), transaction)
        val terminal = tmsDatabase.Terminal.firstOrNull()
            ?: return ReturnResult(false, context.getString(R.string.err_comm_error), transaction)
        val hostSettings = HostSettingsResolver.resolve(acquirerId)
            ?: return ReturnResult(false, context.getString(R.string.err_comm_error), transaction)

        if (hostSettings.primary == null && hostSettings.secondary == null) {
            return ReturnResult(false, context.getString(R.string.err_comm_error), transaction)
        }

        val messageSpec = resolveReturnMessageSpec(transaction.type, returnAction)
        val clearPan = decryptPan(transaction.cardNumber)
        val procInfo = buildProcInfo(transaction, messageSpec, clearPan)
        val isoFactory = IsoMessageFactoryProvider.factoryFor()
        val stan = resolveStan(transaction, messageSpec.action)
        val requestTimestamp = resolveTimestamp(transaction, messageSpec.action)
        val message = hostMessageBuilder.build(
            acquirer = acquirer,
            terminal = terminal,
            procInfo = procInfo,
            isoFactory = isoFactory,
            stanSupplier = { stan },
            timestampSupplier = { requestTimestamp },
        )
        val progress = HostProcessingStateMachine(ProcessingStatusStrings(
            context.getString(R.string.processing_status_connecting),
            context.getString(R.string.processing_status_sending),
            context.getString(R.string.processing_status_waiting),
            context.getString(R.string.processing_status_processing_response),
            context.getString(R.string.processing_status_result),
            context.getString(R.string.processing_status_result_pending),
        ))
        val request = HostTransactionRequest(
            acquirer = acquirer,
            ipProfile = ipProfile,
            terminal = terminal,
            message = message,
            clearPan = clearPan,
            lengthConfig = hostSettings.length,
            primaryEndpoint = hostSettings.primary,
            secondaryEndpoint = hostSettings.secondary,
            connectTimeoutMs = secondsToMillis(hostSettings.connectTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SEC),
            readTimeoutMs = secondsToMillis(hostSettings.readTimeoutSeconds, DEFAULT_READ_TIMEOUT_SEC),
            attempts = hostSettings.attempts,
            primaryRetries = hostSettings.primaryRetries,
            secondaryRetries = hostSettings.secondaryRetries,
            useTls = hostSettings.isTls,
            sslSocketFactory = if (hostSettings.isTls) AcquirerSslCache.get(ipProfile.IPTabID) else null,
            isoFactory = isoFactory,
            onStatusChanged = { event ->
                onStatusUpdate(ReturnUiState.Loading(statusMessage(event), progress.onEvent(event)))
            },
        )

        val result = hostClient.execute(request)
        val responseCode = result.isoMessage.getFieldValue(39)
        val approved = responseCode == "00" ||
            (messageSpec.action == ReturnAction.REVERSAL && responseCode == "21")
        if (!approved) {
            return ReturnResult(
                false,
                HostResponseMessageResolver.resolveOrFallback(responseCode),
                transaction,
                responseCode?.takeIf { it.length == 2 } ?: "96",
            )
        }

        val updatedTransaction = transaction.copy(
            checkStatus = CheckStatus.Closed,
            returnStatus = ReturnStatus.Voided,
        )
        val successMessage = when (messageSpec.action) {
            ReturnAction.VOID -> context.getString(R.string.successful_void)
            ReturnAction.REVERSAL -> context.getString(R.string.successful_reversal)
        }
        return ReturnResult(true, successMessage, updatedTransaction, "00")
    }

    private fun buildProcInfo(
        transaction: Transaction,
        messageSpec: ReturnMessageSpec,
        clearPan: String?,
    ): ProcInfo {
        val (expiryYear, expiryMonth) = parseExpiry(transaction.cardExpirationDate)
        return ProcInfo(
            TransLog = TransLog(
                TxnType = transaction.type.toTransactionString(),
                AccType = transaction.accType,
                AcquirerId = transaction.acquirerId,
                IssuerId = transaction.issuerId,
                CardRangeId = transaction.cardRangeId,
                CardRangeName = transaction.cardRangeName,
                TxnAmt = transaction.totalAmount.ifBlank { transaction.subTotal },
                BaseAmt = transaction.baseAmount,
                Tax1Amt = transaction.tax1Amount,
                Tax1DiscountAmt = transaction.tax1DiscountAmount,
                Tax2Amt = transaction.tax2Amount,
                TipAmt = transaction.tipAmount,
                TxnId = transaction.transactionId,
                OriginalMessageType = originalMessageType(transaction.type),
                OriginalStan = transaction.stan,
                MessageTypeOverride = messageSpec.messageType,
                ProcessingCodeOverride = messageSpec.processingCode,
                InvoiceId = transaction.invoiceId.ifBlank { InvoiceNumberProvider.nextInvoiceNumber() },
                AuthNtwkName = transaction.authNtwkName,
                CardDataSource = transaction.cardEntryMethod,
                CardNbr = clearPan.orEmpty(),
                MaskedCardNbr = transaction.masked_cardNumber,
                HashedCardNbr = transaction.hashed_cardNumber,
                ExpireMonth = expiryMonth,
                ExpireYear = expiryYear,
                AuthCode = transaction.authCode,
                AuthorizationId = transaction.authorizationId,
                PAN = clearPan,
                RefNbr = transaction.retrievalReferenceNumber,
                ExternalRefNumber = transaction.externalReferenceNumber,
                AID = transaction.AID,
                TVR = transaction.TVR,
                TSI = transaction.TSI,
                AC = transaction.AC,
                ATC = transaction.ATC,
                ARC = transaction.ARC,
                IAD = transaction.IAD,
                CryptoInfo = transaction.emvCryptoInformation,
                Cryptogram = transaction.emvCryptogram,
                TxnInterface = transaction.cardEntryMethod,
                FolioNumber = transaction.folioNumber,
                OriginalTransactionId = transaction.originalTransactionId,
                CashbackAmt = transaction.cashbackAmount,
                PaymentPlan = transaction.paymentPlan,
                PaymentPlanQueryResponse = transaction.paymentPlanQueryResponse,
            )
        )
    }

    private fun decryptPan(encryptedPan: String): String? {
        if (encryptedPan.isBlank()) return null
        return runCatching { EncryptionUtil.decryptData(encryptedPan) }
            .onFailure { error -> Log.w(TAG, "Unable to decrypt stored PAN for return request", error) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun resolveStan(transaction: Transaction, returnAction: ReturnAction): String {
        val originalStan = transaction.stan.filter(Char::isDigit).takeLast(6)
        return if (returnAction == ReturnAction.REVERSAL && originalStan.isNotEmpty()) {
            originalStan.padStart(6, '0')
        } else {
            StanProvider.nextStan()
        }
    }

    private fun resolveTimestamp(transaction: Transaction, returnAction: ReturnAction): LocalDateTime {
        if (returnAction != ReturnAction.REVERSAL) return LocalDateTime.now()
        return runCatching { LocalDateTime.parse(transaction.localDateTime, dateTimeFormatter) }
            .getOrDefault(LocalDateTime.now())
    }

    private fun originalMessageType(type: TransactionType): String = when (type) {
        TransactionType.AUTHONLY, TransactionType.CHECKIN -> "0100"
        else -> "0200"
    }

    private fun parseExpiry(value: String): Pair<String, String> {
        val parts = value.split('-')
        return if (parts.size == 2) {
            parts[0] to parts[1]
        } else {
            "" to ""
        }
    }

    private fun statusMessage(event: HostProcessingEvent): String = when (event) {
        HostProcessingEvent.Connecting -> context.getString(R.string.processing_status_connecting)
        HostProcessingEvent.Sending -> context.getString(R.string.processing_status_sending)
        HostProcessingEvent.WaitingForResponse -> context.getString(R.string.processing_status_waiting)
        HostProcessingEvent.ProcessingResponse -> context.getString(R.string.processing_status_processing_response)
    }

    private fun secondsToMillis(seconds: Int, fallback: Int): Int {
        val value = if (seconds <= 0) fallback else seconds
        return value.coerceAtMost(Int.MAX_VALUE / 1000) * 1000
    }
}
