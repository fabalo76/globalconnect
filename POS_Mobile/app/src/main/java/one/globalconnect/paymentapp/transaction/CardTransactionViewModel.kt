package one.globalconnect.paymentapp.transaction

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_CardRanges
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Issuer
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.cardreader.CardReadResult
import one.globalconnect.paymentapp.dao.TransactionRepository
import one.globalconnect.paymentapp.navigation.AMOUNT_KEY
import one.globalconnect.paymentapp.navigation.CHECK_IN_ID_KEY
import one.globalconnect.paymentapp.navigation.FOLIO_KEY
import one.globalconnect.paymentapp.navigation.ORIGINAL_TRANSACTION_ID_KEY
import one.globalconnect.paymentapp.navigation.TAX1_KEY
import one.globalconnect.paymentapp.navigation.TAX2_KEY
import one.globalconnect.paymentapp.navigation.TIP_KEY
import one.globalconnect.paymentapp.navigation.TRANSACTION_TYPE_KEY
import one.globalconnect.paymentapp.transaction.TransactionTimeouts.SELECTION_SCREEN_TIMEOUT_MS
import one.globalconnect.paymentapp.uicpos.pos.host.HostProcessingEvent
import one.globalconnect.paymentapp.uicpos.pos.host.HostMessageBuilder
import one.globalconnect.paymentapp.uicpos.pos.host.HostSettings
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionClient
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionRequest
import one.globalconnect.paymentapp.uicpos.pos.host.HostTransactionResult
import one.globalconnect.paymentapp.uicpos.pos.host.HostResponseMessageResolver
import one.globalconnect.paymentapp.uicpos.pos.host.InvoiceNumberProvider
import one.globalconnect.paymentapp.uicpos.pos.host.IsoMessageFactoryProvider
import one.globalconnect.paymentapp.uicpos.pos.host.protocol.PrivateUseData63
import one.globalconnect.paymentapp.uicpos.pos.host.AcquirerSslCache
import one.globalconnect.paymentapp.uicpos.pos.host.LengthPrefixRegistry
import one.globalconnect.paymentapp.uicpos.pos.host.parseHostAddress
import one.globalconnect.paymentapp.uicpos.pos.host.StanProvider
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry
import one.globalconnect.paymentapp.uicpos.pos.host.TransactionConfigRegistry.TransactionAttribute
import one.globalconnect.paymentapp.uicpos.pos.host.formatHostErrorMessage
import one.globalconnect.paymentapp.uicpos.pos.model.ProcInfo
import one.globalconnect.paymentapp.uicpos.pos.model.SysParam
import one.globalconnect.paymentapp.uicpos.pos.model.toTransaction
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import one.globalconnect.paymentapp.printer.NexGoPaymentPrinter
import one.globalconnect.paymentapp.printer.PaymentPrinter
import one.globalconnect.paymentapp.records.dateTimeFormatter
import one.globalconnect.paymentapp.settlement.storage.SettlementStateRepository
import one.globalconnect.paymentapp.transactions.TransactionReportBridge
import one.globalconnect.paymentapp.utils.FormatterUtils
import com.uic.pos.iso8583.IsoMessage
import com.uic.pos.iso8583.IsoMessageFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.Locale

/**
 * ViewModel orchestrating the new sale transaction flow.
 */
