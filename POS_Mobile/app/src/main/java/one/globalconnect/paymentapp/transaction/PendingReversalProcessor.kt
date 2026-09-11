package one.globalconnect.paymentapp.transaction

import android.content.Context
import android.util.Log
import com.uic.pos.iso8583.IsoMessage
import com.uic.pos.iso8583.IsoMessageFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.records.dateTimeFormatter
import one.globalconnect.paymentapp.uicpos.pos.host.AcquirerSslCache
import one.globalconnect.paymentapp.uicpos.pos.host.HostSettings
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionClient
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionRequest
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionResult
import one.globalconnect.paymentapp.uicpos.pos.host.IsoMessageFactoryProvider
import one.globalconnect.paymentapp.uicpos.pos.host.LengthPrefixRegistry
import one.globalconnect.paymentapp.uicpos.pos.host.parseHostAddress
import one.globalconnect.paymentapp.utils.FormatterUtils
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import one.globalconnect.tms.paymentapp.TMS_Terminal
import java.time.LocalDateTime

/** Summary of one explicit or automatic pending-reversal transmission pass. */
data class PendingReversalProcessingResult(
    val found: Int,
    val sent: Int,
    val remaining: Int,
    val failedAcquirerIds: Set<String> = emptySet(),
) {
    val allSent: Boolean get() = remaining == 0
}

/**
 * Sends persisted reversals in creation order and removes only host-confirmed rows.
 *
 * Response codes 00 (approved) and 21 (already reversed) both complete a queued reversal.
 * Processing stops for an acquirer after its first failure so later reversals cannot overtake it.
 */
