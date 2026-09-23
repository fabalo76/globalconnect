package one.globalconnect.paymentapp.transaction

import kotlinx.coroutines.flow.first
import one.globalconnect.paymentapp.security.EncryptionUtil
import one.globalconnect.paymentapp.ecr.EcrRuntime
import android.util.Log
import one.globalconnect.paymentapp.transaction.installments.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import one.globalconnect.tms.paymentapp.TMS_Acquirer
import one.globalconnect.tms.paymentapp.TMS_CardRanges
import one.globalconnect.tms.paymentapp.TMSDATA
import one.globalconnect.tms.paymentapp.TMS_Issuer
import one.globalconnect.tms.paymentapp.TMS_HostConnectionInfo
import one.globalconnect.tms.paymentapp.TMS_Terminal
import one.globalconnect.tms.paymentapp.TMS_PinKeyScheme
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.cardreader.CardReadResult
import one.globalconnect.paymentapp.cardreader.EmvKernelCompletion
import one.globalconnect.paymentapp.cardreader.EmvOnlineAuthorizationResponse
import one.globalconnect.paymentapp.cardreader.nexgo.NexgoApi
import one.globalconnect.paymentapp.cardreader.nexgo.OnlinePinScheme
import one.globalconnect.paymentapp.cardreader.nexgo.PinChangeCaptureFailure
import one.globalconnect.paymentapp.cardreader.nexgo.PinChangeCaptureResult
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
import com.uic.pos.iso8583.IsoMessage
import com.uic.pos.iso8583.IsoMessageFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val nexgoApi: NexgoApi = GlobalConnectPaymentApplication.instance.nexgoApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CardTransactionUiState())
    val uiState: StateFlow<CardTransactionUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CardTransactionEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<CardTransactionEvent> = _events.asSharedFlow()

    private val ecrRequest = EcrRuntime.sale.value
    val isEcrTransaction: Boolean get() = ecrRequest != null

    private val initialBaseAmount: String = savedStateHandle[AMOUNT_KEY] ?: "0.00"
    private val initialTax1Amount: String = savedStateHandle[TAX1_KEY] ?: "0.00"
    private val initialTax2Amount: String = savedStateHandle[TAX2_KEY] ?: "0.00"
    private val initialTipAmount: String = savedStateHandle[TIP_KEY] ?: "0.00"
    private val folioNumber: String = savedStateHandle[FOLIO_KEY] ?: ""
    private var originalTransactionId: String = savedStateHandle[ORIGINAL_TRANSACTION_ID_KEY] ?: ""
    private var checkInRowId: Int? = (savedStateHandle[CHECK_IN_ID_KEY] as String?)?.toIntOrNull()
    private val terminal: TMS_Terminal? = tmsDatabase.Terminal.firstOrNull()
    private val initialTax1Discount = Tax1DiscountCalculator.calculate(
        tax1Amount = initialTax1Amount,
        discountPercentage = terminal?.TaxDiscount ?: 0.0,
    )

    val transactionType: TransactionType = (savedStateHandle[TRANSACTION_TYPE_KEY]
        ?: TransactionType.ERROR.toTransactionString()).transactionStringToTransactionType()

    private val installmentContract = InstallmentContracts.forTransaction(transactionType)
    var installmentQueryPending: Boolean = installmentContract != null
        private set
    val cardDataQuery: Boolean get() = installmentQueryPending || transactionType in setOf(TransactionType.EXTRAS_BALANCE, TransactionType.BALANCE)
    private var installmentSelection: InstallmentSelection? = null
    private var installmentCardIdentity: String? = null
    private var installmentAcquirerId: String? = null
    private var installmentQueryResponse: String = ""
    private var installmentQueryReference: String = ""

    private fun installmentCardIdentity(card: CardReadResult): String? = extractPan(card)?.takeIf { it.isNotBlank() }?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
    }

    val cashbackAmount: String get() = ecrRequest?.cashback ?: "0.00"

    private var currentAmounts = PartialApprovalAmounts.fromStrings(
        base = (initialBaseAmount.toBigDecimal() + cashbackAmount.toBigDecimal()).toPlainString(),
        tax1 = initialTax1Discount.discountedTaxAmountText,
        tax1Discount = initialTax1Discount.discountAmountText,
        tax2 = initialTax2Amount,
        tip = initialTipAmount,
    )

    private val transactionInvoice = CardTransactionInvoice(InvoiceNumberProvider::nextInvoiceNumber)

    fun invoiceNumberForCardRead(): String = if (installmentQueryPending) "000000" else transactionInvoice.forCardRead()

    private var selectionJob: Job? = null
    private var activeCardData: CardReadResult? = null
    private var currentOptions: List<CardTransactionAcquirerOption> = emptyList()
    private var pendingEmvOnlineContext: EmvOnlineFlowCoordinator? = null
    private var partialApprovalDecision: CompletableDeferred<Boolean>? = null
    private var remainingTransactionDecision: CompletableDeferred<Boolean>? = null
    private var fallbackApprovedTransactionId: String? = null
    private val pendingAcquirerIds = MutableStateFlow<Set<String>>(emptySet())
    private val supportedTransactionTypes = setOf(
        TransactionType.SALE,
        TransactionType.REFUND,
        TransactionType.AUTHONLY,
        TransactionType.BALANCE,
        TransactionType.PAYMENT,
        TransactionType.CASH,
        TransactionType.LOYALTY_SALE,
        TransactionType.LOYALTY_BALANCE,
        TransactionType.QUOTA_SALE,
        TransactionType.EXTRAS_SALE,
        TransactionType.EXTRAS_BALANCE,
        TransactionType.CHECKIN,
        TransactionType.CHECKOUT,
        TransactionType.OFFLINE_PIN_CHANGE,
        TransactionType.PIN_UNBLOCK,
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
    private val pendingReversalReceiptPrinter = PendingReversalReceiptPrinter(
        context = GlobalConnectPaymentApplication.instance.applicationContext,
        profileRepository = profileRepository,
        tmsDatabase = tmsDatabase,
        paymentPrinter = paymentPrinter,
    )
    private val pendingReversalProcessor = PendingReversalProcessor(
        transactionRepository = transactionRepository,
        hostExecutor = { request -> hostClient.execute(request) },
    )
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
        Log.d(TAG, "start invoked transactionType=$transactionType totalAmount=${currentAmounts.total}")
        if (installmentQueryPending) {
            currentAmounts = PartialApprovalAmounts.fromStrings("0.00", "0.00", "0.00", "0.00", "0.00")
        }
        if ((installmentContract != null || transactionType == TransactionType.EXTRAS_BALANCE) && terminal?.enableInstallments != true) {
            showError(string(R.string.installment_disabled))
            return
        }
        if (!supportedTransactionTypes.contains(transactionType)) {
            _uiState.value = CardTransactionUiState(
                step = CardTransactionStep.Error(string(R.string.trans_error))
            )
            return
        }
        if (ecrRequest != null && when (transactionType) {
                TransactionType.REFUND -> terminal?.enableRefund != true
                TransactionType.PAYMENT -> terminal?.enablePayment != true
                TransactionType.CASH -> terminal?.enableCash != true
                TransactionType.CHECKIN, TransactionType.CHECKOUT -> terminal?.enableCheckInOut != true
                else -> false
            }) {
            showError(string(R.string.trans_error), "58"); return
        }
        if (ecrRequest != null && LoyaltyContract.isLoyalty(transactionType) && terminal?.enableLoyalty != true) {
            showError(string(R.string.trans_error), "58")
            return
        }
        stopWaitingForResponseCountdown()
        val requestedCurrency = ecrRequest?.currency
        val allCurrencies = CurrencyTable.build(tmsDatabase.Acquirer)
        val currencies = if(requestedCurrency == null) allCurrencies else allCurrencies.filter { it.currencyCode.toString().padStart(3,'0') == requestedCurrency }
        if(requestedCurrency != null && currencies.isEmpty()) {
            showError("Unsupported ECR currency", "30")
            return
        }
        Log.d(TAG, "start currencies=${currencies.size}")
        val baseState = CardTransactionUiState(
            baseAmount = currentAmounts.base.moneyText(),
            tax1Amount = currentAmounts.tax1.moneyText(),
            tax2Amount = currentAmounts.tax2.moneyText(),
            tipAmount = currentAmounts.tip.moneyText(),
            totalAmount = currentAmounts.total.moneyText(),
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

    fun onCardRead(
        result: CardReadResult,
        onlineResponseHandler: ((EmvOnlineAuthorizationResponse) -> Unit)? = null,
    ) {
        Log.d(
            TAG,
            "onCardRead slot=${result.slotType} maskedPan=${result.maskedCardNumber} track2Length=${result.track2?.length}",
        )
        if (!supportedTransactionTypes.contains(transactionType)) return
        if (cardDataQuery && onlineResponseHandler != null) {
            showError(string(R.string.installment_query_invalid))
            return
        }
        if (installmentContract != null && !installmentQueryPending &&
            (installmentSelection == null || installmentCardIdentity(result) != installmentCardIdentity)) {
            showError(string(R.string.installment_same_card))
            return
        }
        pendingEmvOnlineContext = onlineResponseHandler?.let(::EmvOnlineFlowCoordinator)
        activeCardData = result
        val resolvedOptions = resolveAcquirerOptions(result).filter { installmentQueryPending || installmentAcquirerId == null || it.acquirer.AcqID == installmentAcquirerId }
        val allOptions = if (transactionType == TransactionType.OFFLINE_PIN_CHANGE) {
            resolvedOptions.filter { option -> OfflinePinChangeContract.supportsAcquirer(option.acquirer) }
        } else {
            resolvedOptions
        }
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

    fun prepareAutomaticContactlessRetry(
        resultCode: Int?,
        cardSlot: com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum?,
        onlineAuthorizationPending: Boolean,
    ): Boolean {
        if (_uiState.value.step !is CardTransactionStep.AwaitingCard) return false
        if (!ContactlessReadRetryPolicy.shouldRetry(
                resultCode,
                cardSlot,
                onlineAuthorizationPending || pendingEmvOnlineContext != null || activeCardData != null,
            )) return false
        Log.i(TAG, "Recoverable contactless read failure $resultCode; scheduling reader restart")
        _uiState.update {
            it.copy(
                step = CardTransactionStep.ContactlessReadRetryPrompt,
                statusMessage = if (resultCode == one.globalconnect.paymentapp.cardreader.nexgo.NexgoSdkResult.Emv_Candidatelist_Empty) {
                    string(R.string.card_brand_not_supported)
                } else string(R.string.contactless_read_error),
                cardData = null,
            )
        }
        return true
    }

    fun continueAutomaticContactlessRetry() {
        if (_uiState.value.step is CardTransactionStep.ContactlessReadRetryPrompt) retry()
    }

    val contactlessAllowed: Boolean
        get() = !OfflinePinChangeContract.isPinMaintenance(transactionType) &&
            (!LoyaltyContract.isLoyalty(transactionType) || terminal?.ctlsLoyaltyEnabled == true)

    fun onEmvKernelCompleted(completion: EmvKernelCompletion) {
        val context = pendingEmvOnlineContext
        if (context == null) {
            Log.w(TAG, "Ignoring EMV kernel completion without a pending online authorization")
            return
        }
        if (LoyaltyContract.isLoyalty(transactionType) && terminal?.enableLoyalty != true) {
            _uiState.value = CardTransactionUiState(
                step = CardTransactionStep.Error(string(R.string.trans_error))
            )
            return
        }
        if (OfflinePinChangeContract.isPinMaintenance(transactionType)) {
            val availability = when (transactionType) {
                TransactionType.OFFLINE_PIN_CHANGE -> OfflinePinChangeContract.configurationAvailability(
                    terminal = terminal,
                    acquirers = tmsDatabase.Acquirer,
                )
                TransactionType.PIN_UNBLOCK -> {
                    OfflinePinChangeContract.pinUnblockConfigurationAvailability(terminal)
                }
                else -> OfflinePinChangeAvailability.AVAILABLE
            }
            if (availability != OfflinePinChangeAvailability.AVAILABLE) {
                val message = string(R.string.function_not_allowed)
                _uiState.value = CardTransactionUiState(
                    step = CardTransactionStep.Error(message),
                    statusMessage = message,
                )
                return
            }
        }
        Log.d(TAG, "onEmvKernelCompleted resultCode=${completion.resultCode}")
        context.complete(completion)
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

    val selectedInstallment: InstallmentSelection? get() = installmentSelection

    fun installmentAmountPromptConfig(): AmountPromptConfig =
        (TransactionConfigRegistry.amountPromptConfigFor(transactionType.toTransactionString(), terminal,
            tmsDatabase.Acquirer.filter { it.AcqID == installmentAcquirerId })
            ?: AmountPromptConfig(true, false, false, false)).copy(currencySymbol = _uiState.value.currencySymbol)

    fun selectInstallmentPlan(code: String) {
        val step = _uiState.value.step as? CardTransactionStep.SelectingInstallmentPlan ?: return
        val plan = step.plans.singleOrNull { it.code == code } ?: return
        _uiState.update { it.copy(step = CardTransactionStep.SelectingInstallmentCount(plan)) }
        if (ecrRequest?.installments != null) selectInstallmentCount(ecrRequest.installments!!) else startSelectionTimer()
    }

    fun selectInstallmentCount(count: Int) {
        val step = _uiState.value.step as? CardTransactionStep.SelectingInstallmentCount ?: return
        if (count !in step.plan.installments) return
        installmentSelection = InstallmentSelection(step.plan, count)
        _uiState.update { it.copy(step = CardTransactionStep.EnteringInstallmentAmounts) }
        if (ecrRequest != null) {
            submitInstallmentAmounts(ecrRequest.base, ecrRequest.tax1, ecrRequest.tax2, ecrRequest.tip)
        } else startSelectionTimer()
    }

    fun submitInstallmentAmounts(base: String, tax1: String, tax2: String, tip: String) {
        if (_uiState.value.step != CardTransactionStep.EnteringInstallmentAmounts || installmentSelection == null) return
        val discount = Tax1DiscountCalculator.calculate(tax1, terminal?.TaxDiscount ?: 0.0)
        val amounts = PartialApprovalAmounts.fromStrings(base, discount.discountedTaxAmountText,
            discount.discountAmountText, tax2, tip)
        if (amounts.total.signum() <= 0) return
        selectionJob?.cancel()
        prepareRemainingTransaction(amounts)
    }

    fun retry() {
        if (ecrRequest != null && EcrRuntime.sale.value == null) { cancelTransaction(); return }
        Log.d(TAG, "retry invoked")
        if (installmentContract != null && installmentSelection == null) {
            installmentQueryPending = true
            installmentCardIdentity = null
            installmentAcquirerId = null
            currentAmounts = PartialApprovalAmounts.fromStrings("0.00", "0.00", "0.00", "0.00", "0.00")
            start()
            return
        }
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

    /** Pauses before restarting a declined contactless operation as a new contact ICC transaction. */
    private fun prepareContactRetry() {
        Log.i(TAG, "Issuer requested contact ICC retry; showing contact-required prompt")
        activeCardData = null
        currentOptions = emptyList()
        stopWaitingForResponseCountdown()
        _uiState.update {
            it.copy(
                step = CardTransactionStep.ContactRetryPrompt,
                statusMessage = string(R.string.contact_retry_required),
                processingStatus = null,
                cardData = null,
                acquirerOptions = emptyList(),
                contactOnly = true,
            )
        }
    }

    /** Handles a First GEN AC request to leave contactless and start a new contact ICC transaction. */
    fun onKernelContactRetryRequired() {
        val state = _uiState.value
        if (state.step !is CardTransactionStep.AwaitingCard || state.contactOnly) return
        Log.i(TAG, "Contactless kernel requested contact ICC at First GEN AC")
        prepareContactRetry()
    }

    /** Starts the issuer-directed second attempt with contact ICC as the only enabled interface. */
    fun continueContactRetry() {
        if (_uiState.value.step !is CardTransactionStep.ContactRetryPrompt) return
        Log.i(TAG, "Starting new contact-only attempt with fresh EMV trace, STAN, and invoice counters")
        _uiState.update {
            it.copy(
                step = CardTransactionStep.AwaitingCard,
                statusMessage = string(R.string.contact_retry_insert_chip),
                processingStatus = null,
                cardData = null,
                acquirerOptions = emptyList(),
                contactOnly = true,
            )
        }
    }

    fun acceptPartialApproval() {
        partialApprovalDecision?.complete(true)
    }

    fun declinePartialApproval() {
        partialApprovalDecision?.complete(false)
    }

    fun startRemainingTransaction() {
        remainingTransactionDecision?.complete(true)
    }

    fun finishAfterPartialApproval() {
        remainingTransactionDecision?.complete(false)
    }

    fun cancelTransaction() {
        Log.d(TAG, "cancelTransaction invoked")
        when (_uiState.value.step) {
            is CardTransactionStep.PartialApproval -> {
                declinePartialApproval()
                return
            }
            is CardTransactionStep.PartialApprovalRemainder -> {
                finishAfterPartialApproval()
                return
            }
            else -> Unit
        }
        selectionJob?.cancel()
        stopWaitingForResponseCountdown()
        abortPendingEmvOnlineAuthorization()
        viewModelScope.launch {
            val approvedId = fallbackApprovedTransactionId
            if (approvedId != null) {
                Log.d(TAG, "Returning to previously approved partial transaction id=$approvedId")
                _events.emit(CardTransactionEvent.NavigateToResult(approvedId))
            } else {
                Log.d(TAG, "Emitting CardTransactionEvent.Cancelled")
                EcrRuntime.finish("UC", "Cancelled")
                _events.emit(CardTransactionEvent.Cancelled)
            }
        }
    }

    fun onSelectionTimeout() {
        Log.d(TAG, "onSelectionTimeout invoked")
        if (installmentContract != null) installmentSelection = null
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
        if (OfflinePinChangeContract.isPinMaintenance(transactionType)) {
            val isContactIcc = cardData.slotType == CardSlotTypeEnum.ICC1 ||
                cardData.slotType == CardSlotTypeEnum.ICC2
            val emvTags = cardData.emvTags.associate { tag -> tag.tag to tag.value }
            val validArqc = when (transactionType) {
                TransactionType.OFFLINE_PIN_CHANGE -> OfflinePinChangeContract.isValidArqcRequest(emvTags)
                TransactionType.PIN_UNBLOCK -> {
                    OfflinePinChangeContract.isValidPinUnblockArqcRequest(emvTags)
                }
                else -> false
            }
            if (!isContactIcc || currentAmounts.total.signum() != 0 || !validArqc) {
                val message = if (transactionType == TransactionType.PIN_UNBLOCK) {
                    R.string.pin_unblock_invalid_card_flow
                } else {
                    R.string.offline_pin_change_invalid_card_flow
                }
                showError(string(message))
                return
            }
            if (transactionType == TransactionType.OFFLINE_PIN_CHANGE &&
                !OfflinePinChangeContract.supportsAcquirer(option.acquirer)
            ) {
                showError(string(R.string.online_pin_error_no_selected_acquirer_pin_type))
                return
            }
        }
        if (cardData.onlinePinRequested && !option.acquirer.supportsOnlinePin) {
            showError(string(R.string.online_pin_error_no_selected_acquirer_pin_type))
            return
        }
        if (cardData.onlinePinRequested) {
            val expectedScheme = when (option.acquirer.pinKeyScheme) {
                TMS_PinKeyScheme.MKSK -> OnlinePinScheme.MKSK
                TMS_PinKeyScheme.DUKPT -> OnlinePinScheme.DUKPT
                TMS_PinKeyScheme.NONE -> null
            }
            val profileMatches = expectedScheme != null &&
                cardData.onlinePinScheme == expectedScheme &&
                cardData.onlinePinKeyIndex == option.acquirer.nexgoPinKeyIndex &&
                (cardData.onlinePinAcquirerIds.isEmpty() || option.acquirer.AcqID in cardData.onlinePinAcquirerIds)
            if (!profileMatches) {
                Log.e(
                    TAG,
                    "Online PIN profile does not match selected acquirer=${option.acquirer.AcqID} " +
                        "expectedScheme=$expectedScheme expectedIndex=${option.acquirer.nexgoPinKeyIndex} " +
                        "actualScheme=${cardData.onlinePinScheme} actualIndex=${cardData.onlinePinKeyIndex}",
                )
                showError(string(R.string.online_pin_error_no_selected_acquirer_pin_type))
                return
            }
        }
        val emvOnlineContext = pendingEmvOnlineContext
        viewModelScope.launch {
            Log.d(TAG, "processTransaction coroutine started")
            if (ecrRequest != null && transactionType == TransactionType.CHECKOUT) {
                val candidates = transactionRepository.getAllTransactionsStream().first().filter {
                    it.type == TransactionType.CHECKIN && it.returnStatus == ReturnStatus.None &&
                        it.folioNumber == folioNumber && it.acquirerId == option.acquirer.AcqID &&
                        (ecrRequest.message.fields["65"] == null || it.invoiceId.trimStart('0') == ecrRequest.message.fields["65"]!!.trimStart('0'))
                }
                val original = candidates.singleOrNull()
                if (original == null || original.hashed_cardNumber != EncryptionUtil.hashPAN(extractPan(cardData).orEmpty())) {
                    showError(string(R.string.ecr_hotel_reference_invalid), "30"); return@launch
                }
                checkInRowId = original.id
                originalTransactionId = original.transactionId
            }
            val effectiveCardData = if (transactionType == TransactionType.OFFLINE_PIN_CHANGE) {
                captureOfflinePinChangeData(option, cardData) ?: return@launch
            } else {
                cardData
            }
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
                val invoiceId = if (installmentQueryPending) "000000" else transactionInvoice.forHostRequest()
                Log.d(TAG, "processTransaction stan=$stan invoice=$invoiceId")
                val procInfo = buildProcInfo(option, effectiveCardData, stan, invoiceId)

                val transactionConfig = TransactionConfigRegistry.configFor(procInfo.TransLog.TxnType)
                needsReversal = transactionConfig?.hasAttribute(TransactionAttribute.NEEDS_REVERSAL) == true

                val message = try {
                    hostMessageBuilder.build(
                        acquirer = option.acquirer,
                        terminal = terminalConfig,
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
                        val candidate = reversalCandidate
                        if (needsReversal && candidate != null && queuedReversal == null) {
                            try {
                                queuedReversal = queuePendingReversal(candidate)
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
                if (installmentQueryPending) {
                    if (responseCode != "00") {
                        showError(HostResponseMessageResolver.resolveOrFallback(responseCode), responseCode ?: "96")
                        return@launch
                    }
                    val plans = runCatching {
                        requireNotNull(installmentContract).parsePlans(
                            PrivateUseData63.parse(result.isoMessage.getFieldValue(63))["46"].orEmpty())
                    }.getOrNull()?.filter { plan -> ecrRequest?.installments?.let { it in plan.installments } ?: true }
                    val identity = installmentCardIdentity(cardData)
                    val queryReference = result.isoMessage.getFieldValue(37).orEmpty().trim()
                    if (plans != null && plans.isEmpty() && ecrRequest?.installments != null) {
                        showError(string(R.string.installment_count_unavailable), "58")
                        return@launch
                    }
                    if (plans.isNullOrEmpty() || identity == null || queryReference.length != 12) {
                        showError(string(R.string.installment_query_invalid))
                        return@launch
                    }
                    installmentQueryResponse = PrivateUseData63.parse(result.isoMessage.getFieldValue(63))["46"].orEmpty()
                    installmentQueryReference = queryReference
                    installmentCardIdentity = identity
                    installmentAcquirerId = option.acquirer.AcqID
                    installmentQueryPending = false
                    activeCardData = null
                    currentOptions = emptyList()
                    pendingEmvOnlineContext = null
                    stopWaitingForResponseCountdown()
                    _uiState.update { it.copy(
                        step = if (plans.size == 1) CardTransactionStep.SelectingInstallmentCount(plans.single())
                            else CardTransactionStep.SelectingInstallmentPlan(plans),
                        processingStatus = null, cardData = null,
                        statusMessage = string(R.string.installment_select_count)) }
                    if (plans.size == 1 && ecrRequest?.installments != null) {
                        selectInstallmentCount(ecrRequest.installments!!)
                    } else startSelectionTimer()
                    return@launch
                }

                Log.d(TAG, "processTransaction responseCode=$responseCode")
                val partialAllocation = if (
                    responseCode == PartialApprovalContract.RESPONSE_CODE &&
                    PartialApprovalContract.isSupported(transactionType) && installmentContract == null
                ) {
                    PartialApprovalContract.parseApprovedAmount(result.isoMessage.getFieldValue(4))
                        ?.let { approvedAmount ->
                            PartialApprovalContract.allocate(currentAmounts, approvedAmount)
                        }
                } else {
                    null
                }
                partialAllocation?.let { allocation ->
                    val approvedAmountText = allocation.approved.total.moneyText()
                    val approvedField4 = result.isoMessage.getFieldValue(4)
                        ?: approvedAmountText.replace(".", "").padStart(12, '0')
                    reversalCandidate = reversalCandidate?.withReversalAmount(
                        field4 = approvedField4,
                        amountText = approvedAmountText,
                    )
                    queuedReversal = queuedReversal?.withReversalAmount(
                        field4 = approvedField4,
                        amountText = approvedAmountText,
                    )
                }
                val hostApproved = partialAllocation != null || OfflinePinChangeContract.isHostApproved(
                    transactionType = transactionType,
                    responseCode = responseCode,
                )
                val responseField55 = result.isoMessage.getFieldValue(55)
                val issuerResponseValid = !hostApproved || when (transactionType) {
                    TransactionType.OFFLINE_PIN_CHANGE -> {
                        OfflinePinChangeContract.hasRequiredIssuerResponse(responseField55)
                    }
                    TransactionType.PIN_UNBLOCK -> {
                        OfflinePinChangeContract.hasRequiredPinUnblockIssuerResponse(responseField55)
                    }
                    else -> true
                }
                val kernelCompletion = if (!issuerResponseValid) {
                    val message = if (transactionType == TransactionType.PIN_UNBLOCK) {
                        R.string.pin_unblock_invalid_issuer_response
                    } else {
                        R.string.offline_pin_change_invalid_issuer_response
                    }
                    EmvKernelCompletion(
                        resultCode = SdkResult.Fail,
                        message = string(message),
                        cardData = null,
                    )
                } else emvOnlineContext?.let { context ->
                    submitEmvOnlineResponseAndAwaitCompletion(
                        context = context,
                        response = EmvOnlineAuthorizationResponse(
                            hostReachable = true,
                            responseCode = responseCode ?: "96",
                            authorizationCode = result.isoMessage.getFieldValue(38),
                            field55 = responseField55,
                        ),
                    )
                }
                val completionTags = kernelCompletion?.cardData?.emvTags
                    ?.associate { tag -> tag.tag to tag.value }
                val expectedSuccessfulAac = completionTags
                    ?.let { tags ->
                        OfflinePinChangeContract.isExpectedSuccessfulAacCompletion(
                            transactionType = transactionType,
                            responseCode = responseCode,
                            tags = tags,
                        )
                    } == true
                val kernelApproved = kernelCompletion?.resultCode == SdkResult.Success ||
                    expectedSuccessfulAac
                if (expectedSuccessfulAac) {
                    Log.i(
                        TAG,
                        "PIN maintenance completed with expected AAC after successful issuer script processing",
                    )
                }
                val approved = hostApproved && issuerResponseValid &&
                    (kernelCompletion == null || kernelApproved)
                val cardDeclinedAfterOnlineApproval = isCardDeclinedAfterOnlineApproval(
                    hostApproved = hostApproved,
                    kernelApproved = kernelApproved,
                    kernelResultCode = kernelCompletion?.resultCode,
                    cryptogramInformationData = completionTags?.get("9F27"),
                )
                val shouldPersistReversal =
                    needsReversal && (
                        responseCode == "91" || responseCode == "96" || responseCode == "92" ||
                            (responseCode == PartialApprovalContract.RESPONSE_CODE && partialAllocation == null) ||
                            (hostApproved && kernelCompletion != null && !kernelApproved)
                        ) &&
                        reversalCandidate != null
                val responseMessage = when {
                    cardDeclinedAfterOnlineApproval && shouldPersistReversal -> {
                        string(R.string.sale_error_emv_chip_declined_reversal)
                    }
                    cardDeclinedAfterOnlineApproval -> {
                        string(R.string.sale_error_emv_chip_declined)
                    }
                    hostApproved && kernelCompletion != null && !kernelApproved -> {
                        kernelCompletion.message.ifBlank { string(R.string.err_comm_error) }
                    }
                    else -> HostResponseMessageResolver.resolveOrFallback(responseCode)
                }
                updateProcInfoWithResponse(procInfo, result)

                if (ContactRetryContract.shouldRetryUsingContact(responseCode, cardData.slotType)) {
                    setProcessingResult(ProcessingStatusStepState.FAILED, responseMessage)
                    if (queuedReversal != null) {
                        try {
                            clearPendingReversal(queuedReversal!!)
                            Log.d(
                                TAG,
                                "Cleared pending reversal before issuer-directed contact retry id=${queuedReversal?.id}",
                            )
                        } catch (error: Throwable) {
                            Log.e(TAG, "Unable to clear pending reversal before contact retry", error)
                        } finally {
                            queuedReversal = null
                        }
                    }
                    prepareContactRetry()
                    return@launch
                }

                if (approved && partialAllocation != null) {
                    val acceptsPartial = awaitPartialApprovalDecision(partialAllocation)
                    if (!acceptsPartial) {
                        val declineMessage = string(R.string.host_result_partial_declined_user)
                        setProcessingResult(ProcessingStatusStepState.FAILED, declineMessage)
                        if (needsReversal && reversalCandidate != null) {
                            reversalContext?.let { context ->
                                val reversalForApprovedAmount = queuedReversal ?: reversalCandidate!!
                                recordPendingReversal(
                                    acquirer = context.acquirer,
                                    ipProfile = context.ipProfile,
                                    terminal = context.terminal,
                                    hostSettings = context.hostSettings,
                                    isoFactory = isoFactory,
                                    candidate = reversalForApprovedAmount,
                                    existing = reversalForApprovedAmount.takeIf { it.id != 0L },
                                    reason = ReversalReason.USER_DECLINED_PARTIAL,
                                    responseCode = responseCode,
                                )
                            }
                        }
                        Log.i(
                            TAG,
                            "Partial approval declined; reversal attempted for ${partialAllocation.approved.total}",
                        )
                        showError(declineMessage)
                        return@launch
                    }

                    procInfo.TransLog.PartialApprovalOriginalAmount = partialAllocation.original.total.moneyText()
                    applyAmountsToProcInfo(procInfo, partialAllocation.approved)
                    setProcessingResult(
                        ProcessingStatusStepState.COMPLETED,
                        string(R.string.partial_approve_update_notif),
                    )
                    val writesActiveRecord = transactionConfig
                        ?.hasAttribute(TransactionAttribute.WRITES_RECORD)
                        ?: true
                    val destinationId = persistApprovedTransaction(procInfo, writesActiveRecord)
                    if (needsReversal && queuedReversal != null) {
                        runCatching { clearPendingReversal(queuedReversal!!) }
                            .onFailure { error -> Log.e(TAG, "Unable to clear partial approval reversal", error) }
                        queuedReversal = null
                    }
                    fallbackApprovedTransactionId = destinationId

                    val processRemaining = awaitRemainingTransactionDecision(partialAllocation.remaining)
                    if (processRemaining) {
                        prepareRemainingTransaction(partialAllocation.remaining)
                    } else {
                        _events.emit(CardTransactionEvent.NavigateToResult(destinationId))
                    }
                    return@launch
                }

                if (approved) {
                    setProcessingResult(ProcessingStatusStepState.COMPLETED, responseMessage)
                    val writesActiveRecord = transactionConfig
                        ?.hasAttribute(TransactionAttribute.WRITES_RECORD)
                        ?: true
                    val destinationId = persistApprovedTransaction(procInfo, writesActiveRecord)
                    viewModelScope.launch {
                        _events.emit(CardTransactionEvent.NavigateToResult(destinationId))
                    }
                } else {
                    setProcessingResult(ProcessingStatusStepState.FAILED, responseMessage)
                    Log.w(
                        TAG,
                        "Host declined transaction responseCode=$responseCode message=$responseMessage",
                    )
                    showError(responseMessage, responseCode ?: "96")
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
                                reason = reversalReasonForCode(
                                    if (hostApproved && kernelCompletion != null && !kernelApproved) "96" else responseCode
                                ),
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
                if (emvOnlineContext != null && !emvOnlineContext.responseSent) {
                    submitEmvOnlineResponseAndAwaitCompletion(
                        context = emvOnlineContext,
                        response = EmvOnlineAuthorizationResponse(
                            hostReachable = false,
                            responseCode = "91",
                        ),
                    )
                }
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
            } finally {
                if (pendingEmvOnlineContext === emvOnlineContext) {
                    pendingEmvOnlineContext = null
                }
            }
        }
    }

    /** Captures and confirms the new PIN using the PIN method configured on [option]'s acquirer. */
    private suspend fun captureOfflinePinChangeData(
        option: CardTransactionAcquirerOption,
        cardData: CardReadResult,
    ): CardReadResult? {
        val scheme = when (option.acquirer.pinKeyScheme) {
            TMS_PinKeyScheme.MKSK -> OnlinePinScheme.MKSK
            TMS_PinKeyScheme.DUKPT -> OnlinePinScheme.DUKPT
            TMS_PinKeyScheme.NONE -> null
        }
        val keyIndex = option.acquirer.nexgoPinKeyIndex
        val pan = extractPan(cardData)
        if (scheme == null || keyIndex == null || pan.isNullOrBlank()) {
            showError(string(R.string.online_pin_error_no_selected_acquirer_pin_type))
            return null
        }

        _uiState.update { state ->
            state.copy(statusMessage = string(R.string.pin_change_enter_new_pin))
        }
        return when (
            val capture = nexgoApi.captureNewPinAndConfirmation(
                pan = pan,
                scheme = scheme,
                keyIndex = keyIndex,
                compatibleAcquirerIds = setOf(option.acquirer.AcqID),
            )
        ) {
            is PinChangeCaptureResult.Success -> {
                _uiState.update { state ->
                    state.copy(statusMessage = string(R.string.sale_host_processing))
                }
                cardData.copy(
                    pinBlock = capture.pinBlock,
                    ksn = capture.ksn.takeIf { it.isNotBlank() },
                    onlinePinScheme = capture.scheme,
                    onlinePinKeyIndex = capture.keyIndex,
                    onlinePinAcquirerIds = capture.compatibleAcquirerIds,
                    pinChangePinConfirmed = true,
                )
            }
            is PinChangeCaptureResult.Failure -> {
                Log.w(
                    TAG,
                    "New-PIN capture failed acquirer=${option.acquirer.AcqID} reason=${capture.reason}",
                )
                showError(pinChangeCaptureFailureMessage(capture.reason))
                null
            }
        }
    }

    /** Maps a classified secure PIN-capture failure to an operator-safe message. */
    private fun pinChangeCaptureFailureMessage(reason: PinChangeCaptureFailure): String = when (reason) {
        PinChangeCaptureFailure.PIN_MISMATCH -> string(R.string.offline_pin_change_pin_mismatch)
        PinChangeCaptureFailure.FIRST_ENTRY_CANCELED,
        PinChangeCaptureFailure.CONFIRMATION_CANCELED,
        PinChangeCaptureFailure.FIRST_ENTRY_BYPASSED,
        PinChangeCaptureFailure.CONFIRMATION_BYPASSED,
        -> string(R.string.offline_pin_change_pin_entry_canceled)
        PinChangeCaptureFailure.FIRST_ENTRY_TIMED_OUT,
        PinChangeCaptureFailure.CONFIRMATION_TIMED_OUT,
        -> string(R.string.offline_pin_change_pin_entry_timeout)
        PinChangeCaptureFailure.KSN_CHANGED,
        PinChangeCaptureFailure.KSN_INCREMENT_FAILED,
        -> string(R.string.offline_pin_change_ksn_error)
        PinChangeCaptureFailure.INVALID_CONFIGURATION,
        PinChangeCaptureFailure.FIRST_ENTRY_FAILED,
        PinChangeCaptureFailure.CONFIRMATION_FAILED,
        -> string(R.string.offline_pin_change_new_pin_unavailable)
    }

    private suspend fun submitEmvOnlineResponseAndAwaitCompletion(
        context: EmvOnlineFlowCoordinator,
        response: EmvOnlineAuthorizationResponse,
    ): EmvKernelCompletion {
        context.submitResponse(response)

        return withTimeoutOrNull(EMV_KERNEL_COMPLETION_TIMEOUT_MS) {
            context.completion.await()
        } ?: EmvKernelCompletion(
            resultCode = SdkResult.Fail,
            message = string(R.string.host_result_timeout),
            cardData = null,
        )
    }

    private fun abortPendingEmvOnlineAuthorization() {
        val context = pendingEmvOnlineContext ?: return
        if (!context.responseSent) {
            runCatching {
                context.submitResponse(
                    EmvOnlineAuthorizationResponse(
                        hostReachable = false,
                        responseCode = "96",
                    )
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to abort pending EMV online authorization", error)
            }
        }
        pendingEmvOnlineContext = null
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
        transLog.TxnType = if (installmentQueryPending) requireNotNull(installmentContract).queryCode(transactionType)
            else transactionType.toTransactionString()
        installmentContract?.let { contract ->
            transLog.PaymentPlan = if (installmentQueryPending) contract.queryField45(transactionType)
                else contract.saleField45(transactionType, requireNotNull(installmentSelection))
            if (!installmentQueryPending) {
                transLog.PaymentPlanQueryResponse = installmentQueryResponse
                transLog.RefNbr = installmentQueryReference
            }
        }
        if (transactionType == TransactionType.EXTRAS_BALANCE) {
            transLog.PaymentPlan = requireNotNull(InstallmentContracts.extrasBalanceRequest)
        }
        transLog.AccType = "Credit"
        transLog.AcquirerId = option.acquirer.AcqID
        transLog.IssuerId = option.issuer.IssuID
        transLog.CardRangeId = option.cardRange?.CardRangeID ?: ""
        transLog.CardRangeName = option.cardRange?.RangeName ?: ""
        transLog.CurrCode = sysParam.CurrCode
        transLog.TxnAmt = currentAmounts.total.moneyText()
        transLog.BaseAmt = (currentAmounts.base - cashbackAmount.toBigDecimal()).moneyText()
        transLog.CashbackAmt = cashbackAmount
        transLog.Tax1Amt = currentAmounts.tax1.moneyText()
        transLog.Tax1DiscountAmt = currentAmounts.tax1Discount.moneyText()
        transLog.OriginalTax1Amt = if (currentAmounts.tax1Discount.signum() > 0) {
            currentAmounts.tax1.add(currentAmounts.tax1Discount).moneyText()
        } else {
            ""
        }
        transLog.Tax2Amt = currentAmounts.tax2.moneyText()
        transLog.TipAmt = currentAmounts.tip.moneyText()
        transLog.InvoiceId = invoiceId
        transLog.TxnId = stan
        transLog.AuthNtwkName = option.acquirer.AcquirerName
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
        transLog.PINBlock = cardData.pinBlock.orEmpty()
        transLog.KSN = cardData.ksn.orEmpty()

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
        transLog.AID = emvTags["4F"]?.value ?: emvTags["84"]?.value ?: ""
        transLog.CardType = CardBrandResolver.resolve(transLog.AID, pan)
        transLog.TVR = emvTags["95"]?.value ?: ""
        transLog.TSI = emvTags["9B"]?.value ?: ""
        transLog.AC = emvTags["9F26"]?.value ?: ""
        transLog.Cryptogram = transLog.AC
        transLog.ATC = emvTags["9F36"]?.value ?: ""
        transLog.IAD = emvTags["9F10"]?.value ?: ""
        transLog.CryptoInfo = emvTags["9F27"]?.value ?: ""
        val applicationLabel = resolveEmvApplicationName(
            preferredNameHex = emvTags["9F12"]?.value,
            applicationLabelHex = emvTags["50"]?.value,
            issuerFallback = option.issuer.IssuerName,
        )
        transLog.AppName = applicationLabel
        transLog.AppLabel = applicationLabel
        transLog.AppId = transLog.AID
        transLog.CVMResult = emvTags["9F34"]?.value.orEmpty()
        transLog.CardhdrName = resolveEmvCardholderName(
            cardholderNameHex = emvTags["5F20"]?.value,
            track1 = cardData.track1,
        )
        transLog.CVMText = when {
            transactionType == TransactionType.PIN_UNBLOCK -> "No CVM; PIN unblock issuer script"
            cardData.pinChangePinConfirmed -> "Offline PIN verified; new PIN confirmed"
            interfaceCode == "03" || interfaceCode == "04" ->
                resolveKernelCvmText(cardData.kernelCvmResult) ?: resolveEmvCvmText(
                    cvmResults = transLog.CVMResult,
                    onlinePinRequested = cardData.onlinePinRequested,
                )
            cardData.onlinePinRequested -> CVM_TEXT_ONLINE_PIN
            else -> CVM_TEXT_NOT_PERFORMED
        }
        transLog.TipProcessingInfo = option.acquirer.TIPProcs.toString()

        return ProcInfo(TransLog = transLog)
    }

    private suspend fun awaitPartialApprovalDecision(
        allocation: PartialApprovalAllocation,
    ): Boolean {
        if (cashbackAmount.toBigDecimal().signum() > 0) return false
        val decision = CompletableDeferred<Boolean>()
        partialApprovalDecision = decision
        _uiState.update { current ->
            current.copy(
                step = CardTransactionStep.PartialApproval(
                    requestedAmount = allocation.original.total.moneyText(),
                    approvedAmount = allocation.approved.total.moneyText(),
                    remainingAmount = allocation.remaining.total.moneyText(),
                ),
                statusMessage = string(R.string.partial_approval_title),
            )
        }
        return try {
            decision.await()
        } finally {
            if (partialApprovalDecision === decision) partialApprovalDecision = null
        }
    }

    private suspend fun awaitRemainingTransactionDecision(
        remaining: PartialApprovalAmounts,
    ): Boolean {
        if (ecrRequest != null) return false
        val decision = CompletableDeferred<Boolean>()
        remainingTransactionDecision = decision
        _uiState.update { current ->
            current.copy(
                step = CardTransactionStep.PartialApprovalRemainder(
                    remainingAmount = remaining.total.moneyText(),
                ),
                statusMessage = string(R.string.partial_approval_remaining_question),
            )
        }
        return try {
            decision.await()
        } finally {
            if (remainingTransactionDecision === decision) remainingTransactionDecision = null
        }
    }

    private fun prepareRemainingTransaction(remaining: PartialApprovalAmounts) {
        currentAmounts = remaining.normalized()
        activeCardData = null
        currentOptions = emptyList()
        pendingEmvOnlineContext = null
        stopWaitingForResponseCountdown()
        _uiState.update { current ->
            current.copy(
                step = CardTransactionStep.AwaitingCard,
                statusMessage = string(R.string.partial_approval_present_card_remaining),
                processingStatus = null,
                cardData = null,
                acquirerOptions = emptyList(),
                baseAmount = currentAmounts.base.moneyText(),
                tax1Amount = currentAmounts.tax1.moneyText(),
                tax2Amount = currentAmounts.tax2.moneyText(),
                tipAmount = currentAmounts.tip.moneyText(),
                totalAmount = currentAmounts.total.moneyText(),
            )
        }
    }

    private fun applyAmountsToProcInfo(procInfo: ProcInfo, amounts: PartialApprovalAmounts) {
        procInfo.TransLog.TxnAmt = amounts.total.moneyText()
        procInfo.TransLog.BaseAmt = (amounts.base - cashbackAmount.toBigDecimal()).moneyText()
        procInfo.TransLog.Tax1Amt = amounts.tax1.moneyText()
        procInfo.TransLog.Tax1DiscountAmt = amounts.tax1Discount.moneyText()
        procInfo.TransLog.OriginalTax1Amt = if (amounts.tax1Discount.signum() > 0) {
            amounts.tax1.add(amounts.tax1Discount).moneyText()
        } else {
            ""
        }
        procInfo.TransLog.Tax2Amt = amounts.tax2.moneyText()
        procInfo.TransLog.TipAmt = amounts.tip.moneyText()
        procInfo.TransLog.TxnResultMsg = string(R.string.msg_approved)
        procInfo.TransLog.RspText = procInfo.TransLog.TxnResultMsg
    }

    private suspend fun persistApprovedTransaction(
        procInfo: ProcInfo,
        writesActiveRecord: Boolean,
    ): String {
        val transaction = procInfo.toTransaction().apply { posTransactionId = ecrRequest?.id.orEmpty() }
        val destinationId: String
        val reportTransaction: Transaction
        if (writesActiveRecord) {
            Log.d(TAG, "processTransaction approved inserting active transaction")
            val rowId = transactionRepository.insert(transaction)
            destinationId = rowId.toString()
            reportTransaction = transaction.copy(id = rowId.toInt())
        } else {
            Log.i(
                TAG,
                "Approved ${transaction.type} is non-batch; skipping active transaction insert",
            )
            destinationId = TransientTransactionResultStore.store(transaction)
            reportTransaction = transaction
        }
        if (ecrRequest != null) EcrRuntime.approved(reportTransaction, procInfo.TransLog.BatchId.orEmpty(), destinationId)
        TransactionReportBridge.reportTransaction(
            context = GlobalConnectPaymentApplication.instance,
            transaction = reportTransaction,
            tmsDatabase = tmsDatabase,
        )
        if (writesActiveRecord && transaction.type == TransactionType.CHECKOUT) {
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
        return destinationId
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
        if (transactionType in setOf(TransactionType.EXTRAS_BALANCE, TransactionType.BALANCE)) {
            // Blank means unavailable, not a zero balance. Keep tag 29 as fallback.
            procInfo.TransLog.TxnAmt = ExtrasBalance.parse(result.isoMessage.getFieldValue(4)).orEmpty()
        }
        if (transactionType == TransactionType.LOYALTY_BALANCE) {
            procInfo.TransLog.LoyaltyBalancePoints =
                LoyaltyContract.parseBalancePoints(result.isoMessage.getFieldValue(4)).orEmpty()
        }
        procInfo.TransLog.ARC = responseCode ?: procInfo.TransLog.ARC
        procInfo.TransLog.RspDT = result.timestamp.toString()
        procInfo.TransLog.TxnResultMsg = if (
            responseCode == PartialApprovalContract.RESPONSE_CODE ||
            OfflinePinChangeContract.isHostApproved(transactionType, responseCode)
        ) {
            string(R.string.msg_approved)
        } else {
            string(R.string.msg_declined)
        }
        procInfo.TransLog.RspText = procInfo.TransLog.TxnResultMsg

        val privateUseTags = PrivateUseData63.parse(result.isoMessage.getFieldValue(63))
        privateUseTags["22"]?.let { procInfo.TransLog.AlternateHostResponse = it }
        privateUseTags["29"]?.let { procInfo.TransLog.AdditionalHostPrintData = it }
        // Keep the plan catalogue used for selection as the durable receipt snapshot.
        if (procInfo.TransLog.PaymentPlanQueryResponse.isNullOrBlank()) {
            privateUseTags["46"]?.let { procInfo.TransLog.PaymentPlanQueryResponse = it }
        }
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
            TransactionType.BALANCE -> acquirer.EnableBalance
            TransactionType.CHECKIN, TransactionType.CHECKOUT -> acquirer.enableCheckInOut
            TransactionType.REFUND -> acquirer.enableRefund
            TransactionType.PAYMENT -> acquirer.EnablePayment
            TransactionType.CASH -> acquirer.EnableCash
            TransactionType.LOYALTY_SALE,
            TransactionType.LOYALTY_BALANCE -> acquirer.enableLoyalty
            TransactionType.EXTRAS_BALANCE -> InstallmentContracts.extrasBalanceRequest != null && acquirer.enableInstallments
            TransactionType.QUOTA_SALE, TransactionType.EXTRAS_SALE ->
                if (installmentContract != null) acquirer.enableInstallments && (!isFallback || acquirer.AllowFallBack) else true
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
        return pendingReversalProcessor.processForAcquirer(
            acquirer = acquirer,
            ipProfile = ipProfile,
            terminal = terminal,
            hostSettings = hostSettings,
            isoFactory = isoFactory,
            onApproved = pendingReversalReceiptPrinter::print,
        ).allSent
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

    private fun PendingReversal.withReversalAmount(
        field4: String,
        amountText: String,
    ): PendingReversal = copy(
        fieldValues = fieldValues + (4 to field4),
        transactionAmount = amountText,
    )

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
            paymentPlanQueryResponse = procInfo.TransLog.PaymentPlanQueryResponse.orEmpty(),
        )
    }

    private fun reversalReasonForCode(code: String?): ReversalReason = when (code) {
        "91" -> ReversalReason.RESPONSE_91
        "96" -> ReversalReason.RESPONSE_96
        else -> ReversalReason.UNKNOWN
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

    private fun showError(message: String, ecrCode: String = "96") {
        EcrRuntime.finish(ecrCode, message)
        Log.w(TAG, "Transaction failed: $message")
        abortPendingEmvOnlineAuthorization()
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
        private const val EMV_KERNEL_COMPLETION_TIMEOUT_MS = 30_000L
    }
}

internal class EmvOnlineFlowCoordinator(
    private val responseHandler: (EmvOnlineAuthorizationResponse) -> Unit,
) {
    val completion = CompletableDeferred<EmvKernelCompletion>()
    var responseSent: Boolean = false
        private set

    fun submitResponse(response: EmvOnlineAuthorizationResponse) {
        if (responseSent) return
        responseSent = true
        try {
            responseHandler(response)
        } catch (error: Throwable) {
            responseSent = false
            throw error
        }
    }

    fun complete(kernelCompletion: EmvKernelCompletion): Boolean {
        return completion.complete(kernelCompletion)
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
    object ContactRetryPrompt : CardTransactionStep()
    object ContactlessReadRetryPrompt : CardTransactionStep()
    data class SelectingCurrency(val currencies: List<CurrencyInfo>) : CardTransactionStep()
    data class SelectingAcquirer(val options: List<CardTransactionAcquirerOption>) : CardTransactionStep()
    data class SelectingInstallmentPlan(val plans: List<InstallmentPlan>) : CardTransactionStep()
    data class SelectingInstallmentCount(val plan: InstallmentPlan) : CardTransactionStep()
    object EnteringInstallmentAmounts : CardTransactionStep()
    object ProcessingHost : CardTransactionStep()
    data class PartialApproval(
        val requestedAmount: String,
        val approvedAmount: String,
        val remainingAmount: String,
    ) : CardTransactionStep()
    data class PartialApprovalRemainder(val remainingAmount: String) : CardTransactionStep()
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
    val contactOnly: Boolean = false,
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