class CardTransactionViewModel(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val tmsDatabase: TMSDATA,
    private val profileRepository: ProfileRepository,
    private val settlementStateRepository: SettlementStateRepository,
    private val hostMessageBuilder: HostMessageBuilder = HostMessageBuilder(),
    private val hostClient: HostTransactionClient = HostTransactionClient(),
    private val sysParam: SysParam = SysParam.getInstance(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(CardTransactionUiState())
    val uiState: StateFlow<CardTransactionUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CardTransactionEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<CardTransactionEvent> = _events.asSharedFlow()

    private val baseAmount: String = savedStateHandle[AMOUNT_KEY] ?: "0.00"
    private val tax1Amount: String = savedStateHandle[TAX1_KEY] ?: "0.00"
    private val tax2Amount: String = savedStateHandle[TAX2_KEY] ?: "0.00"
    private val tipAmount: String = savedStateHandle[TIP_KEY] ?: "0.00"
    private val folioNumber: String = savedStateHandle[FOLIO_KEY] ?: ""
    private val originalTransactionId: String = savedStateHandle[ORIGINAL_TRANSACTION_ID_KEY] ?: ""
    private val checkInRowId: Int? = (savedStateHandle[CHECK_IN_ID_KEY] as String?)?.toIntOrNull()
    private val terminal: TMS_Terminal? = tmsDatabase.Terminal.firstOrNull()
    private val tax1Discount = Tax1DiscountCalculator.calculate(
        tax1Amount = tax1Amount,
        discountPercentage = terminal?.TaxDiscount ?: 0.0,
    )

    val transactionType: TransactionType = (savedStateHandle[TRANSACTION_TYPE_KEY]
        ?: TransactionType.ERROR.toTransactionString()).transactionStringToTransactionType()

    private val totalAmount: String = listOf(baseAmount, tax1Discount.discountedTaxAmountText, tax2Amount, tipAmount)
        .map { it.toBigDecimalOrZero() }
        .reduce(BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString()

    private var selectionJob: Job? = null
    private var activeCardData: CardReadResult? = null
    private var currentOptions: List<CardTransactionAcquirerOption> = emptyList()
    private val pendingAcquirerIds = MutableStateFlow<Set<String>>(emptySet())
    private val supportedTransactionTypes = setOf(
        TransactionType.SALE,
        TransactionType.REFUND,
        TransactionType.PAYMENT,
        TransactionType.CASH,
        TransactionType.LOYALTY_SALE,
        TransactionType.QUOTA_SALE,
        TransactionType.EXTRAS_SALE,
        TransactionType.CHECKIN,
        TransactionType.CHECKOUT,
    )

    private val hostProcessingStrings = ProcessingStatusStrings(
        connecting = string(R.string.processing_status_connecting),
        sending = string(R.string.processing_status_sending),
        waitingForResponse = string(R.string.processing_status_waiting),
        processingResponse = string(R.string.processing_status_processing_response),
        result = string(R.string.processing_status_result),
        pendingResult = string(R.string.processing_status_result_pending),
    )
    private val hostProcessingStateMachine = HostProcessingStateMachine(hostProcessingStrings)

    private val paymentPrinter: PaymentPrinter = NexGoPaymentPrinter
    private var waitingForResponseTimeoutSeconds: Int = DEFAULT_READ_TIMEOUT_SEC
    private var waitingForResponseJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                settlementStateRepository.observeStates().collect { states ->
                    val pending = states
                        .asSequence()
                        .filter { it.pending }
                        .mapNotNull { state -> state.acquirerId.takeIf { id -> id.isNotBlank() } }
                        .toSet()
                    pendingAcquirerIds.value = pending
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    Log.d(TAG, "Settlement state observer cancelled")
                } else {
                    Log.e(TAG, "Unable to observe settlement state changes", error)
                }
            }
        }
    }

    fun start() {
        Log.d(TAG, "start invoked transactionType=$transactionType totalAmount=$totalAmount")
        if (!supportedTransactionTypes.contains(transactionType)) {
            _uiState.value = CardTransactionUiState(
                step = CardTransactionStep.Error(string(R.string.trans_error))
            )
            return
        }
        stopWaitingForResponseCountdown()
        val currencies = CurrencyTable.build(tmsDatabase.Acquirer)
        Log.d(TAG, "start currencies=${currencies.size}")
        val baseState = CardTransactionUiState(
            baseAmount = baseAmount,
            tax1Amount = tax1Amount,
            tax2Amount = tax2Amount,
            tipAmount = tipAmount,
            totalAmount = totalAmount,
        )
        if (currencies.size > 1) {
            _uiState.value = baseState.copy(
                step = CardTransactionStep.SelectingCurrency(currencies),
                statusMessage = string(R.string.currency_select_title),
            )
            startSelectionTimer()
        } else {
            val currency = currencies.firstOrNull()
            _uiState.value = baseState.copy(
                step = CardTransactionStep.AwaitingCard,
                statusMessage = string(R.string.sale_present_card),
                currencySymbol = currency?.symbol,
                emvCountryCode = currency?.let { CurrencyTable.formatEmvCode(it.countryCode) } ?: "0840",
                emvCurrencyCode = currency?.let { CurrencyTable.formatEmvCode(it.currencyCode) } ?: "0840",
            )
        }
    }

    fun onCurrencySelected(currencyCode: String) {
        Log.d(TAG, "onCurrencySelected currencyCode=$currencyCode")
        selectionJob?.cancel()
        val step = _uiState.value.step as? CardTransactionStep.SelectingCurrency ?: return
        val selected = step.currencies.find { it.currencyCode.toString() == currencyCode } ?: return
        _uiState.update {
            it.copy(
                step = CardTransactionStep.AwaitingCard,
                statusMessage = string(R.string.sale_present_card),
                currencySymbol = selected.symbol,
                emvCountryCode = CurrencyTable.formatEmvCode(selected.countryCode),
                emvCurrencyCode = CurrencyTable.formatEmvCode(selected.currencyCode),
            )
        }
    }

    fun onCardRead(result: CardReadResult) {
        Log.d(
            TAG,
            "onCardRead slot=${result.slotType} maskedPan=${result.maskedCardNumber} track2Length=${result.track2?.length}",
        )
        if (!supportedTransactionTypes.contains(transactionType)) return
        activeCardData = result
        val allOptions = resolveAcquirerOptions(result)
        Log.d(TAG, "onCardRead all options=${allOptions.joinToString(separator = ", ") { it.acquirer.AcquirerName }}")

        if (allOptions.isEmpty()) {
            showError(string(R.string.sale_error_no_acquirer))
            return
        }

        val selectedCurrency = _uiState.value.emvCurrencyCode
        val options = allOptions.filter { CurrencyTable.formatEmvCode(it.acquirer.CurrencyCode) == selectedCurrency }
        Log.d(TAG, "onCardRead currency-filtered options (currency=$selectedCurrency) count=${options.size}")

        if (options.isEmpty()) {
            showError(string(R.string.sale_error_no_acquirer))
            return
        }

        val pendingIds = pendingAcquirerIds.value
        val (availableOptions, blockedOptions) = options.partition { option ->
            !pendingIds.contains(option.acquirer.AcqID)
        }
        if (availableOptions.isEmpty()) {
            val blockedName = blockedOptions.firstOrNull()?.let { option ->
                option.acquirer.AcquirerName.ifBlank { option.acquirer.AcqID }
            } ?: string(R.string.sale_error_no_acquirer)
            showError(string(R.string.sale_error_settlement_pending, blockedName))
            return
        }
        if (blockedOptions.isNotEmpty()) {
            val blockedList = blockedOptions.joinToString { it.acquirer.AcqID }
            Log.i(TAG, "Skipping ${blockedOptions.size} acquirer(s) due to pending settlement: $blockedList")
        }

        currentOptions = availableOptions

        if (availableOptions.size == 1) {
            _uiState.update {
                it.copy(
                    step = CardTransactionStep.ProcessingHost,
                    statusMessage = string(R.string.sale_host_processing),
                    processingStatus = hostProcessingStateMachine.restart(),
                    cardData = result,
                    acquirerOptions = emptyList()
                )
            }
            processTransaction(availableOptions.first(), result)
        } else {
            _uiState.update {
                it.copy(
                    step = CardTransactionStep.SelectingAcquirer(availableOptions),
                    statusMessage = string(R.string.sale_select_acquirer_title),
                    processingStatus = null,
                    cardData = result,
                    acquirerOptions = availableOptions
                )
            }
            startSelectionTimer()
        }
    }

    fun onCardReadError(message: String) {
        Log.d(TAG, "onCardReadError message=$message")
        if (!supportedTransactionTypes.contains(transactionType)) return
        selectionJob?.cancel()
        activeCardData = null
        currentOptions = emptyList()
        val display = message.ifBlank { string(R.string.card_reader_error, "") }
        showError(display)
    }

    fun onAcquirerSelected(acquirerId: String) {
        Log.d(TAG, "onAcquirerSelected acquirerId=$acquirerId")
        val option = currentOptions.find { it.acquirer.AcqID == acquirerId }
        val cardData = activeCardData
        if (option == null || cardData == null) {
            Log.d(TAG, "onAcquirerSelected missing option or cardData option=${option != null} cardData=${cardData != null}")
            showError(string(R.string.sale_error_no_acquirer))
            return
        }
        selectionJob?.cancel()
        _uiState.update {
            it.copy(
                step = CardTransactionStep.ProcessingHost,
                statusMessage = string(R.string.sale_host_processing),
                processingStatus = hostProcessingStateMachine.restart(),
                acquirerOptions = emptyList()
            )
        }
        processTransaction(option, cardData)
    }

    fun retry() {
        Log.d(TAG, "retry invoked")
        selectionJob?.cancel()
        activeCardData = null
        currentOptions = emptyList()
        stopWaitingForResponseCountdown()
        _uiState.update {
            it.copy(
                step = CardTransactionStep.AwaitingCard,
                statusMessage = string(R.string.sale_present_card),
                cardData = null,
                acquirerOptions = emptyList(),
                processingStatus = null,
            )
        }
    }

    fun cancelTransaction() {
        Log.d(TAG, "cancelTransaction invoked")
        selectionJob?.cancel()
        stopWaitingForResponseCountdown()
        viewModelScope.launch {
            Log.d(TAG, "Emitting CardTransactionEvent.Cancelled")
            _events.emit(CardTransactionEvent.Cancelled)
        }
    }

    fun onSelectionTimeout() {
        Log.d(TAG, "onSelectionTimeout invoked")
        activeCardData = null
        currentOptions = emptyList()
        showError(string(R.string.sale_error_selection_timeout))
    }

    private fun updateProcessingStatus(event: HostProcessingEvent) {
        var updated: ProcessingStatusUi? = null
        _uiState.update { current ->
            current.processingStatus ?: return@update current
            val next = hostProcessingStateMachine.onEvent(event)
            updated = next
            current.copy(processingStatus = next)
        }
        if (updated != null) {
            if (event == HostProcessingEvent.WaitingForResponse) {
                startWaitingForResponseCountdown()
            } else if (event != HostProcessingEvent.WaitingForResponse) {
                stopWaitingForResponseCountdown()
            }
        }
    }

    private fun setProcessingResult(state: ProcessingStatusStepState, detail: String) {
        stopWaitingForResponseCountdown()
        _uiState.update { current ->
            val status = current.processingStatus ?: return@update current
            val updated = when (state) {
                ProcessingStatusStepState.COMPLETED -> hostProcessingStateMachine.onSuccess(detail)
                ProcessingStatusStepState.FAILED -> hostProcessingStateMachine.onFailure(detail)
                else -> status
            }
            current.copy(processingStatus = updated)
        }
    }

    private fun processTransaction(option: CardTransactionAcquirerOption, cardData: CardReadResult) {
        Log.d(
            TAG,
            "processTransaction option=${option.acquirer.AcqID} maskedPan=${cardData.maskedCardNumber} entry=${cardData.slotType}",
        )
        if (cardData.onlinePinRequested && !option.acquirer.supportsDukptOnlinePin) {
            showError(string(R.string.online_pin_error_no_selected_acquirer_pin_type))
            return
        }
        viewModelScope.launch {
            Log.d(TAG, "processTransaction coroutine started")
            val isoFactory = IsoMessageFactoryProvider.factoryFor()
            var needsReversal = false
            var reversalCandidate: PendingReversal? = null
            var reversalContext: ReversalContext? = null
            var queuedReversal: PendingReversal? = null
            var activeIpProfile: TMS_HostConnectionInfo? = null
            var activeHostSettings: HostSettings? = null
            try {
                val ipProfile = tmsDatabase.IPTab.firstOrNull { it.IPTabID == option.acquirer.IPTabTran }
                    ?: run {
                        Log.d(TAG, "processTransaction missing IP profile")
                        showError(string(R.string.sale_error_no_acquirer))
                        return@launch
                    }
                activeIpProfile = ipProfile
                val terminalConfig = terminal ?: run {
                    Log.d(TAG, "processTransaction missing terminal configuration")
                    showError(string(R.string.sale_error_no_acquirer))
                    return@launch
                }
                val hostSettings = buildHostSettings(option.acquirer, ipProfile, terminalConfig)
                activeHostSettings = hostSettings
                Log.d(
                    TAG,
                    "processTransaction hostSettings primary=${hostSettings.primary} secondary=${hostSettings.secondary} tls=${hostSettings.isTls}",
                )
                if (hostSettings.primary == null && hostSettings.secondary == null) {
                    showError(string(R.string.err_comm_error))
                    return@launch
                }

                reversalContext = ReversalContext(option.acquirer, ipProfile, terminalConfig, hostSettings)

                val reversalsCleared = processPendingReversals(
                    acquirer = option.acquirer,
                    ipProfile = ipProfile,
                    terminal = terminalConfig,
                    hostSettings = hostSettings,
                    isoFactory = isoFactory,
                )
                if (!reversalsCleared) {
                    showError(string(R.string.sale_error_reversal_pending))
                    return@launch
                }

                val stan = StanProvider.nextStan()
                val invoiceId = InvoiceNumberProvider.nextInvoiceNumber()
                Log.d(TAG, "processTransaction stan=$stan invoice=$invoiceId")
                val procInfo = buildProcInfo(option, cardData, stan, invoiceId)

                val transactionConfig = TransactionConfigRegistry.configFor(procInfo.TransLog.TxnType)
                needsReversal = transactionConfig?.hasAttribute(TransactionAttribute.NEEDS_REVERSAL) == true

                val message = try {
                    hostMessageBuilder.build(
                        acquirer = option.acquirer,
                        procInfo = procInfo,
                        isoFactory = isoFactory,
                        stanSupplier = { stan },
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to build ISO8583 request", error)
                    showError(error.localizedMessage ?: string(R.string.sale_error_host_generic, ""))
                    return@launch
                }
                if (needsReversal) {
                    reversalCandidate = createPendingReversalCandidate(option.acquirer, procInfo, message)
                }

                val connectTimeoutMs = secondsToMillis(hostSettings.connectTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SEC)
                val readTimeoutMs = secondsToMillis(hostSettings.readTimeoutSeconds, DEFAULT_READ_TIMEOUT_SEC)
                waitingForResponseTimeoutSeconds = ((readTimeoutMs + 999) / 1000).coerceAtLeast(1)
                Log.d(
                    TAG,
                    "processTransaction timeouts connectMs=$connectTimeoutMs readMs=$readTimeoutMs attempts=${hostSettings.attempts}",
                )

                val request = HostTransactionRequest(
                    acquirer = option.acquirer,
                    ipProfile = ipProfile,
                    terminal = terminalConfig,
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
                    sslSocketFactory = if (hostSettings.isTls) AcquirerSslCache.get(ipProfile.IPTabID.toString()) else null,
                    isoFactory = isoFactory,
                    onConnected = suspend {
                        if (needsReversal && reversalCandidate != null && queuedReversal == null) {
                            try {
                                queuedReversal = queuePendingReversal(reversalCandidate)
                                Log.d(
                                    TAG,
                                    "Queued pending reversal id=${queuedReversal?.id} acquirer=${option.acquirer.AcqID}"
                                )
                            } catch (error: Throwable) {
                                Log.e(TAG, "Unable to queue pending reversal", error)
                            }
                        }
                    },
                    onStatusChanged = { event ->
                        updateProcessingStatus(event)
                    },
                )
                Log.d(TAG, "processTransaction executing host request")

                val result = hostClient.execute(request)
                val responseCode = result.isoMessage.getFieldValue(39)
                Log.d(TAG, "processTransaction responseCode=$responseCode")
                val responseMessage = HostResponseMessageResolver.resolveOrFallback(responseCode)
                val approved = responseCode == "00"
                val shouldPersistReversal =
                    needsReversal && (responseCode == "91" || responseCode == "96" || responseCode == "92") &&
                        reversalCandidate != null
                updateProcInfoWithResponse(procInfo, result)

                if (approved) {
                    setProcessingResult(ProcessingStatusStepState.COMPLETED, responseMessage)
                    Log.d(TAG, "processTransaction approved inserting transaction")
                    val transaction = procInfo.toTransaction()
                    val rowId = transactionRepository.insert(transaction)
                    val storedTransaction = transaction.copy(id = rowId.toInt())
                    TransactionReportBridge.reportTransaction(
                        context = GlobalConnectPaymentApplication.instance,
                        transaction = storedTransaction,
                        tmsDatabase = tmsDatabase,
                    )
                    if (transaction.type == TransactionType.CHECKOUT) {
                        checkInRowId?.let { id ->
                            runCatching {
                                transactionRepository.getTransactionFromId(id)?.let { original ->
                                    transactionRepository.delete(original)
                                }
                            }.onFailure { error ->
                                Log.e(TAG, "Unable to remove check-in transaction id=$id", error)
                            }
                        }
                    }
                    viewModelScope.launch {
                        _events.emit(CardTransactionEvent.NavigateToResult(rowId.toString()))
                    }
                } else {
                    setProcessingResult(ProcessingStatusStepState.FAILED, responseMessage)
                    Log.w(
                        TAG,
                        "Host declined transaction responseCode=$responseCode message=$responseMessage",
                    )
                    showError(responseMessage)
                }

                if (needsReversal) {
                    if (shouldPersistReversal) {
                        reversalContext?.let { context ->
                            queuedReversal = recordPendingReversal(
                                acquirer = context.acquirer,
                                ipProfile = context.ipProfile,
                                terminal = context.terminal,
                                hostSettings = context.hostSettings,
                                isoFactory = isoFactory,
                                candidate = reversalCandidate!!,
                                existing = queuedReversal,
                                reason = reversalReasonForCode(responseCode),
                                responseCode = responseCode,
                            )
                        }
                    } else if (queuedReversal != null) {
                        try {
                            clearPendingReversal(queuedReversal!!)
                            Log.d(TAG, "Cleared pending reversal id=${queuedReversal?.id}")
                        } catch (error: Throwable) {
                            Log.e(TAG, "Unable to clear pending reversal", error)
                        } finally {
                            queuedReversal = null
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Host transaction failed", error)
                val safeMessage = formatSafeHostConnectionError(error, activeIpProfile, activeHostSettings)
                setProcessingResult(ProcessingStatusStepState.FAILED, safeMessage)
                if (needsReversal && reversalCandidate != null) {
                    reversalContext?.let { context ->
                        queuedReversal = recordPendingReversal(
                            acquirer = context.acquirer,
                            ipProfile = context.ipProfile,
                            terminal = context.terminal,
                            hostSettings = context.hostSettings,
                            isoFactory = isoFactory,
                            candidate = reversalCandidate!!,
                            existing = queuedReversal,
                            reason = ReversalReason.NO_RESPONSE,
                        )
                    }
                }
                showError(safeMessage)
            }
        }
    }

    private data class ReversalContext(
        val acquirer: TMS_Acquirer,
        val ipProfile: TMS_HostConnectionInfo,
        val terminal: TMS_Terminal,
        val hostSettings: HostSettings,
    )

    private fun formatSafeHostConnectionError(
        error: Throwable,
        ipProfile: TMS_HostConnectionInfo?,
        hostSettings: HostSettings?,
    ): String {
        if (ipProfile == null || hostSettings == null) {
            return formatHostErrorMessage(error)
        }

        val serverName = ipProfile.description.ifBlank { ipProfile.IPTabID }.ifBlank {
            optionlessHostConnectionLabel()
        }
        val tries = totalConfiguredHostTries(hostSettings)
        return string(R.string.sale_error_host_connection_safe, serverName, tries)
    }

    private fun totalConfiguredHostTries(hostSettings: HostSettings): Int {
        val endpointCount = listOfNotNull(hostSettings.primary, hostSettings.secondary).size.coerceAtLeast(1)
        val primaryTries = if (hostSettings.primary != null) hostSettings.primaryRetries.coerceAtLeast(1) else 0
        val secondaryTries = if (hostSettings.secondary != null) hostSettings.secondaryRetries.coerceAtLeast(1) else 0
        val perCycleTries = (primaryTries + secondaryTries).takeIf { it > 0 } ?: endpointCount
        return hostSettings.attempts.coerceAtLeast(1) * perCycleTries
    }

    private fun optionlessHostConnectionLabel(): String = string(R.string.sale_error_host_connection_unknown)

    private fun buildProcInfo(
        option: CardTransactionAcquirerOption,
        cardData: CardReadResult,
        stan: String,
        invoiceId: String
    ): ProcInfo {
        Log.d(
            TAG,
            "buildProcInfo acquirer=${option.acquirer.AcqID} issuer=${option.issuer.IssuerName} maskedPan=${cardData.maskedCardNumber}",
        )
        val transLog = ProcInfo().TransLog
        transLog.TxnType = transactionType.toTransactionString()
        transLog.AccType = "Credit"
        transLog.AcquirerId = option.acquirer.AcqID
        transLog.IssuerId = option.issuer.IssuID
        transLog.CardRangeId = option.cardRange?.CardRangeID ?: ""
        transLog.CardRangeName = option.cardRange?.RangeName ?: ""
        transLog.CurrCode = sysParam.CurrCode
        transLog.TxnAmt = totalAmount
        transLog.BaseAmt = baseAmount
        transLog.Tax1Amt = tax1Discount.discountedTaxAmountText
        transLog.Tax1DiscountAmt = tax1Discount.discountAmountText
        transLog.OriginalTax1Amt = if (tax1Discount.hasDiscount) {
            tax1Discount.originalTaxAmountText
        } else {
            ""
        }
        transLog.Tax2Amt = tax2Amount
        transLog.TipAmt = tipAmount
        transLog.InvoiceId = invoiceId
        transLog.TxnId = stan
        transLog.AuthNtwkName = option.acquirer.AcquirerName
        transLog.CardType = option.issuer.IssuerName
        transLog.FolioNumber = folioNumber
        if (transactionType == TransactionType.CHECKOUT) {
            transLog.OriginalTransactionId = originalTransactionId
        }

        val pan = extractPan(cardData)
        Log.d(TAG, "buildProcInfo extractedPan=${pan?.let { obfuscatePAN(it) }}")
        transLog.CardNbr = pan ?: ""
        transLog.PAN = pan
        transLog.MaskedCardNbr = cardData.maskedCardNumber ?: pan?.let { obfuscatePAN(it) } ?: ""
        transLog.Track1 = cardData.track1 ?: ""
        transLog.Track2 = cardData.track2 ?: ""
        transLog.Track3 = cardData.track3 ?: ""
        transLog.Field55 = cardData.rawEmvData?.trim().orEmpty()

        val expiryDigits = cardData.expiryDate?.filter { it.isDigit() }
        if (expiryDigits?.length == 4) {
            val year = expiryDigits.substring(0, 2)
            val month = expiryDigits.substring(2, 4)
            transLog.ExpireMonth = month
            transLog.ExpireYear = "20$year"
        }

        val (interfaceCode, sourceLabel) = entryMode(cardData)
        transLog.TxnInterface = interfaceCode
        transLog.CardDataSource = sourceLabel

        val emvTags = cardData.emvTags.associateBy { it.tag.uppercase(Locale.US) }
        transLog.AID = emvTags["84"]?.value ?: ""
        transLog.TVR = emvTags["95"]?.value ?: ""
        transLog.TSI = emvTags["9B"]?.value ?: ""
        transLog.AC = emvTags["9F26"]?.value ?: ""
        transLog.Cryptogram = transLog.AC
        transLog.ATC = emvTags["9F36"]?.value ?: ""
        transLog.IAD = emvTags["9F10"]?.value ?: ""
        transLog.CryptoInfo = emvTags["9F27"]?.value ?: ""
        val applicationLabel = emvTags["50"]?.value ?: option.issuer.IssuerName
        transLog.AppName = applicationLabel
        transLog.AppLabel = applicationLabel
        transLog.AppId = transLog.AID
        transLog.CVMText = "No CVM"
        transLog.TipProcessingInfo = option.acquirer.TIPProcs.toString()

        return ProcInfo(TransLog = transLog)
    }

    private fun updateProcInfoWithResponse(procInfo: ProcInfo, result: HostTransactionResult) {
        Log.d(TAG, "updateProcInfoWithResponse responseCode=${result.isoMessage.getFieldValue(39)}")
        val responseCode = result.isoMessage.getFieldValue(39)
        procInfo.TransLog.RspCode = responseCode
        procInfo.TransLog.TxnResult = responseCode ?: ""
        procInfo.TransLog.AuthCode = result.isoMessage.getFieldValue(38)
        procInfo.TransLog.AuthorizationId = procInfo.TransLog.AuthCode
        procInfo.TransLog.RefNbr = result.isoMessage.getFieldValue(37)
        procInfo.TransLog.ExternalRefNumber = result.isoMessage.getFieldValue(62)
        procInfo.TransLog.ARC = responseCode ?: procInfo.TransLog.ARC
        procInfo.TransLog.RspDT = result.timestamp.toString()
        procInfo.TransLog.TxnResultMsg = if (responseCode == "00") string(R.string.msg_approved) else string(R.string.msg_declined)
        procInfo.TransLog.RspText = procInfo.TransLog.TxnResultMsg

        val privateUseTags = PrivateUseData63.parse(result.isoMessage.getFieldValue(63))
        privateUseTags["22"]?.let { procInfo.TransLog.AlternateHostResponse = it }
        privateUseTags["29"]?.let { procInfo.TransLog.AdditionalHostPrintData = it }
        privateUseTags["46"]?.let { procInfo.TransLog.PaymentPlanQueryResponse = it }
    }

    private fun resolveAcquirerOptions(cardData: CardReadResult): List<CardTransactionAcquirerOption> {
        Log.d(
            TAG,
            "resolveAcquirerOptions slot=${cardData.slotType} maskedPan=${cardData.maskedCardNumber} emvTags=${cardData.emvTags.size}",
        )
        val aid = cardData.emvTags.firstOrNull { it.tag.equals("84", ignoreCase = true) }?.value
        val pan = extractPan(cardData)
        val fallback = isFallback(cardData)
        val candidateRanges = resolveCardRanges(aid, pan)
        Log.d(TAG, "resolveAcquirerOptions candidateRanges=${candidateRanges.size}")
        if (candidateRanges.isEmpty()) return emptyList()

        val cardRangeIds = candidateRanges.map { it.CardRangeID }.toSet()
        val issuers = tmsDatabase.Issuer.filter { issuer ->
            issuerSupportsTransaction(issuer, transactionType) && issuerMatchesCardRanges(issuer, cardRangeIds)
        }
        if (issuers.isEmpty()) return emptyList()

        val acquirerMap = tmsDatabase.Acquirer.associateBy { it.AcqID }
        val options = issuers.mapNotNull { issuer ->
            val acquirer = acquirerMap[issuer.AcqID] ?: return@mapNotNull null
            if (!acquirerSupportsTransaction(acquirer, transactionType, fallback)) return@mapNotNull null
            val issuerRanges = issuerCardRangeIds(issuer)
            val matchedRange = candidateRanges.firstOrNull { issuerRanges.contains(it.CardRangeID) }
            CardTransactionAcquirerOption(acquirer, issuer, matchedRange)
        }
        val distinct = options.distinctBy { it.acquirer.AcqID }
        Log.d(TAG, "resolveAcquirerOptions returning [${distinct.size}] options: $distinct")
        return distinct
    }

    private fun resolveCardRanges(aid: String?, pan: String?): List<TMS_CardRanges> {
        Log.d(TAG, "resolveCardRanges aid=$aid panLength=${pan?.length}")
        val normalizedAid = aid?.trim()?.uppercase(Locale.US).takeUnless { it.isNullOrEmpty() }
        val ranges = tmsDatabase.CardRange
        val aidMatches = normalizedAid?.let { value ->
            ranges.filter { range -> cardRangeMatchesAid(range, value) }
        } ?: emptyList()

        val matches = if (aidMatches.isNotEmpty()) {
            aidMatches
        } else {
            val digits = pan?.filter { it.isDigit() }
            if (digits.isNullOrEmpty()) {
                emptyList()
            } else {
                ranges.filter { range -> cardRangeMatchesPan(range, digits) }
            }
        }

        if (matches.isEmpty()) {
            Log.d(TAG, "resolveCardRanges no matches found")
            return emptyList()
        }
        val exclusive = matches.filter { it.ExclusiveBIN }
        val result = if (exclusive.isNotEmpty()) exclusive else matches
        Log.d(TAG, "resolveCardRanges returning count=${result.size}")
        return result
    }

    private fun cardRangeMatchesAid(range: TMS_CardRanges, normalizedAid: String): Boolean {
        val aidRefs = range.processedAidRefs.map { it.trim() }.filter { it.isNotBlank() }
        if (aidRefs.isEmpty()) return false

        val directMatch = aidRefs.any { it.equals(normalizedAid, ignoreCase = true) }
        if (directMatch) return true

        val contactMatches = tmsDatabase.AIDtab.any { config ->
            aidRefs.any { it.equals(config.config_id, ignoreCase = true) } &&
                config.AID.equals(normalizedAid, ignoreCase = true)
        }
        val contactlessMatches = tmsDatabase.PCDApps.any { config ->
            aidRefs.any { it.equals(config.config_id, ignoreCase = true) } &&
                config.AID.equals(normalizedAid, ignoreCase = true)
        }
        val matches = contactMatches || contactlessMatches
        Log.d(
            TAG,
            "cardRangeMatchesAid range=${range.CardRangeID} aid=$normalizedAid refs=$aidRefs matches=$matches"
        )
        return matches
    }

    private fun cardRangeMatchesPan(range: TMS_CardRanges, panDigits: String): Boolean {
        val configuredPanLength = range.Length.toInt()
        val panLengthMatches = configuredPanLength == 0 || panDigits.length == configuredPanLength
        if (!panLengthMatches) {
            Log.d(
                TAG,
                "cardRangeMatchesPan range=${range.CardRangeID} panLength=${panDigits.length} " +
                    "configuredPanLength=$configuredPanLength matches=false"
            )
            return false
        }

        val lowDigits = range.binLow.filter { it.isDigit() }
        val highDigits = range.binHigh.filter { it.isDigit() }
        val compareLength = maxOf(lowDigits.length, highDigits.length)
        if (compareLength == 0 || panDigits.length < compareLength) {
            Log.d(
                TAG,
                "cardRangeMatchesPan range=${range.CardRangeID} invalid bounds low='${range.binLow}' high='${range.binHigh}'"
            )
            return false
        }

        val panPrefix = panDigits.take(compareLength).toBigIntegerOrZero()
        val low = lowDigits.padEnd(compareLength, '0').toBigIntegerOrZero()
        val high = highDigits.padEnd(compareLength, '9').toBigIntegerOrZero()
        val matches = panPrefix >= low && panPrefix <= high
        Log.d(
            TAG,
            "cardRangeMatchesPan range=${range.CardRangeID} panLength=${panDigits.length} " +
                "configuredPanLength=$configuredPanLength compareLength=$compareLength panPrefix=$panPrefix " +
                "low=$low high=$high matches=$matches"
        )
        return matches
    }

    private fun issuerMatchesCardRanges(issuer: TMS_Issuer, cardRangeIds: Set<String>): Boolean {
        val matches = issuerCardRangeIds(issuer).any { cardRangeIds.contains(it) }
        Log.d(TAG, "issuerMatchesCardRanges issuer=${issuer.IssuerName} matches=$matches")
        return matches
    }

    private fun issuerCardRangeIds(issuer: TMS_Issuer): List<String> {
        val values = listOf(
            issuer.CardRange1, issuer.CardRange2, issuer.CardRange3, issuer.CardRange4, issuer.CardRange5,
            issuer.CardRange6, issuer.CardRange7, issuer.CardRange8, issuer.CardRange9, issuer.CardRange10,
            issuer.CardRange11, issuer.CardRange12, issuer.CardRange13, issuer.CardRange14, issuer.CardRange15,
            issuer.CardRange16, issuer.CardRange17, issuer.CardRange18, issuer.CardRange19, issuer.CardRange20
        ).filter { it.isNotBlank() }
        Log.d(TAG, "issuerCardRangeIds issuer=${issuer.IssuerName} [${values.size}] cardRangeIds=$values")
        return values
    }

    private fun issuerSupportsTransaction(issuer: TMS_Issuer, type: TransactionType): Boolean {
        val supported = when (type) {
            TransactionType.REFUND -> issuer.Refund
            TransactionType.VOID -> issuer.Void
            TransactionType.PAYMENT -> issuer.Payment
            TransactionType.CHECKIN, TransactionType.CHECKOUT -> issuer.chkInOut
            else -> true
        }
        Log.d(TAG, "issuerSupportsTransaction issuer=${issuer.IssuerName} type=$type supported=$supported")
        return supported
    }

    private fun acquirerSupportsTransaction(
        acquirer: TMS_Acquirer,
        type: TransactionType,
        isFallback: Boolean
    ): Boolean {
        val supported = when (type) {
            TransactionType.SALE -> acquirer.EnableSales && (!isFallback || acquirer.AllowFallBack)
            TransactionType.PAYMENT -> acquirer.EnablePayment
            TransactionType.CASH -> acquirer.EnableCash
            else -> true
        }
        Log.d(TAG, "acquirerSupportsTransaction acquirer=${acquirer.AcqID} type=$type fallback=$isFallback supported=$supported")
        return supported
    }

    private fun buildHostSettings(
        acquirer: TMS_Acquirer,
        ipProfile: TMS_HostConnectionInfo,
        terminal: TMS_Terminal
    ): HostSettings {
        Log.d(
            TAG,
            "buildHostSettings acquirer=${acquirer.AcqID} ipProfile=${ipProfile.IPTabID} terminal=${terminal.TermID}",
        )
        val length = LengthPrefixRegistry.resolve(acquirer.HostProtocol, terminal)
        val connectTimeout = ipProfile.IPConnTime.safeInt(DEFAULT_CONNECT_TIMEOUT_SEC)
        val readTimeout = ipProfile.TranTimeOut.safeInt(DEFAULT_READ_TIMEOUT_SEC)
        val attempts = ipProfile.AttemptIPT.safeInt(1)
        val primaryRetries = ipProfile.IPConnRetriesP.safeInt(1)
        val secondaryRetries = ipProfile.IPConnRetriesS.safeInt(1)

        val settings = HostSettings(
            isTls = ipProfile.SSL,
            primary = parseHostAddress(ipProfile.PrimIpAddr),
            secondary = parseHostAddress(ipProfile.SecIpAddr),
            connectTimeoutSeconds = connectTimeout,
            readTimeoutSeconds = readTimeout,
            attempts = attempts,
            primaryRetries = primaryRetries,
            secondaryRetries = secondaryRetries,
            length = length
        )
        Log.d(
            TAG,
            "buildHostSettings result primary=${settings.primary} secondary=${settings.secondary} tls=${settings.isTls}",
        )
        return settings
    }

    private fun secondsToMillis(seconds: Int, fallback: Int): Int {
       // Log.d(TAG, "secondsToMillis seconds=$seconds fallback=$fallback")
        val value = if (seconds <= 0) fallback else seconds
        val capped = value.coerceAtMost(Int.MAX_VALUE / 1000)
        val result = capped * 1000
       // Log.d(TAG, "secondsToMillis result=$result")
        return result
    }

    private fun Long.safeInt(default: Int): Int {
        //Log.d(TAG, "safeInt value=$this default=$default")
        if (this <= 0) return default
        val result = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        //Log.d(TAG, "safeInt result=$result")
        return result
    }

    private fun extractPan(cardData: CardReadResult): String? {
        Log.d(TAG, "extractPan masked=${cardData.maskedCardNumber} track2Length=${cardData.track2?.length}")
        val explicit = cardData.cardNumber?.filter { it.isDigit() }
        if (!explicit.isNullOrEmpty()) {
            Log.d(TAG, "extractPan using explicit PAN")
            return explicit
        }
        val track2 = cardData.track2 ?: return null
        val trimmed = track2.trim()
        val start = trimmed.indexOf(';').let { if (it >= 0 && it + 1 < trimmed.length) it + 1 else 0 }
        val end = trimmed.indexOfAny(charArrayOf('=', 'D')).let { if (it > start) it else trimmed.length }
        val candidate = trimmed.substring(start, end).filter { it.isDigit() }
        val result = candidate.takeIf { it.isNotEmpty() }
        Log.d(TAG, "extractPan derivedPan=${result?.let { obfuscatePAN(it) }}")
        return result
    }

    private fun isFallback(cardData: CardReadResult): Boolean {
        val fallback = cardData.slotType == CardSlotTypeEnum.SWIPE && cardData.isIcc
        Log.d(TAG, "isFallback slot=${cardData.slotType} isIcc=${cardData.isIcc} fallback=$fallback")
        return fallback
    }

    private fun entryMode(cardData: CardReadResult): Pair<String, String> {
        Log.d(TAG, "entryMode slot=${cardData.slotType} isFallback=${isFallback(cardData)}")
        return when (cardData.slotType) {
            CardSlotTypeEnum.RF -> "04" to "CONTACTLESS"
            CardSlotTypeEnum.ICC1, CardSlotTypeEnum.ICC2, CardSlotTypeEnum.ICC3 -> "03" to "ICC"
            CardSlotTypeEnum.SWIPE -> {
                if (isFallback(cardData)) "05" to "FALLBACK" else "02" to "SWIPE"
            }
            else -> "00" to "UNSPECIFIED"
        }
    }

    private suspend fun processPendingReversals(
        acquirer: TMS_Acquirer,
        ipProfile: TMS_HostConnectionInfo,
        terminal: TMS_Terminal,
        hostSettings: HostSettings,
        isoFactory: IsoMessageFactory,
    ): Boolean {
        val pending = transactionRepository.getPendingReversals(acquirer.AcqID)
        if (pending.isEmpty()) return true

        val connectTimeoutMs = secondsToMillis(hostSettings.connectTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SEC)
        val readTimeoutMs = secondsToMillis(hostSettings.readTimeoutSeconds, DEFAULT_READ_TIMEOUT_SEC)

        for (reversal in pending) {
            val message = buildReversalIsoMessage(reversal, isoFactory)
            val request = HostTransactionRequest(
                acquirer = acquirer,
                ipProfile = ipProfile,
                terminal = terminal,
                message = message,
                lengthConfig = hostSettings.length,
                primaryEndpoint = hostSettings.primary,
                secondaryEndpoint = hostSettings.secondary,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                attempts = hostSettings.attempts,
                primaryRetries = hostSettings.primaryRetries,
                secondaryRetries = hostSettings.secondaryRetries,
                useTls = hostSettings.isTls,
                sslSocketFactory = if (hostSettings.isTls) AcquirerSslCache.get(ipProfile.IPTabID.toString()) else null,
                isoFactory = isoFactory,
            )
            val attemptTimestamp = LocalDateTime.now().format(dateTimeFormatter)
            val updated = reversal.copy(
                attempts = reversal.attempts + 1,
                lastAttemptAt = attemptTimestamp,
            )
            try {
                val result = hostClient.execute(request)
                val responseCode = result.isoMessage.getFieldValue(39)
                val responseUpdated = updated.copy(lastResponseCode = responseCode)
                if (responseCode == "00" || responseCode == "21") {
                    transactionRepository.deleteReversal(reversal)
                    if (terminal.PrintReversalReceipt) {
                        printReversalReceipt(responseUpdated, acquirer)
                    }
                } else {
                    transactionRepository.updateReversal(responseUpdated)
                    return false
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Reversal transmission failed", error)
                transactionRepository.updateReversal(updated)
                return false
            }
        }
        return true
    }

    private suspend fun queuePendingReversal(candidate: PendingReversal): PendingReversal {
        val pending = candidate.copy(reason = ReversalReason.PENDING)
        val id = transactionRepository.insertReversal(pending)
        return pending.copy(id = id)
    }

    private suspend fun recordPendingReversal(
        acquirer: TMS_Acquirer,
        ipProfile: TMS_HostConnectionInfo,
        terminal: TMS_Terminal,
        hostSettings: HostSettings,
        isoFactory: IsoMessageFactory,
        candidate: PendingReversal,
        existing: PendingReversal?,
        reason: ReversalReason,
        responseCode: String? = null,
    ): PendingReversal {
        val base = (existing ?: candidate).copy(
            reason = reason,
            lastResponseCode = responseCode,
        )
        val persisted = if (existing != null && existing.id != 0L) {
            transactionRepository.updateReversal(base)
            base
        } else {
            val id = transactionRepository.insertReversal(base)
            base.copy(id = id)
        }
        processPendingReversals(acquirer, ipProfile, terminal, hostSettings, isoFactory)
        return persisted
    }

    private suspend fun clearPendingReversal(reversal: PendingReversal) {
        transactionRepository.deleteReversal(reversal)
    }

    private fun buildReversalIsoMessage(
        reversal: PendingReversal,
        isoFactory: IsoMessageFactory,
    ): IsoMessage {
        val message = isoFactory.newMessage()
        reversal.header?.let { message.setHeader(it) }
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

    private fun createPendingReversalCandidate(
        acquirer: TMS_Acquirer,
        procInfo: ProcInfo,
        message: IsoMessage,
    ): PendingReversal {
        val fieldValues = mutableMapOf<Int, String>()
        for (index in 2..128) {
            if (message.hasField(index)) {
                message.getFieldValue(index)?.let { fieldValues[index] = it }
            }
        }
        val createdAt = LocalDateTime.now().format(dateTimeFormatter)
        val maskedPan = procInfo.TransLog.MaskedCardNbr.ifBlank {
            obfuscatePAN(procInfo.TransLog.PAN ?: message.getFieldValue(2) ?: "") ?: ""
        }
        val stan = message.getFieldValue(11) ?: procInfo.TransLog.TxnId ?: ""
        return PendingReversal(
            acquirerId = acquirer.AcqID,
            transactionType = procInfo.TransLog.TxnType,
            stan = stan,
            originalMessageType = message.messageType ?: "",
            processingCode = message.getFieldValue(3) ?: "",
            header = message.header,
            fieldValues = fieldValues,
            createdAt = createdAt,
            invoiceNumber = procInfo.TransLog.InvoiceId,
            transactionAmount = procInfo.TransLog.TxnAmt,
            maskedPan = maskedPan,
            cardBrand = procInfo.TransLog.CardType ?: "",
        )
    }

    private fun reversalReasonForCode(code: String?): ReversalReason = when (code) {
        "91" -> ReversalReason.RESPONSE_91
        "96" -> ReversalReason.RESPONSE_96
        else -> ReversalReason.UNKNOWN
    }

    private suspend fun printReversalReceipt(
        reversal: PendingReversal,
        acquirer: TMS_Acquirer,
    ) {
        withContext(Dispatchers.IO) {
            val profile = profileRepository.get()
            val timestamp = runCatching { LocalDateTime.parse(reversal.createdAt, dateTimeFormatter) }
                .getOrElse { LocalDateTime.now() }
            val transactionLabel = reversal.transactionType
                .transactionStringToTransactionType()
                .toStringForUsers()
            val amountText = FormatterUtils.formatAmount(acquirer.Currency, reversal.transactionAmount)
            val maskedPan = if (reversal.maskedPan.isNotBlank()) {
                reversal.maskedPan
            } else {
                obfuscatePAN(reversal.fieldValues[2]) ?: ""
            }
            val rrn = reversal.fieldValues[37]?.takeIf { it.isNotBlank() } ?: reversal.stan
            val receiptData = ReversalReceiptData(
                timestamp = timestamp,
                maskedPan = maskedPan,
                cardBrand = reversal.cardBrand,
                rrn = rrn,
                invoiceNumber = reversal.invoiceNumber,
                transactionTypeLabel = transactionLabel,
                totalAmountText = amountText,
            )
            paymentPrinter.printReversalReceipt(
                context = GlobalConnectPaymentApplication.instance.applicationContext,
                profile = profile,
                tmsDatabase = tmsDatabase,
                data = receiptData,
            )
        }
    }

    private fun startSelectionTimer() {
        Log.d(TAG, "startSelectionTimer invoked")
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            delay(SELECTION_SCREEN_TIMEOUT_MS)
            Log.d(TAG, "startSelectionTimer expired")
            onSelectionTimeout()
        }
    }

    private fun startWaitingForResponseCountdown() {
        val seconds = waitingForResponseTimeoutSeconds.coerceAtLeast(1)
        waitingForResponseJob?.cancel()
        waitingForResponseJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining >= 0) {
                applyWaitingForResponseDetail(remaining)
                if (remaining == 0) break
                delay(1_000)
                remaining -= 1
            }
        }
    }

    private fun stopWaitingForResponseCountdown() {
        waitingForResponseJob?.cancel()
        waitingForResponseJob = null
        _uiState.update { current ->
            current.processingStatus ?: return@update current
            val updated = hostProcessingStateMachine.onWaitingForResponseDetail("")
            current.copy(processingStatus = updated)
        }
    }

    private fun applyWaitingForResponseDetail(remainingSeconds: Int) {
        val detail = string(R.string.processing_status_waiting_countdown, remainingSeconds)
        _uiState.update { current ->
            current.processingStatus ?: return@update current
            val updated = hostProcessingStateMachine.onWaitingForResponseDetail(detail)
            current.copy(processingStatus = updated)
        }
    }

    private fun showError(message: String) {
        Log.w(TAG, "Transaction failed: $message")
        stopWaitingForResponseCountdown()
        _uiState.update {
            it.copy(
                step = CardTransactionStep.Error(message),
                statusMessage = null,
                acquirerOptions = emptyList(),
                cardData = null
            )
        }
    }

    override fun onCleared() {
        Log.d(TAG, "onCleared invoked")
        selectionJob?.cancel()
        Log.d(TAG, "onCleared cancelled selection job")
        waitingForResponseJob?.cancel()
        super.onCleared()
    }

    private fun String.toBigDecimalOrZero(): BigDecimal {
        Log.d(TAG, "toBigDecimalOrZero input=$this")
        return try {
            BigDecimal(this)
        } catch (error: NumberFormatException) {
            Log.w(TAG, "toBigDecimalOrZero parse failure", error)
            BigDecimal.ZERO
        }.also {
            Log.d(TAG, "toBigDecimalOrZero result=$it")
        }
    }

    private fun String?.toBigIntegerOrZero(): BigInteger {
        Log.d(TAG, "toBigIntegerOrZero input=$this")
        return try {
            if (this.isNullOrBlank()) BigInteger.ZERO else BigInteger(this)
        } catch (error: NumberFormatException) {
            Log.w(TAG, "toBigIntegerOrZero parse failure", error)
            BigInteger.ZERO
        }.also {
            Log.d(TAG, "toBigIntegerOrZero result=$it")
        }
    }

    private fun string(resId: Int, vararg args: Any?): String {
        val result = GlobalConnectPaymentApplication.instance.getString(resId, *args)
        //Log.d(TAG, "string resId=$resId resultLength=${result.length}")
        return result
    }

    companion object {
        private const val TAG = "CardTransactionVM"
        private const val DEFAULT_CONNECT_TIMEOUT_SEC = 10
        private const val DEFAULT_READ_TIMEOUT_SEC = 60
    }
}

sealed class CardTransactionEvent {
    data class NavigateToResult(val transactionId: String) : CardTransactionEvent()
    object Cancelled : CardTransactionEvent()
}

sealed class CardTransactionStep {
    /** Transient initial state before [CardTransactionViewModel.start] runs. No card search. */
    object Initializing : CardTransactionStep()
    object AwaitingCard : CardTransactionStep()
    data class SelectingCurrency(val currencies: List<CurrencyInfo>) : CardTransactionStep()
    data class SelectingAcquirer(val options: List<CardTransactionAcquirerOption>) : CardTransactionStep()
    object ProcessingHost : CardTransactionStep()
    data class Error(val message: String) : CardTransactionStep()
}

data class CardTransactionUiState(
    val step: CardTransactionStep = CardTransactionStep.Initializing,
    val statusMessage: String? = null,
    val processingStatus: ProcessingStatusUi? = null,
    val cardData: CardReadResult? = null,
    val acquirerOptions: List<CardTransactionAcquirerOption> = emptyList(),
    val baseAmount: String = "0.00",
    val tax1Amount: String = "0.00",
    val tax2Amount: String = "0.00",
    val tipAmount: String = "0.00",
    val totalAmount: String = "0.00",
    val currencySymbol: String? = null,
    val emvCountryCode: String = "0840",
    val emvCurrencyCode: String = "0840",
)

data class CardTransactionAcquirerOption(
    val acquirer: TMS_Acquirer,
    val issuer: TMS_Issuer,
    val cardRange: TMS_CardRanges?
) {
    override fun toString(): String {
        return buildString {
            append("Acquirer(ID=${acquirer.AcqID}, Name=${acquirer.AcquirerName}), ")
            append("Issuer(Name=${issuer.IssuerName}), ")
            if (cardRange != null) {
                append("CardRange(ID=${cardRange.CardRangeID}, Name=${cardRange.RangeName}, PanLow=${cardRange.CardRangeLow}, PanHigh=${cardRange.CardRangeHigh}) AID1=${cardRange.AID1} AID2=${cardRange.AID2} AID3=${cardRange.AID3} AID4=${cardRange.AID4}")
            } else {
                append("CardRange=None")
            }
        }
    }
}