class PendingReversalProcessor(
    private val transactionRepository: TransactionRepository,
    private val hostExecutor: suspend (HostTransactionRequest) -> HostTransactionResult = { request ->
        HostTransactionClient().execute(request)
    },
    private val isoFactoryProvider: () -> IsoMessageFactory = IsoMessageFactoryProvider::factoryFor,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {

    /** Checks the complete queue and attempts every configured acquirer's pending reversals. */
    suspend fun processAll(
        tmsDatabase: TMSDATA,
        onApproved: suspend (PendingReversal, TMS_Acquirer) -> Unit = { _, _ -> },
    ): PendingReversalProcessingResult {
        val pending = transactionRepository.getAllPendingReversals()
        if (pending.isEmpty()) return PendingReversalProcessingResult(0, 0, 0)

        val terminal = tmsDatabase.Terminal.firstOrNull()
            ?: return PendingReversalProcessingResult(
                found = pending.size,
                sent = 0,
                remaining = pending.size,
                failedAcquirerIds = pending.map(PendingReversal::acquirerId).toSet(),
            )
        val acquirers = tmsDatabase.Acquirer.associateBy(TMS_Acquirer::AcqID)
        val ipProfiles = tmsDatabase.IPTab.associateBy(TMS_HostConnectionInfo::IPTabID)
        var sent = 0
        val failedAcquirerIds = mutableSetOf<String>()

        pending.groupBy(PendingReversal::acquirerId).forEach { (acquirerId, reversals) ->
            val acquirer = acquirers[acquirerId]
            val ipProfile = acquirer?.let { ipProfiles[it.IPTabTran] }
            if (acquirer == null || ipProfile == null) {
                failedAcquirerIds += acquirerId
                return@forEach
            }
            val hostSettings = buildHostSettings(acquirer, ipProfile, terminal)
            if (hostSettings.primary == null && hostSettings.secondary == null) {
                failedAcquirerIds += acquirerId
                return@forEach
            }

            val result = processPendingList(
                pending = reversals,
                acquirer = acquirer,
                ipProfile = ipProfile,
                terminal = terminal,
                hostSettings = hostSettings,
                isoFactory = isoFactoryProvider(),
                onApproved = onApproved,
            )
            sent += result.sent
            if (!result.allSent) failedAcquirerIds += acquirerId
        }

        return PendingReversalProcessingResult(
            found = pending.size,
            sent = sent,
            remaining = pending.size - sent,
            failedAcquirerIds = failedAcquirerIds,
        )
    }

    /** Sends pending reversals for the acquirer used by an in-progress transaction. */
    suspend fun processForAcquirer(
        acquirer: TMS_Acquirer,
        ipProfile: TMS_HostConnectionInfo,
        terminal: TMS_Terminal,
        hostSettings: HostSettings,
        isoFactory: IsoMessageFactory,
        onApproved: suspend (PendingReversal, TMS_Acquirer) -> Unit = { _, _ -> },
    ): PendingReversalProcessingResult {
        return processPendingList(
            pending = transactionRepository.getPendingReversals(acquirer.AcqID),
            acquirer = acquirer,
            ipProfile = ipProfile,
            terminal = terminal,
            hostSettings = hostSettings,
            isoFactory = isoFactory,
            onApproved = onApproved,
        )
    }

    private suspend fun processPendingList(
        pending: List<PendingReversal>,
        acquirer: TMS_Acquirer,
        ipProfile: TMS_HostConnectionInfo,
        terminal: TMS_Terminal,
        hostSettings: HostSettings,
        isoFactory: IsoMessageFactory,
        onApproved: suspend (PendingReversal, TMS_Acquirer) -> Unit,
    ): PendingReversalProcessingResult {
        if (pending.isEmpty()) return PendingReversalProcessingResult(0, 0, 0)

        val connectTimeoutMs = secondsToMillis(hostSettings.connectTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SEC)
        val readTimeoutMs = secondsToMillis(hostSettings.readTimeoutSeconds, DEFAULT_READ_TIMEOUT_SEC)
        var sent = 0

        for (reversal in pending) {
            val request = HostTransactionRequest(
                acquirer = acquirer,
                ipProfile = ipProfile,
                terminal = terminal,
                message = buildReversalIsoMessage(reversal, isoFactory),
                lengthConfig = hostSettings.length,
                primaryEndpoint = hostSettings.primary,
                secondaryEndpoint = hostSettings.secondary,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                attempts = hostSettings.attempts,
                primaryRetries = hostSettings.primaryRetries,
                secondaryRetries = hostSettings.secondaryRetries,
                useTls = hostSettings.isTls,
                sslSocketFactory = if (hostSettings.isTls) {
                    AcquirerSslCache.get(ipProfile.IPTabID.toString())
                } else {
                    null
                },
                isoFactory = isoFactory,
            )
            val attempted = reversal.copy(
                attempts = reversal.attempts + 1,
                lastAttemptAt = now().format(dateTimeFormatter),
            )

            try {
                val responseCode = hostExecutor(request).isoMessage.getFieldValue(39)
                val updated = attempted.copy(lastResponseCode = responseCode)
                if (responseCode != "00" && responseCode != "21") {
                    transactionRepository.updateReversal(updated)
                    break
                }

                transactionRepository.deleteReversal(reversal)
                sent += 1
                if (terminal.PrintReversalReceipt) {
                    runCatching { onApproved(updated, acquirer) }
                        .onFailure { error -> Log.e(TAG, "Unable to print reversal receipt", error) }
                }
            } catch (error: Throwable) {
                transactionRepository.updateReversal(attempted)
                if (error is CancellationException) throw error
                Log.e(TAG, "Reversal transmission failed for acquirer=${acquirer.AcqID}", error)
                break
            }
        }

        return PendingReversalProcessingResult(
            found = pending.size,
            sent = sent,
            remaining = pending.size - sent,
            failedAcquirerIds = if (sent == pending.size) emptySet() else setOf(acquirer.AcqID),
        )
    }

    private fun buildHostSettings(
        acquirer: TMS_Acquirer,
        ipProfile: TMS_HostConnectionInfo,
        terminal: TMS_Terminal,
    ): HostSettings = HostSettings(
        isTls = ipProfile.SSL,
        primary = parseHostAddress(ipProfile.PrimIpAddr),
        secondary = parseHostAddress(ipProfile.SecIpAddr),
        connectTimeoutSeconds = ipProfile.IPConnTime.safePositiveInt(DEFAULT_CONNECT_TIMEOUT_SEC),
        readTimeoutSeconds = ipProfile.TranTimeOut.safePositiveInt(DEFAULT_READ_TIMEOUT_SEC),
        attempts = ipProfile.AttemptIPT.safePositiveInt(1),
        primaryRetries = ipProfile.IPConnRetriesP.safePositiveInt(1),
        secondaryRetries = ipProfile.IPConnRetriesS.safePositiveInt(1),
        length = LengthPrefixRegistry.resolve(acquirer.HostProtocol, terminal),
    )

    private fun buildReversalIsoMessage(
        reversal: PendingReversal,
        isoFactory: IsoMessageFactory,
    ): IsoMessage {
        val message = isoFactory.newMessage()
        reversal.header?.let(message::setHeader)
        message.setMessageType("0400")
        message.setFieldValue(3, reversal.processingCode)
        reversal.fieldValues.forEach { (field, value) ->
            when (field) {
                0, 1, 3, 35 -> Unit
                else -> message.setFieldValue(field, value)
            }
        }
        reversal.fieldValues[2]?.let { message.setFieldValue(2, it) }
        reversal.fieldValues[14]?.let { message.setFieldValue(14, it) }
        return message
    }

    private fun secondsToMillis(seconds: Int, fallback: Int): Int {
        val value = if (seconds <= 0) fallback else seconds
        return value.coerceAtMost(Int.MAX_VALUE / 1_000) * 1_000
    }

    private fun Long.safePositiveInt(default: Int): Int {
        if (this <= 0) return default
        return coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        private const val TAG = "PendingReversalProcessor"
        private const val DEFAULT_CONNECT_TIMEOUT_SEC = 10
        private const val DEFAULT_READ_TIMEOUT_SEC = 60
    }
}

/** Builds and prints the optional receipt after a queued reversal is host-confirmed. */
class PendingReversalReceiptPrinter(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val tmsDatabase: TMSDATA,
    private val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter,
) {
    suspend fun print(reversal: PendingReversal, acquirer: TMS_Acquirer) {
        withContext(Dispatchers.IO) {
            val profile = profileRepository.get()
            val timestamp = runCatching { LocalDateTime.parse(reversal.createdAt, dateTimeFormatter) }
                .getOrElse { LocalDateTime.now() }
            val transactionLabel = reversal.transactionType
                .transactionStringToTransactionType()
                .toStringForUsers()
            val amountText = FormatterUtils.formatAmount(acquirer.Currency, reversal.transactionAmount)
            val maskedPan = reversal.maskedPan.ifBlank {
                obfuscatePAN(reversal.fieldValues[2]) ?: ""
            }
            val receiptData = ReversalReceiptData(
                timestamp = timestamp,
                maskedPan = maskedPan,
                cardBrand = reversal.cardBrand,
                rrn = reversal.fieldValues[37]?.takeIf(String::isNotBlank) ?: reversal.stan,
                invoiceNumber = reversal.invoiceNumber,
                transactionTypeLabel = transactionLabel,
                totalAmountText = amountText,
            )
            paymentPrinter.printReversalReceipt(
                context = context.applicationContext,
                profile = profile,
                tmsDatabase = tmsDatabase,
                data = receiptData,
            )
        }
    }
}
