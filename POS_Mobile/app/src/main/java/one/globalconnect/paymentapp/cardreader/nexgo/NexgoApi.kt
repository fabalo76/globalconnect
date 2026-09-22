package one.globalconnect.paymentapp.cardreader.nexgo

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexgo.common.ByteUtils
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.pinpad.AlgorithmModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinKeyboardModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinPadTypeEnum
import com.nexgo.oaf.apiv3.device.reader.CardInfoEntity
import com.nexgo.oaf.apiv3.device.reader.CardReader
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import com.nexgo.oaf.apiv3.emv.AidEntity
import com.nexgo.oaf.apiv3.emv.AidEntryModeEnum
import com.nexgo.oaf.apiv3.emv.CapkEntity
import com.nexgo.oaf.apiv3.emv.EmvDataSourceEnum
import com.nexgo.oaf.apiv3.emv.EmvEntryModeEnum
import com.nexgo.oaf.apiv3.emv.EmvHandler2
import com.nexgo.oaf.apiv3.emv.EmvProcessFlowEnum
import com.nexgo.oaf.apiv3.emv.EmvProcessResultEntity
import com.nexgo.oaf.apiv3.emv.EmvTransConfigurationEntity
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.R
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashSet

/**
 * Kotlin port of the Nexgo EMV orchestration layer. The class exposes a small
 * facade that mirrors the behaviour expected by the legacy .NET integration so
 * the existing business logic can be brought across incrementally.
 */
