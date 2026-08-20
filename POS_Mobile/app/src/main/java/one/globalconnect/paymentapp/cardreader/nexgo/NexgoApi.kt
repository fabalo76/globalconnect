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
) {

    private val cardReader: CardReader = deviceEngine.cardReader
    private val pinPad = deviceEngine.pinPad
    private val beeper = CardReaderBeeper(deviceEngine.beeper)
    private val ledController = CardReaderLedController(deviceEngine.ledDriver)

    private val cardInfoListener = CardInfoListener()
    private val emvProcessListener = EmvProcessListener()

    private var currentRequest: EmvTransactionRequest? = null
    private var currentCardInfo: CardInfoEntity? = null
    private var transactionRunning = AtomicBoolean(false)
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
        contactlessDiscoverCard = false
        currentRequest = request
        currentCardInfo = null
        clearPendingApplicationSelection()
        pinData.clear()

        if (!transactionRunning.compareAndSet(false, true)) {
            Log.d(TAG, "startTransaction already running")
            transactionListener?.onError("Transaction already running")
            return
        }

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
            transactionListener?.onError("Unable to start card search: $result")
        } else {
            Log.d(TAG, "startTransaction searchCard started successfully")
        }
    }

    fun cancelTransaction() {
        Log.d(TAG, "cancelTransaction invoked")
        clearPendingApplicationSelection()
        cardReader.stopSearch()
        emvHandler?.emvProcessCancel()
        transactionRunning.set(false)
        ledController.onTransactionCancelled()
    }

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
        ledController.shutdown()
    }

    private fun setupListeners() {
        Log.d(TAG, "setupListeners configuring")
        cardInfoListener.onCardInfo = ::handleCardInfo
        cardInfoListener.onMultipleCards = {
            Log.d(TAG, "cardInfoListener.onMultipleCards invoked")
            transactionListener?.onMultipleCardsDetected()
            transactionRunning.set(false)
            ledController.onError()
        }
        cardInfoListener.onSwipeIncorrect = {
            Log.d(TAG, "cardInfoListener.onSwipeIncorrect invoked")
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
            if (cardInfo?.cardExistslot == CardSlotTypeEnum.RF) {
                beeper.onContactlessError()
            }
            transactionListener?.onError("Card read failed with code $retCode")
            return
        }

        currentCardInfo = cardInfo
        Log.d(TAG, "handleCardInfo currentCardInfo updated")
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
                transactionListener?.onError("Unsupported card slot: ${cardInfo.cardExistslot}")
            }
        }
    }

    private fun startEmvProcess(cardInfo: CardInfoEntity) {
        Log.d(TAG, "startEmvProcess slot=${cardInfo.cardExistslot}")
        val request = currentRequest ?: return
        val emvConfig = buildTransConfiguration(request, cardInfo.cardExistslot)
        logEmvConfig("startEmvProcess config", emvConfig)
        val result = emvHandler?.emvProcess(emvConfig, emvProcessListener)
        if (result != SdkResult.Success) {
            transactionRunning.set(false)
            ledController.onError()
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
            emvProcessFlowEnum = EmvProcessFlowEnum.EMV_PROCESS_FLOW_STANDARD
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
        try {
            val aid = handler.getTlv(byteArrayOf(0x4F.toByte()), EmvDataSourceEnum.FROM_KERNEL)
            val dedicatedFileName = handler.getTlv(byteArrayOf(0x84.toByte()), EmvDataSourceEnum.FROM_KERNEL)
            val appLabel = handler.getTlv(byteArrayOf(0x50.toByte()), EmvDataSourceEnum.FROM_KERNEL)
            val preferredName = handler.getTlv(byteArrayOf(0x9F.toByte(), 0x12.toByte()), EmvDataSourceEnum.FROM_KERNEL)
            val slot = currentCardInfo?.cardExistslot
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
                configureTerminalCapabilities(handler, selectedAid, slot == CardSlotTypeEnum.RF)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to handle OnTransInitBeforeGPO", error)
        } finally {
            try {
                Log.d(TAG, "EMV_STEP onSetTransInitBeforeGPOResponse continue=true")
                handler.onSetTransInitBeforeGPOResponse(true)
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to acknowledge OnTransInitBeforeGPO", error)
            }
        }
    }

    private fun configureContactlessParameters(handler: EmvHandler2, aid: ByteArray) {
        val aidHex = ByteUtils.byteArray2HexString(aid)?.uppercase(Locale.US) ?: return
        val onlinePinEnabled = isOnlinePinEnabledForContactlessAid(aidHex)
        Log.d(TAG, "configureContactlessParameters aid=$aidHex onlinePinEnabled=$onlinePinEnabled")
        when {
            aidHex.contains("A000000004") -> configPaypassParameter(handler, aid, onlinePinEnabled)
            aidHex.contains("A000000003") -> configPaywaveParameters(handler, onlinePinEnabled)
            aidHex.contains("A000000025") -> configExpressPayParameter(handler)
            aidHex.contains("A000000152") -> {
                configDpasParameter(handler, onlinePinEnabled)
                contactlessDiscoverCard = true
            }
            aidHex.contains("A000000541") -> {
                // PURE contactless configuration not required for current deployment.
            }
            aidHex.contains("A000000065") -> configJcbContactlessParameter(handler)
            aidHex.contains("A000000333010108") -> configUnionPayParameter(handler)
        }
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
    ) {
        val aidHex = ByteUtils.byteArray2HexString(aid)?.uppercase(Locale.US) ?: return
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
            return
        }
        val tag9F33 = byteArrayOf(0x9F.toByte(), 0x33.toByte())
        val current = handler.getTlv(tag9F33, EmvDataSourceEnum.FROM_KERNEL)
        val configured = EmvTerminalCapabilities.apply9F33(current, profile) ?: run {
            Log.w(
                TAG,
                "Unable to configure 9F33 for interface=$interfaceType aid=$aidHex: " +
                    "kernel value is missing and the profile has no base value",
            )
            return
        }
        handler.setTlv(tag9F33, configured)
        Log.i(
            TAG,
            "EMV_STEP 9F33 interface=$interfaceType aid=$aidHex " +
                "before=${current?.let { ByteUtils.byteArray2HexString(it) }} " +
                "after=${ByteUtils.byteArray2HexString(configured)} " +
                "controlled=%02X enabled=%02X".format(
                    profile.controlledCvmMask,
                    profile.enabledCvmMask,
                ),
        )
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

    private fun configPaywaveParameters(handler: EmvHandler2, onlinePinEnabled: Boolean) {
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
        val ttq = ByteUtils.hexString2ByteArray("36004000")
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
    ) {
        Log.d(
            TAG,
            "configPaypassParameter aid=${ByteUtils.byteArray2HexString(aid)} " +
                "transType=${currentRequest?.transactionType} onlinePinEnabled=$onlinePinEnabled",
        )
        val tagDf811b = byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x1B.toByte())
        handler.setTlv(tagDf811b, byteArrayOf(0xB0.toByte()))

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
                ByteUtils.hexString2ByteArray("4C7A800000000000")?.let {
                    handler.setTlv(byteArrayOf(0x9F.toByte(), 0x1D.toByte()), it)
                }
                handler.setTlv(
                    byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x18.toByte()),
                    byteArrayOf(applyOnlinePinCvmCapability(0x40, onlinePinEnabled).toByte()),
                )
                handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x19.toByte()), byteArrayOf(0x08.toByte()))
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
                ByteUtils.hexString2ByteArray("6C7A800000000000")?.let {
                    handler.setTlv(byteArrayOf(0x9F.toByte(), 0x1D.toByte()), it)
                }
                handler.setTlv(
                    byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x18.toByte()),
                    byteArrayOf(applyOnlinePinCvmCapability(0x60, onlinePinEnabled).toByte()),
                )
                handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x19.toByte()), byteArrayOf(0x08.toByte()))
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
                ByteUtils.hexString2ByteArray("487A800000000000")?.let {
                    handler.setTlv(byteArrayOf(0x9F.toByte(), 0x1D.toByte()), it)
                }
                handler.setTlv(
                    byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x18.toByte()),
                    byteArrayOf(applyOnlinePinCvmCapability(0x40, onlinePinEnabled).toByte()),
                )
                handler.setTlv(byteArrayOf(0xDF.toByte(), 0x81.toByte(), 0x19.toByte()), byteArrayOf(0x08.toByte()))
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

    private fun configDpasParameter(handler: EmvHandler2, onlinePinEnabled: Boolean) {
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
        val ttq = ByteUtils.hexString2ByteArray("36A04000")
        if (kernelTTQ != null && kernelTTQ.size >= 4 && ttq != null && ttq.size >= 4) {
            kernelTTQ[0] = applyOnlinePinTtqCapability(ttq[0].toInt(), onlinePinEnabled).toByte()
            kernelTTQ[2] = ttq[2]
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

    private fun launchPinEntryActivity(
        pan: String,
        isOnlinePin: Boolean,
        scheme: OnlinePinScheme?,
        keyIndex: Int,
        compatibleAcquirerIds: Set<String>,
    ) {
        Log.d(TAG, "launchPinEntryActivity pan=${maskPan(pan)} isOnlinePin=$isOnlinePin")
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
        }
        context.startActivity(intent)
    }

    private fun handleEmvFinish(resultCode: Int, result: EmvProcessResultEntity?) {
        Log.d(TAG, "handleEmvFinish resultCode=$resultCode hasResult=${result != null}")
        clearPendingApplicationSelection()
        transactionRunning.set(false)
        transactionListener?.onTransactionFinished(resultCode, result)
        if (currentCardInfo?.cardExistslot == CardSlotTypeEnum.RF) {
            if (resultCode == SdkResult.Success) {
                beeper.onContactlessSuccess()
            } else {
                beeper.onContactlessError()
            }
        }
        ledController.onTransactionFinished()
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
        private const val TAG = "NexgoApi"
        private const val ONLINE_PIN_CVM_CAPABILITY_MASK = 0x40
        private const val ONLINE_PIN_TTQ_CAPABILITY_MASK = 0x04
        private val TAG_TERMINAL_COUNTRY_CODE = byteArrayOf(0x9F.toByte(), 0x1A.toByte())
        private val TAG_TRANSACTION_CURRENCY_CODE = byteArrayOf(0x5F.toByte(), 0x2A.toByte())

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