class NexgoApi(
    private val context: Context = GlobalConnectPaymentApplication.instance,
    private val deviceEngine: DeviceEngine = GlobalConnectPaymentApplication.deviceEngine,
) : PinChangeCaptureGateway {

    private val cardReader: CardReader = deviceEngine.cardReader
    private val pinPad = deviceEngine.pinPad
    private val beeper = CardReaderBeeper(deviceEngine.beeper)
    private val ledController = CardReaderLedController(deviceEngine.ledDriver)
    private val pinChangeCaptureOrchestrator = PinChangeCaptureOrchestrator(this)
    private val pinChangeKsnPreferences = context.getSharedPreferences(
        PIN_CHANGE_KSN_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private val cardInfoListener = CardInfoListener()
    private val emvProcessListener = EmvProcessListener()

    private var currentRequest: EmvTransactionRequest? = null
    private var currentEmvConfig: EmvTransConfigurationEntity? = null
    private var currentCardInfo: CardInfoEntity? = null
    @Volatile private var currentCardSlot: CardSlotTypeEnum? = null
    private var mobileCvmContinuationAvailable = false
    private var mobileCvmContinuationInProgress = false
    private var contactlessReadAcknowledged = false
    private val transactionRunning = AtomicBoolean(false)
    val isTransactionRunning: Boolean get() = transactionRunning.get()
    private var contactlessDiscoverCard = false
    private val applicationSelectionLock = Any()
    private var pendingApplicationCount = 0

    var transactionListener: EmvTransactionListener? = null
    var pinEntryHandler: ((pan: String, isOnlinePin: Boolean) -> Unit)? = null

    init {
        Log.d(TAG, "NexgoApi init starting")
        emvHandler = deviceEngine.getEmvHandler2("app2")
        emvHandler?.emvDebugLog(false)
        //Log.d(TAG, "EMV_STEP debugLog enabled=true")

        pinPad.initPinPad(PinPadTypeEnum.INTERNAL)
        pinPad.setAlgorithmMode(AlgorithmModeEnum.DUKPT)
        pinPad.setPinKeyboardMode(PinKeyboardModeEnum.FIXED)
        recoverReservedDukptKsn()

        setupListeners()
        applyStoredCapkList()
        applyStoredAidList()
        ledController.enterIdle()
        Log.d(TAG, "NexgoApi init completed")
    }

    fun startTransaction(request: EmvTransactionRequest) {
        Log.d(
            TAG,
            "startTransaction amount=${request.amount} cashback=${request.cashbackAmount} timeout=${request.timeoutSeconds}",
        )
        if (!transactionRunning.compareAndSet(false, true)) {
            Log.d(TAG, "startTransaction already running")
            transactionListener?.onError("Transaction already running")
            return
        }

        contactlessDiscoverCard = false
        currentRequest = request
        currentEmvConfig = null
        currentCardInfo = null
        currentCardSlot = null
        mobileCvmContinuationAvailable = false
        mobileCvmContinuationInProgress = false
        contactlessReadAcknowledged = false
        clearPendingApplicationSelection()
        pinData.clear()


        ledController.prepareForTransaction(
            allowContactless = request.allowContactless,
            allowSwipe = request.allowSwipe,
        )

        val slotTypes = HashSet<CardSlotTypeEnum>().apply {
            if (request.allowSwipe) add(CardSlotTypeEnum.SWIPE)
            if (request.allowContact) add(CardSlotTypeEnum.ICC1)
            if (request.allowContactless) add(CardSlotTypeEnum.RF)
        }
        Log.d(TAG, "startTransaction slotTypes=$slotTypes")

        val result = cardReader.searchCard(slotTypes, request.timeoutSeconds, cardInfoListener)
        if (result != SdkResult.Success) {
            Log.d(TAG, "startTransaction searchCard failed result=$result")
            transactionRunning.set(false)
            ledController.onError()
            beeper.onContactlessError()
            transactionListener?.onError("Unable to start card search: $result")
        } else {
            Log.d(TAG, "startTransaction searchCard started successfully")
            beeper.onAttentionRequired()
        }
    }

    /**
     * Continues a Mastercard mobile-CVM transaction after EMV_PLZ_SEE_PHONE.
     * The original request and kernel configuration are retained; only RF is searched.
     */
    fun continueMobileCvmTransaction(timeoutSeconds: Int = 60): Boolean {
        val request = currentRequest
        val config = currentEmvConfig
        if (!mobileCvmContinuationAvailable || request == null || config == null) {
            Log.w(
                TAG,
                "Mobile CVM continuation unavailable " +
                    "available=$mobileCvmContinuationAvailable request=${request != null} config=${config != null}",
            )
            return false
        }
        if (!transactionRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Mobile CVM continuation rejected because a transaction is already running")
            return false
        }

        Log.i(
            TAG,
            "Continuing mobile CVM with original trace=${config.traceNo} amount=${config.transAmount}",
        )
        mobileCvmContinuationAvailable = false
        mobileCvmContinuationInProgress = true
        contactlessReadAcknowledged = false
        currentCardInfo = null
        currentCardSlot = null
        clearPendingApplicationSelection()
        pinData.clear()
        ledController.prepareForTransaction(allowContactless = true, allowSwipe = false)

        val result = cardReader.searchCard(
            hashSetOf(CardSlotTypeEnum.RF),
            timeoutSeconds.coerceAtLeast(1),
            cardInfoListener,
        )
        if (result != SdkResult.Success) {
            Log.w(TAG, "Mobile CVM RF search failed result=$result")
            mobileCvmContinuationInProgress = false
            transactionRunning.set(false)
            ledController.onError()
            beeper.onContactlessError()
            return false
        }
        return true
    }

    fun cancelTransaction() {
        Log.d(TAG, "cancelTransaction invoked")
        clearPendingApplicationSelection()
        // Try both SDK cleanup operations even if one fails. A reader error must
        // not leave the application permanently locked out of future attempts.
        runCatching { cardReader.stopSearch() }
            .onFailure { Log.w(TAG, "Unable to stop card search", it) }
        runCatching { emvHandler?.emvProcessCancel() }
            .onFailure { Log.w(TAG, "Unable to cancel EMV processing", it) }
        mobileCvmContinuationAvailable = false
        mobileCvmContinuationInProgress = false
        transactionRunning.set(false)
        ledController.onTransactionCancelled()
    }

    /** Reads the physical ICC slots. This SDK call may perform device I/O. */
    fun isContactCardInserted(): Boolean = CONTACT_CARD_SLOTS.any { slot ->
        runCatching { cardReader.isCardExist(slot) }
            .onFailure { error -> Log.w(TAG, "Unable to read card presence for slot=$slot", error) }
            .getOrDefault(false)
    }

    fun beepAttentionRequired() {
        beeper.onAttentionRequired()
    }

    fun beepCardRemovalReminder() {
        beeper.onCardRemovalReminder()
    }

    fun beepSeePhoneInstruction() {
        beeper.onSeePhoneInstruction()
    }

    /** Produces audible feedback when contact ICC is required after a contactless attempt. */
    fun beepContactCardRequired() {
        beeper.onContactCardRequired()
    }

    /**
     * Captures a new PIN twice with the PIN key configuration owned by the selected acquirer.
     * Clear PIN digits never leave the secure Nexgo PIN pad.
     */
    internal suspend fun captureNewPinAndConfirmation(
        pan: String,
        scheme: OnlinePinScheme,
        keyIndex: Int,
        compatibleAcquirerIds: Set<String>,
    ): PinChangeCaptureResult = pinChangeCaptureOrchestrator.capture(
        pan = pan,
        scheme = scheme,
        keyIndex = keyIndex,
        compatibleAcquirerIds = compatibleAcquirerIds,
    )

    fun selectApplication(selectedIndex: Int): Boolean {
        val kernelResponse = synchronized(applicationSelectionLock) {
            applicationSelectionResponseIndex(selectedIndex, pendingApplicationCount)?.also {
                pendingApplicationCount = 0
            }
        }
        if (kernelResponse == null) {
            Log.w(
                TAG,
                "Ignoring invalid or stale application selection index=$selectedIndex",
            )
            return false
        }

        return try {
            val handler = emvHandler ?: error("EMV handler is unavailable")
            Log.d(
                TAG,
                "EMV_STEP onSetSelAppResponse selectedIndex=$selectedIndex response=$kernelResponse",
            )
            handler.onSetSelAppResponse(kernelResponse)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to submit EMV application selection", error)
            transactionListener?.onError(
                context.getString(R.string.card_reader_application_selection_error),
                error,
            )
            false
        }
    }

    fun destroy() {
        Log.d(TAG, "destroy invoked")
        cancelTransaction()
        emvHandler = null
        beeper.shutdown()
        ledController.shutdown()
    }

    private fun setupListeners() {
        Log.d(TAG, "setupListeners configuring")
        cardInfoListener.onCardInfo = ::handleCardInfo
        cardInfoListener.onMultipleCards = {
            Log.d(TAG, "cardInfoListener.onMultipleCards invoked")
            beeper.onContactlessError()
            transactionListener?.onMultipleCardsDetected()
            transactionRunning.set(false)
            ledController.onError()
        }
        cardInfoListener.onSwipeIncorrect = {
            Log.d(TAG, "cardInfoListener.onSwipeIncorrect invoked")
            beeper.onContactlessError()
            transactionListener?.onSwipeIncorrect()
        }

        emvProcessListener.onSelApp = { labels, _, mandatory ->
            val appLabels = labels.orEmpty().toList()
            Log.d(TAG, "emvProcessListener.onSelApp labels=${appLabels.size} mandatory=$mandatory")
            when {
                appLabels.isEmpty() -> {
                    val kernelResponse = if (mandatory) 0 else -1
                    Log.w(TAG, "EMV application selection has no candidates response=$kernelResponse")
                    emvHandler?.onSetSelAppResponse(kernelResponse)
                }

                appLabels.size == 1 -> {
                    Log.d(TAG, "EMV application selection auto-select response=1 label=${appLabels.first()}")
                    emvHandler?.onSetSelAppResponse(1)
                }

                else -> {
                    synchronized(applicationSelectionLock) {
                        pendingApplicationCount = appLabels.size
                    }
                    Log.d(TAG, "EMV application selection requesting user choice labels=$appLabels")
                    val listener = transactionListener
                    if (listener == null) {
                        Log.e(TAG, "No transaction listener is available for application selection")
                        clearPendingApplicationSelection()
                        emvHandler?.onSetSelAppResponse(-1)
                    } else {
                        listener.onApplicationSelectionRequested(appLabels, mandatory)
                    }
                }
            }
        }
        emvProcessListener.onTransInitBeforeGpo = ::handleTransInitBeforeGpo
        emvProcessListener.onConfirmCardNo = { cardInfo ->
            if (cardInfo != null) {
                currentCardInfo = cardInfo
            }
            Log.d(TAG, "emvProcessListener.onConfirmCardNo invoked")
            Log.d(TAG, "EMV_STEP onSetConfirmCardNoResponse confirmed=true")
            emvHandler?.onSetConfirmCardNoResponse(true)
        }
        emvProcessListener.onCardHolderInputPin = ::handlePinRequest
        emvProcessListener.onContactlessTapCardAgain = {
            Log.d(TAG, "emvProcessListener.onContactlessTapCardAgain invoked")
            ledController.onError()
            transactionListener?.onContactlessRetryRequired()
        }
        emvProcessListener.onOnlineProc = {
            Log.d(TAG, "emvProcessListener.onOnlineProc invoked")
            acknowledgeContactlessReadSuccess()
            ledController.onOnlineProcessing()
            transactionListener?.onOnlineProcessing()
        }
        emvProcessListener.onPrompt = { prompt ->
            Log.d(TAG, "emvProcessListener.onPrompt invoked prompt=$prompt")
            transactionListener?.onPrompt(prompt)
        }
        emvProcessListener.onRemoveCard = {
            Log.d(TAG, "emvProcessListener.onRemoveCard invoked")
            transactionListener?.onRemoveCard()
        }
        emvProcessListener.onFinish = ::handleEmvFinish
    }

    private fun applyStoredCapkList() {
        val capkList = configuredCapkList ?: run {
            Log.d(TAG, "applyStoredCapkList: no CAPK list configured yet")
            return
        }
        emvHandler?.delAllCapk()
        emvHandler?.setCAPKList(capkList)
        Log.d(TAG, "applyStoredCapkList applied ${capkList.size} CAPKs")
    }

    private fun applyStoredAidList() {
        val aidList = configuredAidList ?: run {
            Log.d(TAG, "applyStoredAidList: no TMS AID list configured yet")
            return
        }
        emvHandler?.delAllAid()
        emvHandler?.setAidParaList(aidList)
        Log.d(TAG, "applyStoredAidList applied ${aidList.size} AIDs from TMS")
        verifyAidList()
    }

    private fun verifyAidList() {
        val loaded = emvHandler?.aidList
        if (loaded == null) {
            Log.w(TAG, "verifyAidList: getAidList() returned null")
            return
        }
        Log.d(TAG, "verifyAidList: ${loaded.size} AID(s) confirmed in handler")
        for (e in loaded) {
            Log.d(
                TAG,
                "  [VERIFIED] aid=${e.aid} mode=${e.aidEntryModeEnum} asi=${e.asi}" +
                    " tacDefault=${e.tacDefault} tacOnline=${e.tacOnline} tacDenial=${e.tacDenial}",
            )
        }
    }

    private fun handleCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
        Log.d(
            TAG,
            "handleCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot} pan=${maskPan(cardInfo?.cardNo)} tk2Length=${cardInfo?.tk2?.length}",
        )
        ledController.onCardDetected(cardInfo?.cardExistslot)
        if (retCode != SdkResult.Success || cardInfo == null) {
            transactionRunning.set(false)
            ledController.onError()
            if (retCode != SdkResult.TimeOut) beeper.onContactlessError()
            Log.w(TAG, "Card search ended with SDK result=$retCode")
            transactionListener?.onError(context.getString(
                if (retCode == SdkResult.TimeOut) R.string.card_search_timeout
                else R.string.card_search_failed,
            ))
            return
        }

        currentCardInfo = cardInfo
        // onConfirmCardNo refreshes card information and may omit the reader slot.
        // Keep the detection result for the lifetime of this EMV attempt.
        currentCardSlot = cardInfo.cardExistslot
        Log.d(TAG, "handleCardInfo currentCardInfo updated")
        // RF detection is not a completed EMV read. Acknowledge at onOnlineProc
        // (or successful onFinish for offline approval), after the kernel has
        // read the card, rather than overlapping the start of EMV processing.
        transactionListener?.onCardDetected(cardInfo.cardExistslot, cardInfo)

        when (cardInfo.cardExistslot) {
            CardSlotTypeEnum.SWIPE -> {
                transactionListener?.onMagstripeRead(
                    MagstripeData(cardInfo.tk1, cardInfo.tk2, cardInfo.tk3),
                )
                transactionRunning.set(false)
                ledController.onTransactionFinished()
            }
            CardSlotTypeEnum.ICC1, CardSlotTypeEnum.RF -> {
                startEmvProcess(cardInfo)
            }
            else -> {
                transactionRunning.set(false)
                ledController.onError()
                beeper.onContactlessError()
                transactionListener?.onError("Unsupported card slot: ${cardInfo.cardExistslot}")
            }
        }
    }

    private fun startEmvProcess(cardInfo: CardInfoEntity) {
        Log.d(TAG, "startEmvProcess slot=${cardInfo.cardExistslot}")
        val request = currentRequest ?: return
        val continuingMobileCvm = mobileCvmContinuationInProgress
        val emvConfig = if (continuingMobileCvm) {
            val preservedConfig = currentEmvConfig
            if (preservedConfig == null) {
                mobileCvmContinuationInProgress = false
                transactionRunning.set(false)
                ledController.onError()
                transactionListener?.onError("Mobile CVM transaction context is unavailable")
                return
            }
            refreshMobileCvmContinuationConfiguration(preservedConfig)
        } else {
            buildTransConfiguration(request, cardInfo.cardExistslot).also { currentEmvConfig = it }
        }
        mobileCvmContinuationInProgress = false
        logEmvConfig(
            if (continuingMobileCvm) "mobile CVM continuation config" else "startEmvProcess config",
            emvConfig,
        )
        val result = emvHandler?.emvProcess(emvConfig, emvProcessListener)
        if (result != SdkResult.Success) {
            transactionRunning.set(false)
            ledController.onError()
            beeper.onContactlessError()
            transactionListener?.onError("EMV process failed to start: $result")
        } else {
            Log.d(TAG, "startEmvProcess started with config=${emvConfig.emvTransType} result=$result")
        }
    }

    private fun buildTransConfiguration(
        request: EmvTransactionRequest,
        slot: CardSlotTypeEnum,
    ): EmvTransConfigurationEntity {
        Log.d(
            TAG,
            "buildTransConfiguration amount=${request.amount} cashback=${request.cashbackAmount} slot=$slot",
        )
        val now = LocalDateTime.now()
        val date = now.format(DateTimeFormatter.ofPattern("yyMMdd"))
        val time = now.format(DateTimeFormatter.ofPattern("HHmmss"))
        return EmvTransConfigurationEntity().apply {
            // The SDK pads this invoice to eight BCD digits for the EMV transaction sequence (9F41).
            traceNo = request.traceNumber
            transAmount = request.amount
            cashbackAmount = request.cashbackAmount
            transDate = date
            transTime = time
            emvTransType = request.transactionType
            countryCode = request.countryCode
            currencyCode = request.currencyCode
            emvEntryModeEnum = if (slot == CardSlotTypeEnum.RF) {
                EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACTLESS
            } else {
                EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACT
            }
            emvProcessFlowEnum = if (request.purpose == EmvTransactionPurpose.CARD_DATA_QUERY)
                EmvProcessFlowEnum.EMV_PROCESS_FLOW_READ_APPDATA else EmvProcessFlowEnum.EMV_PROCESS_FLOW_STANDARD
            isContactForceOnline = request.forceOnline
            isContactlessSupportSelectApp = true
        }.also {
            Log.d(
                TAG,
                "buildTransConfiguration result trace=${it.traceNo} entryMode=${it.emvEntryModeEnum} forceOnline=${it.isContactForceOnline}",
            )
        }
    }

    private fun handleTransInitBeforeGpo() {
        Log.d(TAG, "handleTransInitBeforeGpo invoked")
        val handler = emvHandler ?: return
        var continueTransaction = true
        try {
            val aid = handler.getTlv(byteArrayOf(0x4F.toByte()), EmvDataSourceEnum.FROM_KERNEL)
            val dedicatedFileName = handler.getTlv(byteArrayOf(0x84.toByte()), EmvDataSourceEnum.FROM_KERNEL)
            val appLabel = handler.getTlv(byteArrayOf(0x50.toByte()), EmvDataSourceEnum.FROM_KERNEL)
            val preferredName = handler.getTlv(byteArrayOf(0x9F.toByte(), 0x12.toByte()), EmvDataSourceEnum.FROM_KERNEL)
            val slot = currentCardSlot
            Log.d(
                TAG,
                "EMV_STEP handleTransInitBeforeGpo slot=$slot " +
                    "4F=${aid?.let { ByteUtils.byteArray2HexString(it) }} " +
                    "84=${dedicatedFileName?.let { ByteUtils.byteArray2HexString(it) }} " +
                    "50=${appLabel?.let { ByteUtils.byteArray2HexString(it) }} " +
                    "9F12=${preferredName?.let { ByteUtils.byteArray2HexString(it) }}",
            )
            currentRequest?.let { request -> configureCountryAndCurrency(handler, request) }
            val selectedAid = aid?.takeIf { it.isNotEmpty() }
                ?: dedicatedFileName?.takeIf { it.isNotEmpty() }
            if (slot == CardSlotTypeEnum.RF && selectedAid != null) {
                configureContactlessParameters(handler, selectedAid)
            }
            if (selectedAid != null) {
                continueTransaction = configureTerminalCapabilities(
                    handler,
                    selectedAid,
                    slot == CardSlotTypeEnum.RF,
                )
            } else if (currentRequest?.purpose in PIN_MAINTENANCE_PURPOSES) {
                continueTransaction = false
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to handle OnTransInitBeforeGPO", error)
        } finally {
            try {
                Log.d(TAG, "EMV_STEP onSetTransInitBeforeGPOResponse continue=$continueTransaction")
                handler.onSetTransInitBeforeGPOResponse(continueTransaction)
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to acknowledge OnTransInitBeforeGPO", error)
            }
        }
    }

    private fun configureContactlessParameters(handler: EmvHandler2, aid: ByteArray) {
        val aidHex = ByteUtils.byteArray2HexString(aid)?.uppercase(Locale.US) ?: return
        val onlinePinEnabled = isOnlinePinEnabledForContactlessAid(aidHex)
        val capabilityProfile = EmvTerminalCapabilities.findProfile(
            configuredTerminalCapabilityProfiles,
            aidHex,
            EmvCapabilityInterface.CONTACTLESS,
        )
        Log.d(TAG, "configureContactlessParameters aid=$aidHex onlinePinEnabled=$onlinePinEnabled")
        when {
            aidHex.contains("A000000004") -> configPaypassParameter(
                handler,
                aid,
                onlinePinEnabled,
                capabilityProfile,
            )
            aidHex.contains("A000000003") -> configPaywaveParameters(
                handler,
                onlinePinEnabled,
                capabilityProfile?.ttq,
            )
            aidHex.contains("A000000025") -> configExpressPayParameter(handler)
            aidHex.contains("A000000152") -> {
                configDpasParameter(handler, onlinePinEnabled, capabilityProfile?.ttq)
                contactlessDiscoverCard = true
            }
            aidHex.contains("A000000541") -> {
                // PURE contactless configuration not required for current deployment.
            }
            aidHex.contains("A000000065") -> configJcbContactlessParameter(handler)
            aidHex.contains("A000000333010108") -> configUnionPayParameter(handler)
        }
    }

    private fun refreshMobileCvmContinuationConfiguration(
        config: EmvTransConfigurationEntity,
    ): EmvTransConfigurationEntity {
        val now = LocalDateTime.now()
        return prepareMobileCvmContinuationConfiguration(
            config = config,
            transDate = now.format(DateTimeFormatter.ofPattern("yyMMdd")),
            transTime = now.format(DateTimeFormatter.ofPattern("HHmmss")),
        )
    }

    private fun configureCountryAndCurrency(
        handler: EmvHandler2,
        request: EmvTransactionRequest,
    ) {
        val values = EmvTransactionTags.countryAndCurrency(
            countryCode = request.countryCode,
            currencyCode = request.currencyCode,
        ) ?: run {
            Log.w(
                TAG,
                "Skipping EMV country/currency TLVs because request values are invalid: " +
                    "country=${request.countryCode} currency=${request.currencyCode}",
            )
            return
        }

        handler.setTlv(TAG_TERMINAL_COUNTRY_CODE, values.countryCode)
        handler.setTlv(TAG_TRANSACTION_CURRENCY_CODE, values.currencyCode)
        Log.i(
            TAG,
            "EMV_STEP countryCurrency 9F1A=${ByteUtils.byteArray2HexString(values.countryCode)} " +
                "5F2A=${ByteUtils.byteArray2HexString(values.currencyCode)}",
        )
    }

    private fun configureTerminalCapabilities(
        handler: EmvHandler2,
        aid: ByteArray,
        contactless: Boolean,
    ): Boolean {
        val purpose = currentRequest?.purpose
        val pinMaintenance = purpose in PIN_MAINTENANCE_PURPOSES
        val aidHex = ByteUtils.byteArray2HexString(aid)?.uppercase(Locale.US)
            ?: return !pinMaintenance
        val interfaceType = if (contactless) {
            EmvCapabilityInterface.CONTACTLESS
        } else {
            EmvCapabilityInterface.CONTACT
        }
        val profile = EmvTerminalCapabilities.findProfile(
            configuredTerminalCapabilityProfiles,
            aidHex,
            interfaceType,
        ) ?: run {
            Log.w(TAG, "No 9F33 capability profile found for interface=$interfaceType aid=$aidHex")
            return !pinMaintenance
        }
        val tag9F33 = byteArrayOf(0x9F.toByte(), 0x33.toByte())
        val current = handler.getTlv(tag9F33, EmvDataSourceEnum.FROM_KERNEL)
        val configured = EmvTerminalCapabilities.apply9F33(current, profile) ?: run {
            Log.w(
                TAG,
                "Unable to configure 9F33 for interface=$interfaceType aid=$aidHex: " +
                    "kernel value is missing and the profile has no base value",
            )
            return !pinMaintenance
        }
        val effectiveCapabilities = when (purpose) {
            EmvTransactionPurpose.OFFLINE_PIN_CHANGE -> {
                EmvTerminalCapabilities.applyOfflinePinChangeCvmPolicy(configured) ?: run {
                    Log.w(TAG, "Selected contact AID does not permit an offline PIN CVM")
                    return false
                }
            }
            EmvTransactionPurpose.OFFLINE_PIN_UNBLOCK -> {
                EmvTerminalCapabilities.applyOfflinePinUnblockCvmPolicy(configured) ?: run {
                    Log.w(TAG, "Selected contact AID does not permit No CVM for PIN unblock")
                    return false
                }
            }
            else -> configured
        }
        handler.setTlv(tag9F33, effectiveCapabilities)
        Log.i(
            TAG,
            "EMV_STEP 9F33 interface=$interfaceType aid=$aidHex " +
                "before=${current?.let { ByteUtils.byteArray2HexString(it) }} " +
                "after=${ByteUtils.byteArray2HexString(effectiveCapabilities)} " +
                "controlled=%02X enabled=%02X".format(
                    profile.controlledCvmMask,
                    profile.enabledCvmMask,
                ),
        )
        return true
    }

    private fun isOnlinePinEnabledForContactlessAid(aidHex: String): Boolean {
        val selectedAid = configuredAidList
            .orEmpty()
            .asSequence()
            .filter { it.aidEntryModeEnum == AidEntryModeEnum.AID_ENTRY_CONTACTLESS }
            .filter { aidHex.startsWith(it.aid.uppercase(Locale.US)) }
            .maxByOrNull { it.aid.length }

        return configuredTerminalOnlinePinCap && (selectedAid?.onlinePinCap ?: 1) == 1
    }

    private fun logEmvConfig(label: String, config: EmvTransConfigurationEntity) {
        Log.d(
            TAG,
            "EMV_STEP $label trace=${config.traceNo} amount=${config.transAmount} " +
                "cashback=${config.cashbackAmount} date=${config.transDate} time=${config.transTime} " +
                "type=${config.emvTransType} country=${config.countryCode} currency=${config.currencyCode} " +
                "entry=${config.emvEntryModeEnum} flow=${config.emvProcessFlowEnum} " +
                "forceOnline=${config.isContactForceOnline} clSelect=${config.isContactlessSupportSelectApp}",
        )
    }

    private fun configPaywaveParameters(
        handler: EmvHandler2,
        onlinePinEnabled: Boolean,
        configuredTtq: ByteArray?,
    ) {
        Log.d(TAG, "configPaywaveParameters invoked onlinePinEnabled=$onlinePinEnabled")
        val tag9F33 = byteArrayOf(0x9F.toByte(), 0x33.toByte())
        val tag9F66 = byteArrayOf(0x9F.toByte(), 0x66.toByte())
        val kernel9F33 = handler.getTlv(tag9F33, EmvDataSourceEnum.FROM_KERNEL)
        if (kernel9F33 != null && kernel9F33.size >= 2) {
            kernel9F33[1] = applyOnlinePinCvmCapability(
                kernel9F33[1].toInt() or 0x60,
                onlinePinEnabled,
            ).toByte()
            handler.setTlv(tag9F33, kernel9F33)
        }

        val kernelTTQ = handler.getTlv(tag9F66, EmvDataSourceEnum.FROM_KERNEL)
        val ttq = configuredTtq?.takeIf { it.size == 4 }
            ?: ByteUtils.hexString2ByteArray("36004000")
        if (kernelTTQ != null && kernelTTQ.size >= 4 && ttq != null && ttq.size >= 4) {
            kernelTTQ[0] = applyOnlinePinTtqCapability(ttq[0].toInt(), onlinePinEnabled).toByte()
            kernelTTQ[1] = ttq[1]
            kernelTTQ[2] = ttq[2]
            kernelTTQ[3] = ttq[3]
            handler.setTlv(tag9F66, kernelTTQ)
        }
    }

    private fun configPaypassParameter(
        handler: EmvHandler2,
        aid: ByteArray,
        onlinePinEnabled: Boolean,
        capabilityProfile: EmvTerminalCapabilityProfile?,
    ) {
        Log.d(
            TAG,
            "configPaypassParameter aid=${ByteUtils.byteArray2HexString(aid)} " +
                "transType=${currentRequest?.transactionType} onlinePinEnabled=$onlinePinEnabled",
        )
        val technical = capabilityProfile?.contactlessTechnicalValues
        val tagDf811b = byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x1B.toByte())
        val kernelConfiguration = technical?.kernelConfiguration
            ?.let(ByteUtils::hexString2ByteArray)
            ?.takeIf { it.size == 1 }
            ?: byteArrayOf(0xB0.toByte())
        handler.setTlv(tagDf811b, kernelConfiguration)
        if (technical != null) {
            setTechnicalContactlessTlv(handler, "DF8117", technical.cardDataInputCapability)
            setTechnicalContactlessTlv(handler, "DF811A", technical.defaultUdol)
            setTechnicalContactlessTlv(handler, "DF811C", technical.tornTransactionLifetime)
            setTechnicalContactlessTlv(handler, "DF811D", technical.tornTransactionMaxRecords)
            setTechnicalContactlessTlv(handler, "DF811F", technical.securityCapability)
            setTechnicalContactlessTlv(handler, "9F6D", technical.magstripeApplicationVersion)
            setTechnicalContactlessTlv(handler, "DF811E", technical.magstripeCvmCapabilityRequired)
            setTechnicalContactlessTlv(handler, "DF812C", technical.magstripeCvmCapabilityNoCvmRequired)
            setTechnicalContactlessTlv(handler, "9F35", technical.terminalType)
            setTechnicalContactlessTlv(handler, "DF810C", technical.kernelIdentifier)
        }

        if (currentRequest?.transactionType == 0x20.toByte()) {
            ByteUtils.hexString2ByteArray("0000000000")?.let {
                handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x20.toByte()), it)
            }
            ByteUtils.hexString2ByteArray("FFFFFFFFFF")?.let {
                handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x21.toByte()), it)
            }
            ByteUtils.hexString2ByteArray("0000000000")?.let {
                handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x22.toByte()), it)
            }
            return
        }

        val aidHex = ByteUtils.byteArray2HexString(aid)?.uppercase(Locale.US) ?: return
        when {
            aidHex.contains("A0000000043060") -> {
                ByteUtils.hexString2ByteArray(
                    technical?.terminalRiskManagementData?.takeIf { it.isNotBlank() }
                        ?: "4C7A800000000000",
                )?.let {
                    handler.setTlv(byteArrayOf(0x9F.toByte(), 0x1D.toByte()), it)
                }
                handler.setTlv(
                    byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x18.toByte()),
                    byteArrayOf(
                        mastercardCvmRequiredCapability(
                            certifiedBaseline = 0x40,
                            onlinePinEnabled = onlinePinEnabled,
                            capabilityProfile = capabilityProfile,
                        ).toByte(),
                    ),
                )
                setMastercardNoCvmCapability(handler, capabilityProfile)
                ByteUtils.hexString2ByteArray("F45004800C")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x20.toByte()), it)
                }
                ByteUtils.hexString2ByteArray("0000800000")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x21.toByte()), it)
                }
                ByteUtils.hexString2ByteArray("F45004800C")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x22.toByte()), it)
                }
                pinData.status = PinStatus.DUMMY
            }
            aidHex.contains("A0000000041010") -> {
                ByteUtils.hexString2ByteArray(
                    technical?.terminalRiskManagementData?.takeIf { it.isNotBlank() }
                        ?: "6C7A800000000000",
                )?.let {
                    handler.setTlv(byteArrayOf(0x9F.toByte(), 0x1D.toByte()), it)
                }
                handler.setTlv(
                    byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x18.toByte()),
                    byteArrayOf(
                        mastercardCvmRequiredCapability(
                            certifiedBaseline = 0x60,
                            onlinePinEnabled = onlinePinEnabled,
                            capabilityProfile = capabilityProfile,
                        ).toByte(),
                    ),
                )
                setMastercardNoCvmCapability(handler, capabilityProfile)
                ByteUtils.hexString2ByteArray("F45084800C")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x20.toByte()), it)
                }
                ByteUtils.hexString2ByteArray("0000000000")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x21.toByte()), it)
                }
                ByteUtils.hexString2ByteArray("F45084800C")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x22.toByte()), it)
                }
            }
            aidHex.contains("A0000000042203") -> {
                val kernelDf811b = handler.getTlv(tagDf811b, EmvDataSourceEnum.FROM_KERNEL)
                if (kernelDf811b != null && kernelDf811b.isNotEmpty()) {
                    kernelDf811b[0] = (kernelDf811b[0].toInt() and 0xD0).toByte()
                    handler.setTlv(tagDf811b, kernelDf811b)
                }
                ByteUtils.hexString2ByteArray(
                    technical?.terminalRiskManagementData?.takeIf { it.isNotBlank() }
                        ?: "487A800000000000",
                )?.let {
                    handler.setTlv(byteArrayOf(0x9F.toByte(), 0x1D.toByte()), it)
                }
                handler.setTlv(
                    byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x18.toByte()),
                    byteArrayOf(
                        mastercardCvmRequiredCapability(
                            certifiedBaseline = 0x40,
                            onlinePinEnabled = onlinePinEnabled,
                            capabilityProfile = capabilityProfile,
                        ).toByte(),
                    ),
                )
                setMastercardNoCvmCapability(handler, capabilityProfile)
                ByteUtils.hexString2ByteArray("F45084800C")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x20.toByte()), it)
                }
                ByteUtils.hexString2ByteArray("0000000000")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x21.toByte()), it)
                }
                ByteUtils.hexString2ByteArray("F45084800C")?.let {
                    handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x22.toByte()), it)
                }
            }
        }
    }

    private fun configExpressPayParameter(handler: EmvHandler2) {
        Log.d(TAG, "configExpressPayParameter invoked")
        val tag9F6E = byteArrayOf(0x9F.toByte(), 0x6E.toByte())
        val kernelTtc = handler.getTlv(tag9F6E, EmvDataSourceEnum.FROM_KERNEL)
        val ttc = ByteUtils.hexString2ByteArray("DCE00003")
        if (kernelTtc != null && kernelTtc.size >= 4 && ttc != null && ttc.size >= 4) {
            kernelTtc[1] = ttc[1]
            kernelTtc[3] = (kernelTtc[3].toInt() and 0x7F).toByte()
            handler.setTlv(tag9F6E, kernelTtc)
        }
    }

    private fun configDpasParameter(
        handler: EmvHandler2,
        onlinePinEnabled: Boolean,
        configuredTtq: ByteArray?,
    ) {
        Log.d(TAG, "configDpasParameter invoked onlinePinEnabled=$onlinePinEnabled")
        val tag9F33 = byteArrayOf(0x9F.toByte(), 0x33.toByte())
        val tag9F66 = byteArrayOf(0x9F.toByte(), 0x66.toByte())
        val kernel9F33 = handler.getTlv(tag9F33, EmvDataSourceEnum.FROM_KERNEL)
        if (kernel9F33 != null && kernel9F33.size >= 2) {
            kernel9F33[1] = applyOnlinePinCvmCapability(
                kernel9F33[1].toInt() or 0x60,
                onlinePinEnabled,
            ).toByte()
            handler.setTlv(tag9F33, kernel9F33)
        }

        val kernelTTQ = handler.getTlv(tag9F66, EmvDataSourceEnum.FROM_KERNEL)
        val ttq = configuredTtq?.takeIf { it.size == 4 }
            ?: ByteUtils.hexString2ByteArray("36A04000")
        if (kernelTTQ != null && kernelTTQ.size >= 4 && ttq != null && ttq.size >= 4) {
            kernelTTQ[0] = applyOnlinePinTtqCapability(ttq[0].toInt(), onlinePinEnabled).toByte()
            kernelTTQ[1] = ttq[1]
            kernelTTQ[2] = ttq[2]
            kernelTTQ[3] = ttq[3]
            handler.setTlv(tag9F66, kernelTTQ)
        }
    }

    private fun configJcbContactlessParameter(handler: EmvHandler2) {
        Log.d(TAG, "configJcbContactlessParameter invoked")
        val tip = handler.jcbContactlessTIP
        if (tip != null && tip.size >= 2) {
            tip[1] = (tip[1].toInt() and 0x7F).toByte()
            handler.setJcbContactlessTIP(tip)
        }
    }

    private fun configUnionPayParameter(handler: EmvHandler2) {
        Log.d(TAG, "configUnionPayParameter invoked")
        handler.setTlv(
            byteArrayOf(0x9F.toByte(), 0x09.toByte()),
            byteArrayOf(0x00.toByte(), 0x30.toByte()),
        )
    }

    private fun handlePinRequest(isOnlinePin: Boolean, attemptsRemaining: Int) {
        Log.d(
            TAG,
            "handlePinRequest online=$isOnlinePin attemptsRemaining=$attemptsRemaining pan=${maskPan(currentCardInfo?.cardNo)}",
        )
        transactionListener?.onPinRequested(isOnlinePin, attemptsRemaining)
        if (currentRequest?.purpose == EmvTransactionPurpose.OFFLINE_PIN_UNBLOCK) {
            val message = "PIN unblock unexpectedly requested cardholder PIN entry"
            Log.e(TAG, message)
            pinData.status = PinStatus.ERROR
            pinData.errorMessage = message
            pinEntryDone = true
            emvHandler?.onSetPinInputResponse(false, false)
            return
        }
        val pan = currentCardInfo?.cardNo ?: ""
        if (isOnlinePin) {
            pinData.onlinePinRequested = true
            if (configuredAcquirerPinTypes.none(::supportsOnlinePinType)) {
                val message = context.getString(R.string.online_pin_error_no_acquirer_pin_type)
                Log.e(TAG, message)
                pinData.status = PinStatus.ERROR
                pinData.errorMessage = message
                pinEntryDone = true
                emvHandler?.onSetPinInputResponse(false, false)
                return
            }
            if (pan.isBlank()) {
                val message = context.getString(R.string.online_pin_error_missing_pan)
                Log.e(TAG, message)
                pinData.status = PinStatus.ERROR
                pinData.errorMessage = message
                pinEntryDone = true
                emvHandler?.onSetPinInputResponse(false, false)
                return
            }
            val selection = configuredOnlinePinResolver?.resolve(pan)
            if (selection == null) {
                val message = context.getString(R.string.online_pin_error_ambiguous_key_profile)
                Log.e(TAG, message)
                pinData.status = PinStatus.ERROR
                pinData.errorMessage = message
                pinEntryDone = true
                emvHandler?.onSetPinInputResponse(false, false)
                return
            }
            pinData.scheme = selection.scheme
            pinData.keyIndex = selection.keyIndex
            pinData.compatibleAcquirerIds = selection.compatibleAcquirerIds
        }
        val handler = pinEntryHandler
        if (handler != null) {
            Log.d(TAG, "handlePinRequest invoking external handler")
            handler.invoke(pan, isOnlinePin)
        } else {
            Log.d(TAG, "handlePinRequest launching PixiePinEntryActivity")
            launchPinEntryActivity(pan, isOnlinePin, pinData.scheme, pinData.keyIndex, pinData.compatibleAcquirerIds)
        }
    }

    private fun setTechnicalContactlessTlv(handler: EmvHandler2, tag: String, value: String) {
        if (value.isBlank()) return
        val tagBytes = ByteUtils.hexString2ByteArray(tag) ?: return
        val valueBytes = ByteUtils.hexString2ByteArray(value) ?: return
        handler.setTlv(tagBytes, valueBytes)
    }

    private fun mastercardCvmRequiredCapability(
        certifiedBaseline: Int,
        onlinePinEnabled: Boolean,
        capabilityProfile: EmvTerminalCapabilityProfile?,
    ): Int {
        val legacyValue = applyOnlinePinCvmCapability(certifiedBaseline, onlinePinEnabled)
        if (capabilityProfile?.contactlessTechnicalValues == null) return legacyValue
        return legacyValue and capabilityProfile.enabledCvmMask
    }

    private fun setMastercardNoCvmCapability(
        handler: EmvHandler2,
        capabilityProfile: EmvTerminalCapabilityProfile?,
    ) {
        val noCvm = if (capabilityProfile?.contactlessTechnicalValues == null) {
            EmvTerminalCapabilities.NO_CVM
        } else {
            capabilityProfile.enabledCvmMask and EmvTerminalCapabilities.NO_CVM
        }
        handler.setTlv(
            byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x19.toByte()),
            byteArrayOf(noCvm.toByte()),
        )
    }

    private fun launchPinEntryActivity(
        pan: String,
        isOnlinePin: Boolean,
        scheme: OnlinePinScheme?,
        keyIndex: Int,
        compatibleAcquirerIds: Set<String>,
        purpose: SecurePinEntryPurpose = SecurePinEntryPurpose.EMV_CVM,
        resultRequestId: String = "",
    ) {
        Log.d(
            TAG,
            "launchPinEntryActivity pan=${maskPan(pan)} isOnlinePin=$isOnlinePin purpose=$purpose",
        )
        val intent = Intent(context, PixiePinEntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(PixiePinEntryActivity.EXTRA_PAN, pan)
            putExtra(PixiePinEntryActivity.EXTRA_IS_ONLINE_PIN, isOnlinePin)
            putExtra(PixiePinEntryActivity.EXTRA_KEY_INDEX, keyIndex)
            putExtra(PixiePinEntryActivity.EXTRA_PIN_SCHEME, scheme?.name)
            putStringArrayListExtra(
                PixiePinEntryActivity.EXTRA_COMPATIBLE_ACQUIRERS,
                ArrayList(compatibleAcquirerIds),
            )
            putExtra(PixiePinEntryActivity.EXTRA_PIN_ENTRY_PURPOSE, purpose.name)
            putExtra(PixiePinEntryActivity.EXTRA_RESULT_REQUEST_ID, resultRequestId)
        }
        context.startActivity(intent)
    }

    /** Returns the current DUKPT KSN without exposing it to application logs. */
    override fun currentDukptKsn(keyIndex: Int): String? = try {
        pinPad.dukptCurrentKsn(keyIndex)?.let(ByteUtils::byteArray2HexString)
    } catch (error: Throwable) {
        Log.e(TAG, "Unable to read DUKPT KSN at key index $keyIndex", error)
        null
    }

    /** Persists the DUKPT KSN reservation before either new-PIN block is generated. */
    @Suppress("UseKtx") // A synchronous commit result is required before secure PIN entry starts.
    override fun reserveDukptKsn(keyIndex: Int, ksn: String): Boolean {
        recoverReservedDukptKsn()
        if (pinChangeKsnPreferences.contains(PIN_CHANGE_PENDING_KSN)) return false
        return pinChangeKsnPreferences.edit()
            .putInt(PIN_CHANGE_PENDING_KEY_INDEX, keyIndex)
            .putString(PIN_CHANGE_PENDING_KSN, ksn)
            .commit()
    }

    /** Advances a used DUKPT KSN once and clears its persistent reservation. */
    override fun closeDukptKsnReservation(keyIndex: Int, ksn: String, advance: Boolean): Boolean {
        val pendingIndex = pinChangeKsnPreferences.getInt(PIN_CHANGE_PENDING_KEY_INDEX, -1)
        val pendingKsn = pinChangeKsnPreferences.getString(PIN_CHANGE_PENDING_KSN, null)
        if (pendingIndex != keyIndex || !pendingKsn.equals(ksn, ignoreCase = true)) return false

        val closed = if (advance) advanceReservedDukptKsn(keyIndex, ksn) else true
        if (!closed) return false
        return clearDukptKsnReservation()
    }

    /** Launches one isolated secure PIN-entry activity and awaits its encrypted result. */
    override suspend fun captureSecurePin(
        purpose: SecurePinEntryPurpose,
        pan: String,
        scheme: OnlinePinScheme,
        keyIndex: Int,
        compatibleAcquirerIds: Set<String>,
    ): SecurePinEntryResult {
        val ticket = SecurePinEntryResultBroker.open()
        return try {
            launchPinEntryActivity(
                pan = pan,
                isOnlinePin = true,
                scheme = scheme,
                keyIndex = keyIndex,
                compatibleAcquirerIds = compatibleAcquirerIds,
                purpose = purpose,
                resultRequestId = ticket.requestId,
            )
            withTimeout(MANUAL_PIN_ENTRY_RESULT_TIMEOUT_MS) {
                ticket.result.await()
            }
        } catch (_: TimeoutCancellationException) {
            SecurePinEntryResult(SecurePinEntryStatus.TIMED_OUT)
        } finally {
            SecurePinEntryResultBroker.cancel(ticket.requestId)
        }
    }

    /** Recovers a reservation left by process death so its DUKPT KSN cannot be reused. */
    private fun recoverReservedDukptKsn() {
        val keyIndex = pinChangeKsnPreferences.getInt(PIN_CHANGE_PENDING_KEY_INDEX, -1)
        val pendingKsn = pinChangeKsnPreferences.getString(PIN_CHANGE_PENDING_KSN, null)
        if (keyIndex < 0 || pendingKsn.isNullOrBlank()) return
        if (advanceReservedDukptKsn(keyIndex, pendingKsn)) {
            if (clearDukptKsnReservation()) {
                Log.w(TAG, "Recovered pending PIN-change DUKPT reservation at key index $keyIndex")
            }
        } else {
            Log.e(TAG, "Unable to recover pending PIN-change DUKPT reservation at key index $keyIndex")
        }
    }

    /** Ensures the current DUKPT KSN differs from the reserved KSN. */
    private fun advanceReservedDukptKsn(keyIndex: Int, reservedKsn: String): Boolean {
        return try {
            val before = currentDukptKsn(keyIndex) ?: return false
            if (!before.equals(reservedKsn, ignoreCase = true)) return true
            pinPad.dukptKsnIncrease(keyIndex)
            val after = currentDukptKsn(keyIndex) ?: return false
            !after.equals(reservedKsn, ignoreCase = true)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to advance PIN-change DUKPT KSN at key index $keyIndex", error)
            false
        }
    }

    /** Clears the non-sensitive persistent DUKPT reservation marker. */
    @Suppress("UseKtx") // A synchronous commit result is required before the KSN session closes.
    private fun clearDukptKsnReservation(): Boolean = pinChangeKsnPreferences.edit()
        .remove(PIN_CHANGE_PENDING_KEY_INDEX)
        .remove(PIN_CHANGE_PENDING_KSN)
        .commit()

    private fun handleEmvFinish(resultCode: Int, result: EmvProcessResultEntity?) {
        Log.d(TAG, "handleEmvFinish resultCode=$resultCode hasResult=${result != null}")
        clearPendingApplicationSelection()
        mobileCvmContinuationAvailable = resultCode == NexgoSdkResult.Emv_Plz_See_Phone &&
            currentRequest != null && currentEmvConfig != null
        mobileCvmContinuationInProgress = false
        transactionRunning.set(false)
        if (currentCardSlot == CardSlotTypeEnum.RF) {
            when (resultCode) {
                SdkResult.Success -> acknowledgeContactlessReadSuccess()
                NexgoSdkResult.Emv_Plz_See_Phone -> Unit
                else -> beeper.onContactlessError()
            }
        }
        contactlessReadAcknowledged = false
        ledController.onTransactionFinished()
        // Finish internal reader state before notifying UI code that can navigate
        // to the result screen and begin sensory playback.
        transactionListener?.onTransactionFinished(resultCode, result)
    }

    private fun acknowledgeContactlessReadSuccess() {
        if (
            currentCardSlot == CardSlotTypeEnum.RF &&
            !contactlessReadAcknowledged
        ) {
            contactlessReadAcknowledged = true
            beeper.onContactlessSuccess()
        }
    }

    private fun clearPendingApplicationSelection() {
        synchronized(applicationSelectionLock) {
            pendingApplicationCount = 0
        }
    }

    private fun maskPan(pan: String?): String? {
        Log.d(TAG, "maskPan invoked panLength=${pan?.length}")
        if (pan.isNullOrBlank()) return pan
        return when {
            pan.length <= 4 -> "*".repeat(pan.length)
            pan.length <= 6 -> pan.take(1) + "*".repeat(pan.length - 2) + pan.takeLast(1)
            pan.length <= 10 -> pan.take(2) + "*".repeat(pan.length - 6) + pan.takeLast(4)
            else -> pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)
        }.also {
            Log.d(TAG, "maskPan result=$it")
        }
    }

    companion object {
        private val CONTACT_CARD_SLOTS = listOf(CardSlotTypeEnum.ICC1)
        private const val TAG = "NexgoApi"
        private const val ONLINE_PIN_CVM_CAPABILITY_MASK = 0x40
        private const val ONLINE_PIN_TTQ_CAPABILITY_MASK = 0x04
        private const val MANUAL_PIN_ENTRY_RESULT_TIMEOUT_MS = 70_000L
        private const val PIN_CHANGE_KSN_PREFERENCES = "pin_change_ksn_state"
        private const val PIN_CHANGE_PENDING_KEY_INDEX = "pending_key_index"
        private const val PIN_CHANGE_PENDING_KSN = "pending_ksn"
        private val TAG_TERMINAL_COUNTRY_CODE = byteArrayOf(0x9F.toByte(), 0x1A.toByte())
        private val TAG_TRANSACTION_CURRENCY_CODE = byteArrayOf(0x5F.toByte(), 0x2A.toByte())
        private val PIN_MAINTENANCE_PURPOSES = setOf(
            EmvTransactionPurpose.OFFLINE_PIN_CHANGE,
            EmvTransactionPurpose.OFFLINE_PIN_UNBLOCK,
        )

        internal val pinData: PinData = PinData()
        @Volatile internal var pinEntryDone: Boolean = true
        @Volatile internal var emvHandler: EmvHandler2? = null
        @Volatile private var configuredAidList: List<AidEntity>? = null
        @Volatile private var configuredTerminalOnlinePinCap: Boolean = true
        @Volatile private var configuredTerminalCapabilityProfiles: List<EmvTerminalCapabilityProfile> = emptyList()
        @Volatile private var configuredCapkList: List<CapkEntity>? = null
        @Volatile private var configuredAcquirerPinTypes: Set<Int> = emptySet()
        @Volatile private var configuredOnlinePinResolver: OnlinePinProfileResolver? = null

        internal fun applyOnlinePinCvmCapability(value: Int, onlinePinEnabled: Boolean): Int {
            return if (onlinePinEnabled) value else value and ONLINE_PIN_CVM_CAPABILITY_MASK.inv()
        }

        internal fun applyOnlinePinTtqCapability(value: Int, onlinePinEnabled: Boolean): Int {
            return if (onlinePinEnabled) value else value and ONLINE_PIN_TTQ_CAPABILITY_MASK.inv()
        }

        internal fun supportsOnlinePinType(pinType: Int): Boolean = pinType == 1 || pinType == 3 || pinType == 4

        internal fun applicationSelectionResponseIndex(
            selectedIndex: Int,
            applicationCount: Int,
        ): Int? = selectedIndex
            .takeIf { it in 0 until applicationCount }
            ?.plus(1)

        fun applyAcquirerPinTypes(pinTypes: Collection<Int>) {
            configuredAcquirerPinTypes = pinTypes.toSet()
            Log.d(TAG, "applyAcquirerPinTypes configured=${configuredAcquirerPinTypes.sorted()}")
        }

        internal fun applyOnlinePinProfileResolver(resolver: OnlinePinProfileResolver) {
            configuredOnlinePinResolver = resolver
            Log.d(TAG, "Online PIN profile resolver updated")
        }

        /**
         * Stores the CAPK list loaded from assets and immediately applies it to [emvHandler]
         * if one is already active. Called from [GlobalConnectPaymentApplication.onCreate] on every cold start.
         * If [emvHandler] is null the list is stored and applied on next [NexgoApi] init.
         */
        fun applyCapkList(capkList: List<CapkEntity>) {
            configuredCapkList = capkList
            val handler = emvHandler ?: run {
                Log.d(TAG, "applyCapkList: handler not ready, list stored for next init")
                return
            }
            handler.delAllCapk()
            handler.setCAPKList(capkList)
            Log.d(TAG, "applyCapkList applied ${capkList.size} CAPKs to active handler")
        }

        /**
         * Stores the TMS-built AID list and terminal online PIN capability, then immediately
         * applies the AIDs to [emvHandler] if one is already active. Called from
         * [GlobalConnectPaymentApplication.applyTmsUpdate] whenever new TMS
         * parameters are received. If [emvHandler] is null at call time the list is stored
         * and applied the next time a [NexgoApi] instance is initialised.
         */
        internal fun applyEmvAidList(
            aidList: List<AidEntity>,
            terminalOnlinePinCap: Boolean = true,
            terminalCapabilityProfiles: List<EmvTerminalCapabilityProfile> = emptyList(),
        ) {
            configuredAidList = aidList
            configuredTerminalOnlinePinCap = terminalOnlinePinCap
            configuredTerminalCapabilityProfiles = terminalCapabilityProfiles
            val handler = emvHandler ?: run {
                Log.d(TAG, "applyEmvAidList: handler not ready, list stored for next init")
                return
            }
            handler.delAllAid()
            handler.setAidParaList(aidList)
            Log.d(
                TAG,
                "applyEmvAidList applied ${aidList.size} AIDs to active handler " +
                    "terminalOnlinePinCap=$terminalOnlinePinCap " +
                    "9F33Profiles=${terminalCapabilityProfiles.size}",
            )
            val loaded = handler.aidList
            if (loaded != null) {
                Log.d(TAG, "applyEmvAidList verified ${loaded.size} AID(s) confirmed in handler")
                for (e in loaded) {
                    Log.d(
                        TAG,
                        "  [VERIFIED] aid=${e.aid} mode=${e.aidEntryModeEnum} asi=${e.asi}" +
                            " tacDefault=${e.tacDefault} tacOnline=${e.tacOnline}" +
                            " onlinePinCap=${e.onlinePinCap}",
                    )
                }
            } else {
                Log.w(TAG, "applyEmvAidList: getAidList() returned null after set")
            }
        }

        fun obtain(context: Context): NexgoApi {
            Log.d(TAG, "obtain called for context=${context.packageName}")
            val engine = APIProxy.getDeviceEngine(context)
            return NexgoApi(context, engine)
        }
    }
}

/** Preserves the first-tap kernel configuration while refreshing the two time-dependent fields. */
internal fun prepareMobileCvmContinuationConfiguration(
    config: EmvTransConfigurationEntity,
    transDate: String,
    transTime: String,
): EmvTransConfigurationEntity = config.apply {
    this.transDate = transDate
    this.transTime = transTime
}
