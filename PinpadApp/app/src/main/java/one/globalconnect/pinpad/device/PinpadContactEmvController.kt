package one.globalconnect.pinpad.device

import android.content.Context
import android.util.Log
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.pinpad.OnPinPadInputListener
import com.nexgo.oaf.apiv3.device.pinpad.PinPad
import com.nexgo.oaf.apiv3.device.pinpad.PinPadKeyCode
import com.nexgo.oaf.apiv3.device.pinpad.PinPadTypeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinKeyboardModeEnum
import com.nexgo.oaf.apiv3.device.reader.CardInfoEntity
import com.nexgo.oaf.apiv3.device.reader.CardReader
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import com.nexgo.oaf.apiv3.device.reader.OnCardInfoListener
import com.nexgo.oaf.apiv3.emv.AidEntity
import com.nexgo.oaf.apiv3.emv.AidEntryModeEnum
import com.nexgo.oaf.apiv3.emv.CandidateAppInfoEntity
import com.nexgo.oaf.apiv3.emv.EmvDataSourceEnum
import com.nexgo.oaf.apiv3.emv.EmvHandler2
import com.nexgo.oaf.apiv3.emv.EmvEntryModeEnum
import com.nexgo.oaf.apiv3.emv.EmvOnlineResultEntity
import com.nexgo.oaf.apiv3.emv.EmvProcessFlowEnum
import com.nexgo.oaf.apiv3.emv.EmvProcessResultEntity
import com.nexgo.oaf.apiv3.emv.EmvTransConfigurationEntity
import com.nexgo.oaf.apiv3.emv.OnEmvProcessListener2
import com.nexgo.oaf.apiv3.emv.PromptEnum
import one.globalconnect.pinpad.BuildConfig
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.model.PinpadTransactionDisplay
import one.globalconnect.pinpad.ui.ContactlessLedState
import one.globalconnect.pinpad.ui.PinpadDisplayController
import one.globalconnect.pinpad.ui.PinpadDisplayState
import one.globalconnect.pinpad.ui.SensoryBrand
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PinpadContactEmvController(
    context: Context,
    private val deviceEngine: DeviceEngine,
    private val onlinePinEntryStarter: ((EmvOnlinePinRequest, (EmvOnlinePinResult) -> Unit) -> Boolean)? = null,
) {
    private val store = PinpadEmvConfigStore(context)
    private val sequencePrefs = context.getSharedPreferences(EMV_SEQUENCE_PREFS, Context.MODE_PRIVATE)
    private val contactSearchRunning = AtomicBoolean(false)
    private val emvConfigReloadRequired = AtomicBoolean(true)
    private val emvConfigLoadLock = Any()
    private val terminalPacketTlvs = linkedMapOf<Int, String>()
    private val aidPacketTlvs = linkedMapOf<Int, String>()
    private val pcdAidPacketTlvs = linkedMapOf<Int, String>()
    private val pendingCapkHeaders = linkedMapOf<String, PendingCapkHeader>()
    private var pendingAid: String? = null
    private var pendingAidTotalPackets: Int? = null
    private var pendingPcdAid: String? = null
    private var pendingPcdAidTotalPackets: Int? = null
    private var runtimeTlvHex: String = ""
    private var transactionTlvHex: String = ""
    private var onlineAuthorizationTlvHex: String = ""
    private var onlinePinTlvHex: String = ""
    private var reversalTlvHex: String = ""
    private val batchRecords = ArrayDeque<String>()
    private val pendingIssuerScripts = mutableListOf<String>()
    private val transactionRunning = AtomicBoolean(false)
    private val onlineAuthorizationPending = AtomicBoolean(false)
    private val offlinePinRunning = AtomicBoolean(false)
    @Volatile private var lastOnlineApproved: Boolean? = null
    @Volatile private var pendingAppSelectCompleted: AtomicBoolean? = null
    @Volatile private var pendingAppSelectResult: ((String) -> Unit)? = null
    @Volatile private var pendingTransactionCompleted: AtomicBoolean? = null
    @Volatile private var pendingTransactionResult: ((EmvCommandResult) -> Unit)? = null
    @Volatile private var activeTransactionType: Byte = DEFAULT_EMV_TRANS_TYPE
    @Volatile private var activeEntryMode: EmvEntryModeEnum = EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACT
    @Volatile private var t31ReadDataReady: CountDownLatch? = null
    @Volatile private var lastTransactionSequenceHex: String = ""
    @Volatile private var pendingT11ApplicationSelection: SelectedApplicationSelection? = null

    private val emvHandler: EmvHandler2 by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        deviceEngine.getEmvHandler2(EMV_APP_ID).also {
            it.emvDebugLog(BuildConfig.EMV_SDK_DEBUG_LOG_ENABLED)
            PinpadTraceLog.device("EMV SDK debug log enabled=${BuildConfig.EMV_SDK_DEBUG_LOG_ENABLED}")
        }
    }
    private val cardReader: CardReader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        deviceEngine.cardReader
    }
    private val pinPad: PinPad by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        deviceEngine.pinPad.also { pinPad ->
            PinpadTraceLog.device("EMV initPinPad type=INTERNAL")
            runCatching { pinPad.initPinPad(PinPadTypeEnum.INTERNAL) }
                .onSuccess { PinpadTraceLog.device("EMV initPinPad result=${NexgoSdkResultNames.format(it)}") }
                .onFailure { PinpadTraceLog.device("EMV initPinPad failed=${it.message}") }
        }
    }

    fun applyStoredConfiguration() {
        reloadStoredConfiguration("startup")
    }

    private fun reloadStoredConfiguration(reason: String): Boolean {
        synchronized(emvConfigLoadLock) {
            return reloadStoredConfigurationLocked(reason)
        }
    }

    private fun markEmvConfigReloadRequired(reason: String) {
        emvConfigReloadRequired.set(true)
        PinpadTraceLog.device("EMV config reload required reason=$reason")
    }

    private fun ensureEmvConfigurationLoaded(reason: String): Boolean {
        if (!emvConfigReloadRequired.get()) return true
        synchronized(emvConfigLoadLock) {
            if (!emvConfigReloadRequired.get()) return true
            return reloadStoredConfigurationLocked(reason)
        }
    }

    private fun reloadStoredConfigurationLocked(reason: String): Boolean {
        val terminalTlv = store.terminalConfigTlv()
        PinpadTraceLog.device(
            "EMV config reload start reason=$reason terminal=${terminalTlv.isNotBlank()} " +
                "capks=${store.capkTlvs().size} contactAids=${store.aidTlvs().size} " +
                "pcdAids=${store.pcdAidTlvs().size} pcdDrl=${store.pcdDrlTlvs().size}",
        )
        val terminalOk = terminalTlv.isBlank() || applyTerminalConfig(terminalTlv)
        val capksOk = applyCapkList()
        val aidsOk = applyAidList()
        val success = terminalOk && capksOk && aidsOk
        if (success) {
            emvConfigReloadRequired.set(false)
        }
        PinpadTraceLog.device("EMV config reload finish reason=$reason success=$success")
        return success
    }

    fun loadTerminalConfiguration(payload: String): EmvCommandResult {
        val packet = PinpadEmvDataObjects.parsePacket(payload) ?: return EmvCommandResult.failure('2')
        val tlv = packet.tlvHex ?: return EmvCommandResult.failure('3')
        if (packet.packetNo == 1) terminalPacketTlvs.clear()
        terminalPacketTlvs[packet.packetNo] = tlv
        if (packet.isFinal) {
            val packetTlvs = (1..packet.totalPackets).map { terminalPacketTlvs[it] }
            if (packetTlvs.any { it == null }) return EmvCommandResult.failure('2')
            val combined = packetTlvs.filterNotNull().joinToString(separator = "")
            store.setTerminalConfigTlv(combined)
            markEmvConfigReloadRequired("terminal config loaded")
            PinpadTraceLog.device("EMV terminal assignments ${formatTerminalAssignmentPreview()}")
            terminalPacketTlvs.clear()
        }
        PinpadTraceLog.device("EMV terminal packet=${packet.packetNo}/${packet.totalPackets} final=${packet.isFinal}")
        return EmvCommandResult.ok()
    }

    fun loadApplicationConfiguration(payload: String): EmvCommandResult {
        val packet = PinpadEmvDataObjects.parsePacket(
            payload,
            hasAidInFirstPacket = true,
            inferredPacketNo = nextAidPacketNo(),
            inferredTotalPackets = pendingAidTotalPackets,
        )
            ?: return EmvCommandResult.failure('2')
        val tlv = packet.tlvHex ?: return EmvCommandResult.failure('3')
        if (packet.packetNo == 1) {
            aidPacketTlvs.clear()
            pendingAid = packet.aid ?: return EmvCommandResult.failure('2')
            pendingAidTotalPackets = packet.totalPackets
        }
        aidPacketTlvs[packet.packetNo] = tlv
        if (packet.isFinal) {
            val aid = pendingAid ?: return EmvCommandResult.failure('2')
            val packetTlvs = (1..packet.totalPackets).map { aidPacketTlvs[it] }
            if (packetTlvs.any { it == null }) return EmvCommandResult.failure('2')
            val combinedBody = packetTlvs.filterNotNull().joinToString(separator = "")
            val aidTlv = PinpadEmvDataObjects.encodeTlv("9F06", aid) ?: return EmvCommandResult.failure('3')
            store.setAidTlv(aid, aidTlv + combinedBody)
            markEmvConfigReloadRequired("contact AID loaded id=$aid")
            aidPacketTlvs.clear()
            pendingAid = null
            pendingAidTotalPackets = null
        }
        PinpadTraceLog.device("EMV T05 packet=${packet.packetNo}/${packet.totalPackets} aid=${packet.aid ?: pendingAid}")
        return EmvCommandResult.ok()
    }

    fun loadPcdApplicationConfiguration(payload: String): EmvCommandResult {
        val packet = PinpadEmvDataObjects.parsePcdApplicationPacket(
            payload,
            inferredPacketNo = nextPcdAidPacketNo(),
            inferredTotalPackets = pendingPcdAidTotalPackets,
        )
            ?: return EmvCommandResult.failure('2')
        val tlv = packet.tlvHex ?: return EmvCommandResult.failure('3')
        if (packet.packetNo == 1) {
            pcdAidPacketTlvs.clear()
            pendingPcdAid = packet.aid ?: return EmvCommandResult.failure('2')
            pendingPcdAidTotalPackets = packet.totalPackets
            PinpadTraceLog.device(
                "EMV T55 PCD header aid=${packet.aid} txn=${packet.txn} kernelId=${packet.kernelId}",
            )
        }
        pcdAidPacketTlvs[packet.packetNo] = tlv
        if (packet.isFinal) {
            val aid = pendingPcdAid ?: return EmvCommandResult.failure('2')
            val packetTlvs = (1..packet.totalPackets).map { pcdAidPacketTlvs[it] }
            if (packetTlvs.any { it == null }) return EmvCommandResult.failure('2')
            val combinedBody = packetTlvs.filterNotNull().joinToString(separator = "")
            val aidTlv = PinpadEmvDataObjects.encodeTlv("9F06", aid) ?: return EmvCommandResult.failure('3')
            store.setPcdAidTlv(aid, aidTlv + combinedBody)
            markEmvConfigReloadRequired("contactless AID loaded id=$aid")
            pcdAidPacketTlvs.clear()
            pendingPcdAid = null
            pendingPcdAidTotalPackets = null
        }
        PinpadTraceLog.device("EMV T55 packet=${packet.packetNo}/${packet.totalPackets} aid=${packet.aid ?: pendingPcdAid}")
        return EmvCommandResult.ok()
    }

    fun loadCapk(payload: String, replaceExisting: Boolean = true): EmvCapkResult {
        val opCode = payload.firstOrNull() ?: return EmvCapkResult('0', EmvCommandResult.failure('2'))
        return when (opCode) {
            '1' -> loadCapkHeader(payload, replaceExisting)
            '2' -> loadCapkModulus(payload)
            else -> EmvCapkResult(opCode, EmvCommandResult.failure('2'))
        }
    }

    fun loadDataFormatTable(payload: String): EmvCommandResult {
        val clear = payload.firstOrNull()
        if (clear !in setOf('0', '1')) return EmvCommandResult.failure('2')
        val parsed = PinpadEmvDataObjects.parseDataFormatDefinitions(payload) ?: return EmvCommandResult.failure('2')
        val next = if (clear == '1') parsed else store.dataFormatDefinitions() + parsed
        store.setDataFormatDefinitions(next)
        PinpadTraceLog.device("EMV T07 dataFormats=${next.size} clear=$clear")
        PinpadTraceLog.device("EMV T07 definitions ${formatDataFormatPreview(next)}")
        return EmvCommandResult.ok()
    }

    fun queryConfigIds(configType: Char): EmvConfigQuery {
        val ids = when (configType) {
            '1' -> store.capkTlvs().keys.sorted()
            '2' -> store.aidTlvs().keys.sorted()
            else -> emptyList()
        }
        val status = when {
            configType !in setOf('1', '2') -> '1'
            ids.isEmpty() -> '1'
            else -> '0'
        }
        return EmvConfigQuery(configType, status, ids)
    }

    fun queryPcdConfigIds(configType: Char): EmvConfigQuery {
        val ids = when (configType) {
            '1' -> store.capkTlvs().keys.sorted()
            '2' -> store.pcdAidTlvs().keys.sorted()
            else -> emptyList()
        }
        val status = when {
            configType !in setOf('1', '2') -> '2'
            ids.isEmpty() -> '1'
            else -> '0'
        }
        return EmvConfigQuery(configType, status, ids)
    }

    fun deleteConfig(payload: String): EmvConfigDelete {
        val configType = payload.firstOrNull() ?: return EmvConfigDelete('0', '2', emptyList())
        val ids = payload.drop(1).trimStart(SUB).split(FS).map { it.trim() }.filter { it.isNotEmpty() }
        if (configType !in setOf('1', '2') || ids.isEmpty()) return EmvConfigDelete(configType, '2', emptyList())
        val deleted = if (configType == '1') {
            store.removeCapkTlvs(ids)
        } else {
            store.removeAidTlvs(ids)
        }
        if (deleted.any { it }) {
            markEmvConfigReloadRequired("contact config deleted type=$configType")
        }
        val status = if (deleted.any { it }) '0' else '3'
        return EmvConfigDelete(configType, status, deleted)
    }

    fun deletePcdConfig(payload: String): EmvConfigDelete {
        val configType = payload.firstOrNull() ?: return EmvConfigDelete('0', '2', emptyList())
        val ids = payload.drop(1).trimStart(SUB).split(FS).map { it.trim() }.filter { it.isNotEmpty() }
        if (configType !in setOf('1', '2') || ids.isEmpty()) return EmvConfigDelete(configType, '2', emptyList())
        val deleted = if (configType == '1') {
            store.removeCapkTlvs(ids)
        } else {
            store.removePcdAidTlvs(ids)
        }
        if (deleted.any { it }) {
            markEmvConfigReloadRequired("contactless config deleted type=$configType")
        }
        val status = if (deleted.any { it }) '0' else '0'
        return EmvConfigDelete(configType, status, deleted)
    }

    fun housekeepPcdConfig(payload: String): EmvCommandResult {
        val configType = payload.trimStart(SUB).firstOrNull()
        return if (configType in setOf('1', '2', null)) EmvCommandResult.ok() else EmvCommandResult.failure('2')
    }

    fun loadPcdDrlConfiguration(payload: String): EmvCommandResult {
        val packet = PinpadEmvDataObjects.parsePcdDrlPacket(payload) ?: return EmvCommandResult.failure('2')
        store.setPcdDrlTlv(packet.aid, packet.tlvHex)
        markEmvConfigReloadRequired("contactless DRL loaded id=${packet.aid}")
        PinpadTraceLog.device("EMV T5F PCD DRL aid=${packet.aid} tlvChars=${packet.tlvHex.length}")
        return EmvCommandResult.ok()
    }

    fun deletePcdDrlConfiguration(): EmvCommandResult {
        store.clearPcdDrlTlvs()
        markEmvConfigReloadRequired("contactless DRL cleared")
        PinpadTraceLog.device("EMV T5H PCD DRL cleared")
        return EmvCommandResult.ok()
    }

    fun overwriteTransactionData(payload: String): EmvCommandResult {
        val tlv = PinpadEmvDataObjects.parseRuntimeDataObjects(payload) ?: return EmvCommandResult.failure('3')
        runtimeTlvHex = tlv
        applyRuntimeTlv(tlv)
        PinpadTraceLog.device("EMV T1D runtimeTlvChars=${tlv.length}")
        return EmvCommandResult.ok()
    }

    fun queryTransactionData(payload: String): String {
        val requestedTags = PinpadEmvDataObjects.parseRequestedTags(payload)
        val queryTlv = mergeTlvs(
            runtimeTlvHex,
            transactionTlvHex,
            onlineAuthorizationTlvHex,
            reversalTlvHex,
            collectKnownKernelTlvs(requestedTags),
        )
        val availableTags = PinpadEmvDataObjects.parseTlvRecords(queryTlv).map { it.tag }.toSet()
        val missingTags = requestedTags.filterNot { it.uppercase(Locale.US) in availableTags }
        val result = PinpadEmvDataObjects.tlvToDataObjectText(queryTlv, requestedTags)
        PinpadTraceLog.device(
            "EMV T21 requested=${requestedTags.joinToString(separator = ",")} " +
                "found=${requestedTags.size - missingTags.size}/${requestedTags.size} " +
                "missing=${missingTags.joinToString(separator = ",").ifBlank { "<none>" }} " +
                "payloadChars=${result.length}",
        )
        return result
    }

    fun startApplicationSelect(
        transactionDisplay: PinpadTransactionDisplay? = null,
        onResult: (String) -> Unit,
    ): Boolean {
        if (!ensureEmvConfigurationLoaded("before T11")) return false
        if (!contactSearchRunning.compareAndSet(false, true)) {
            PinpadTraceLog.device("EMV contact search already running")
            return true
        }
        pendingT11ApplicationSelection = null
        val completed = AtomicBoolean(false)
        pendingAppSelectCompleted = completed
        pendingAppSelectResult = onResult
        PinpadDisplayController.showInsertCard(transactionDisplay)
        beepForCardPrompt("T11")
        val listener = object : OnCardInfoListener {
            override fun onCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
                PinpadTraceLog.device("EMV contact onCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot}")
                if (retCode == SdkResult.Success && cardInfo?.cardExistslot == CardSlotTypeEnum.ICC1) {
                    PinpadDisplayController.showProcessing()
                    val started = startReadAppDataApplicationSelect(transactionDisplay, completed, onResult)
                    if (!started) {
                        completeContactApplicationSelect(completed, onResult, T12_FATAL_ERROR)
                    }
                } else {
                    completeContactApplicationSelect(completed, onResult, T12_FATAL_ERROR)
                }
            }

            override fun onSwipeIncorrect() {
                PinpadTraceLog.device("EMV contact onSwipeIncorrect")
            }

            override fun onMultipleCards() {
                PinpadTraceLog.device("EMV contact onMultipleCards")
                completeContactApplicationSelect(completed, onResult, T12_FATAL_ERROR)
            }
        }
        val result = runCatching {
            cardReader.searchCard(hashSetOf(CardSlotTypeEnum.ICC1), CONTACT_SEARCH_TIMEOUT_SECONDS, listener)
        }.onFailure {
            contactSearchRunning.set(false)
            clearPendingAppSelect()
            Log.w(TAG, "Unable to start contact card search", it)
            PinpadTraceLog.device("EMV contact search failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        if (result != SdkResult.Success) {
            contactSearchRunning.set(false)
            clearPendingAppSelect()
            PinpadDisplayController.showIdle()
            PinpadTraceLog.device("EMV contact search sdkResult=$result")
            return false
        }
        return true
    }

    fun startCardDataRead(onApplicationSelect: (String) -> Unit): Boolean {
        if (!ensureEmvConfigurationLoaded("before T31")) return false
        if (!transactionRunning.compareAndSet(false, true)) {
            PinpadTraceLog.device("EMV T31 data read already running")
            return true
        }
        transactionTlvHex = ""
        onlineAuthorizationTlvHex = ""
        onlinePinTlvHex = ""
        reversalTlvHex = ""
        lastOnlineApproved = null
        activeTransactionType = DEFAULT_EMV_TRANS_TYPE
        t31ReadDataReady = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        pendingAppSelectCompleted = completed
        pendingAppSelectResult = onApplicationSelect
        PinpadDisplayController.showInsertCard()
        val listener = object : OnCardInfoListener {
            override fun onCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
                PinpadTraceLog.device("EMV T31 onCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot}")
                if (retCode == SdkResult.Success && cardInfo?.cardExistslot == CardSlotTypeEnum.ICC1) {
                    PinpadDisplayController.showProcessing()
                    val started = startReadAppDataCardDataRead(completed, onApplicationSelect)
                    if (!started) {
                        signalT31ReadDataReady("T31 start failed")
                        transactionRunning.set(false)
                        completeContactApplicationSelect(completed, onApplicationSelect, T12_FATAL_ERROR, "T31")
                    }
                } else {
                    signalT31ReadDataReady("T31 card search no ICC")
                    transactionRunning.set(false)
                    completeContactApplicationSelect(completed, onApplicationSelect, T12_FATAL_ERROR, "T31")
                }
            }

            override fun onSwipeIncorrect() {
                PinpadTraceLog.device("EMV T31 onSwipeIncorrect")
            }

            override fun onMultipleCards() {
                PinpadTraceLog.device("EMV T31 onMultipleCards")
                signalT31ReadDataReady("T31 multiple cards")
                transactionRunning.set(false)
                completeContactApplicationSelect(completed, onApplicationSelect, T12_FATAL_ERROR, "T31")
            }
        }
        val result = runCatching {
            cardReader.searchCard(hashSetOf(CardSlotTypeEnum.ICC1), CONTACT_SEARCH_TIMEOUT_SECONDS, listener)
        }.onFailure {
            signalT31ReadDataReady("T31 search exception")
            transactionRunning.set(false)
            clearPendingAppSelect()
            Log.w(TAG, "Unable to start T31 contact card search", it)
            PinpadTraceLog.device("EMV T31 contact search failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        if (result != SdkResult.Success) {
            signalT31ReadDataReady("T31 search sdkResult=$result")
            transactionRunning.set(false)
            clearPendingAppSelect()
            PinpadDisplayController.showIdle()
            PinpadTraceLog.device("EMV T31 contact search sdkResult=$result")
            return false
        }
        return true
    }

    fun cancelTransaction(
        sendResult: Boolean = false,
        showCancelMessage: Boolean = true,
    ): Boolean {
        val wasActive = contactSearchRunning.get() ||
            transactionRunning.get() ||
            onlineAuthorizationPending.get() ||
            offlinePinRunning.get() ||
            pendingAppSelectCompleted != null ||
            pendingTransactionCompleted != null
        if (!wasActive) {
            PinpadTraceLog.device("EMV cancel ignored; no active transaction")
            return false
        }
        runCatching { cardReader.stopSearch() }
            .onFailure {
                Log.w(TAG, "Unable to stop contact card search", it)
                PinpadTraceLog.device("EMV contact stopSearch failed=${it.message}")
            }
        if (offlinePinRunning.getAndSet(false)) {
            runCatching { pinPad.cancelInput() }
                .onFailure { PinpadTraceLog.device("EMV offline PIN cancelInput failed=${it.message}") }
        }
        runCatching { emvHandler.emvProcessCancel() }
            .onFailure { PinpadTraceLog.device("EMV cancel failed=${it.message}") }
        contactSearchRunning.set(false)
        transactionRunning.set(false)
        onlineAuthorizationPending.set(false)
        if (sendResult) {
            pendingAppSelectCompleted?.let { completed ->
                pendingAppSelectResult?.let { onResult ->
                    completeContactApplicationSelect(completed, onResult, T12_CANCELLED_ERROR)
                }
            }
            pendingTransactionCompleted?.let { completed ->
                pendingTransactionResult?.let { onResult ->
                    completeContactTransaction(
                        onResult,
                        EmvCommandResult.failure('1', EMV_CANCELLED_ERROR),
                        completed,
                    )
                }
            }
            if (showCancelMessage) {
                PinpadDisplayController.showOperationCancelledThenIdle()
            }
        } else {
            clearPendingAppSelect()
            clearPendingTransaction()
            PinpadDisplayController.showIdle()
        }
        return true
    }

    fun startTransaction(
        payload: String,
        sourceCommand: String,
        cardSlot: CardSlotTypeEnum = CardSlotTypeEnum.ICC1,
        entryMode: EmvEntryModeEnum = EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACT,
        transactionDisplay: PinpadTransactionDisplay? = null,
        onResult: (EmvCommandResult) -> Unit,
    ): Boolean {
        if (!ensureEmvConfigurationLoaded("before $sourceCommand")) return false
        if (!transactionRunning.compareAndSet(false, true)) {
            PinpadTraceLog.device("EMV $sourceCommand transaction already running")
            return true
        }
        transactionTlvHex = ""
        onlineAuthorizationTlvHex = ""
        onlinePinTlvHex = ""
        reversalTlvHex = ""
        lastOnlineApproved = null
        val completed = AtomicBoolean(false)
        pendingTransactionCompleted = completed
        pendingTransactionResult = onResult
        val request = ContactTransactionRequest.parse(payload, transactionDisplay)
        val promptTransaction = transactionDisplay ?: PinpadTransactionDisplay.fromEmv(
            request.transactionType,
            request.currencyCode,
            request.amountAuthorized,
        )
        activeTransactionType = request.transactionType
        activeEntryMode = entryMode
        if (cardSlot == CardSlotTypeEnum.RF) {
            showContactlessFeedback(ContactlessLedState.Ready, CONTACTLESS_READY_BEEP_MS)
            PinpadDisplayController.showTapCard(promptTransaction)
        } else {
            PinpadDisplayController.showInsertCard(promptTransaction)
        }
        val listener = object : OnCardInfoListener {
            override fun onCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
                PinpadTraceLog.device("EMV $sourceCommand onCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot}")
                if (retCode == SdkResult.Success && cardInfo?.cardExistslot == cardSlot) {
                    if (cardSlot == CardSlotTypeEnum.RF) {
                        showContactlessFeedback(ContactlessLedState.Detected, CONTACTLESS_DETECTED_BEEP_MS)
                    }
                    PinpadDisplayController.showProcessing()
                    if (!startStandardEmvTransaction(request, sourceCommand, entryMode, completed, onResult)) {
                        completeContactTransaction(onResult, EmvCommandResult.failure('1', "00000000"), completed)
                    }
                } else {
                    completeContactTransaction(onResult, EmvCommandResult.failure('1', "00000000"), completed)
                }
            }

            override fun onSwipeIncorrect() {
                PinpadTraceLog.device("EMV $sourceCommand onSwipeIncorrect")
            }

            override fun onMultipleCards() {
                PinpadTraceLog.device("EMV $sourceCommand onMultipleCards")
                if (cardSlot == CardSlotTypeEnum.RF) {
                    showContactlessFeedback(ContactlessLedState.Error)
                }
                completeContactTransaction(onResult, EmvCommandResult.failure('1', "00000000"), completed)
            }
        }
        val result = runCatching {
            cardReader.searchCard(hashSetOf(cardSlot), request.timeoutSeconds, listener)
        }.onFailure {
            transactionRunning.set(false)
            clearPendingTransaction()
            Log.w(TAG, "Unable to start contact transaction search", it)
            PinpadTraceLog.device("EMV $sourceCommand contact search failed=${it.message}")
            showTransactionWarning(sourceCommand, "search exception")
        }.getOrDefault(SdkResult.Fail)
        if (result != SdkResult.Success) {
            transactionRunning.set(false)
            clearPendingTransaction()
            PinpadTraceLog.device("EMV $sourceCommand contact search sdkResult=$result")
            showTransactionWarning(sourceCommand, "search sdkResult=$result")
            return false
        }
        return true
    }

    fun completeOnlineAuthorization(payload: String): EmvCommandResult {
        if (!onlineAuthorizationPending.compareAndSet(true, false)) {
            PinpadTraceLog.device("EMV T17 no pending online authorization")
            return EmvCommandResult.failure('1', "00000000")
        }
        val response = HostOnlineResponse.parse(payload)
        lastOnlineApproved = response.approved
        val entity = EmvOnlineResultEntity().apply {
            setAuthCode(response.authCode)
            setRejCode(response.arc)
            setRecvField55(response.field55WithScripts(pendingIssuerScripts))
        }
        val retCode = if (response.hostReachable) SdkResult.Success else SdkResult.Fail
        runCatching { emvHandler.onSetOnlineProcResponse(retCode, entity) }
            .onSuccess {
                PinpadTraceLog.device(
                    "EMV T17 online response accepted retCode=$retCode arc=${response.arc} " +
                        "field55Bytes=${entity.recvField55?.size ?: 0}",
                )
                pendingIssuerScripts.clear()
            }
            .onFailure {
                onlineAuthorizationPending.set(true)
                PinpadTraceLog.device("EMV T17 online response failed=${it.message}")
                return EmvCommandResult.failure('1', "00000000")
            }
        return EmvCommandResult.ok()
    }

    fun addIssuerScript(payload: String): EmvCommandResult {
        val script = payload.trim().uppercase(Locale.US)
        if (script.isNotBlank() && !script.all { it.digitToIntOrNull(16) != null }) {
            return EmvCommandResult.failure('2')
        }
        if (script.isNotBlank()) pendingIssuerScripts += script
        PinpadTraceLog.device("EMV T19 issuerScripts=${pendingIssuerScripts.size}")
        return EmvCommandResult.ok()
    }

    fun clearTransactionLog(): EmvCommandResult {
        batchRecords.clear()
        transactionTlvHex = ""
        onlineAuthorizationTlvHex = ""
        reversalTlvHex = ""
        runCatching { emvHandler.clearLog() }
            .onFailure { PinpadTraceLog.device("EMV T23 clearLog failed=${it.message}") }
        PinpadTraceLog.device("EMV T23 transaction log cleared")
        return EmvCommandResult.ok()
    }

    fun nextBatchData(): String {
        val next = batchRecords.removeFirstOrNull()
        return if (next.isNullOrBlank()) "0" else "1$next"
    }

    fun onlineAuthorizationData(): String {
        if (onlineAuthorizationTlvHex.isBlank()) {
            cacheCurrentKernelTransactionData("T27 lazy collect")
        }
        return onlineAuthorizationTlvHex
    }

    fun reversalData(): String = reversalTlvHex

    fun forceCompleteNonEmvTransaction(): EmvCommandResult {
        waitForT31ReadDataIfNeeded()
        cacheCurrentKernelTransactionData("T3C force complete")
        runCatching { emvHandler.emvProcessCancel() }
            .onFailure { PinpadTraceLog.device("EMV T3C cancel failed=${it.message}") }
        runCatching { cardReader.stopSearch() }
            .onFailure { PinpadTraceLog.device("EMV T3C stopSearch failed=${it.message}") }
        contactSearchRunning.set(false)
        transactionRunning.set(false)
        onlineAuthorizationPending.set(false)
        t31ReadDataReady = null
        PinpadDisplayController.showIdle()
        return EmvCommandResult.ok(transactionCode = RESULT_ONLINE_REQUEST, financialNeed = '0')
    }

    fun pinManagementUnsupported(): EmvCommandResult {
        PinpadTraceLog.device("EMV T37 PIN management is not implemented yet")
        return EmvCommandResult.failure('1', "00000000")
    }

    private fun startReadAppDataApplicationSelect(
        transactionDisplay: PinpadTransactionDisplay?,
        completed: AtomicBoolean,
        onResult: (String) -> Unit,
    ): Boolean {
        if (!applyContactAidListForTransaction()) return false
        val config = buildApplicationSelectTransConfiguration(transactionDisplay)
        logEmvConfig("T11 emvProcess config", config)
        val listener = object : OnEmvProcessListener2 {
            override fun onSelApp(
                appLabels: MutableList<String>?,
                candidateApps: MutableList<CandidateAppInfoEntity>?,
                isMandatory: Boolean,
            ) {
                PinpadTraceLog.device(
                    "EMV T11 onSelApp labels=${appLabels?.size ?: 0} " +
                        "candidates=${candidateApps?.size ?: 0} mandatory=$isMandatory",
                )
                handleApplicationSelection("T11", appLabels, isMandatory)
            }

            override fun onTransInitBeforeGPO() {
                PinpadTraceLog.device(
                    "EMV_STEP T11 onTransInitBeforeGPO " +
                        "4F=${kernelTlvHex(0x4F)} 84=${kernelTlvHex(0x84)} " +
                        "50=${kernelTlvHex(0x50)} 9F12=${kernelTlvHex(0x9F, 0x12)}",
                )
                val selectedAid = selectedAidFromKernel()
                if (selectedAid.isNullOrBlank()) {
                    PinpadTraceLog.device("EMV T11 selected AID missing at beforeGPO")
                } else {
                    completeContactApplicationSelect(completed, onResult, "0$selectedAid")
                }
                PinpadTraceLog.device("EMV_STEP T11 onSetTransInitBeforeGPOResponse continue=true")
                runCatching { emvHandler.onSetTransInitBeforeGPOResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV T11 beforeGPO response failed=${it.message}") }
                if (!selectedAid.isNullOrBlank()) {
                    runCatching { emvHandler.emvProcessCancel() }
                        .onFailure { PinpadTraceLog.device("EMV T11 cancel after app select failed=${it.message}") }
                }
            }

            override fun onConfirmCardNo(cardInfo: CardInfoEntity?) {
                PinpadTraceLog.device(
                    "EMV_STEP T11 onConfirmCardNo panLength=${cardInfo?.cardNo?.length ?: 0} " +
                        "tk1=${cardInfo?.tk1?.length ?: 0} tk2=${cardInfo?.tk2?.length ?: 0} " +
                        "tk3=${cardInfo?.tk3?.length ?: 0}",
                )
                PinpadTraceLog.device("EMV_STEP T11 onSetConfirmCardNoResponse confirmed=true")
                runCatching { emvHandler.onSetConfirmCardNoResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV T11 confirm card response failed=${it.message}") }
            }

            override fun onCardHolderInputPin(isOnlinePin: Boolean, leftTimes: Int) {
                PinpadTraceLog.device("EMV_STEP T11 unexpected onCardHolderInputPin online=$isOnlinePin left=$leftTimes")
                completeContactApplicationSelect(completed, onResult, T12_FATAL_ERROR)
                runCatching { emvHandler.emvProcessCancel() }
                    .onFailure { PinpadTraceLog.device("EMV T11 cancel after PIN request failed=${it.message}") }
            }

            override fun onContactlessTapCardAgain() {
                PinpadTraceLog.device("EMV_STEP T11 unexpected onContactlessTapCardAgain")
                completeContactApplicationSelect(completed, onResult, T12_FATAL_ERROR)
            }

            override fun onOnlineProc() {
                PinpadTraceLog.device("EMV_STEP T11 unexpected onOnlineProc")
                completeContactApplicationSelect(completed, onResult, T12_FATAL_ERROR)
                runCatching { emvHandler.emvProcessCancel() }
                    .onFailure { PinpadTraceLog.device("EMV T11 cancel after online proc failed=${it.message}") }
            }

            override fun onPrompt(prompt: PromptEnum?) {
                PinpadTraceLog.device("EMV_STEP T11 onPrompt prompt=${prompt?.name}")
            }

            override fun onRemoveCard() {
                PinpadTraceLog.device("EMV_STEP T11 onRemoveCard")
            }

            override fun onFinish(resultCode: Int, processResult: EmvProcessResultEntity?) {
                PinpadTraceLog.device(
                    "EMV_STEP T11 onFinish result=${NexgoSdkResultNames.format(resultCode)} " +
                        "hasResult=${processResult != null}",
                )
                val selectedAid = selectedAidFromKernel()
                val payload = t12PayloadFromApplicationSelectFinish(resultCode, selectedAid)
                completeContactApplicationSelect(completed, onResult, payload)
            }
        }
        val result = runCatching {
            emvHandler.emvProcess(config, listener)
        }.onFailure {
            Log.w(TAG, "Unable to start T11 EMV application select", it)
            PinpadTraceLog.device("EMV T11 emvProcess failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        PinpadTraceLog.device(
            "EMV T11 emvProcess result=${NexgoSdkResultNames.format(result)} " +
                "trace=${config.traceNo} amount=${config.transAmount} " +
                "country=${config.countryCode} currency=${config.currencyCode}",
        )
        return result == SdkResult.Success
    }

    private fun startReadAppDataCardDataRead(
        completed: AtomicBoolean,
        onApplicationSelect: (String) -> Unit,
    ): Boolean {
        if (!applyContactAidListForTransaction()) return false
        val request = ContactTransactionRequest.parse("")
        val config = buildStandardTransConfiguration(request, EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACT)
        logEmvConfig("T31 emvProcess config", config)
        val listener = object : OnEmvProcessListener2 {
            override fun onSelApp(
                appLabels: MutableList<String>?,
                candidateApps: MutableList<CandidateAppInfoEntity>?,
                isMandatory: Boolean,
            ) {
                PinpadTraceLog.device(
                    "EMV_STEP T31 onSelApp labels=${appLabels?.size ?: 0} " +
                        "candidates=${candidateApps?.size ?: 0} mandatory=$isMandatory",
                )
                handleApplicationSelection("T31", appLabels, isMandatory)
            }

            override fun onTransInitBeforeGPO() {
                PinpadTraceLog.device(
                    "EMV_STEP T31 onTransInitBeforeGPO " +
                        "4F=${kernelTlvHex(0x4F)} 84=${kernelTlvHex(0x84)} " +
                        "50=${kernelTlvHex(0x50)} 9F12=${kernelTlvHex(0x9F, 0x12)}",
                )
                val selectedAid = selectedAidFromKernel()
                if (selectedAid.isNullOrBlank()) {
                    PinpadTraceLog.device("EMV T31 selected AID missing at beforeGPO")
                    transactionRunning.set(false)
                    completeContactApplicationSelect(completed, onApplicationSelect, T12_FATAL_ERROR, "T31")
                    runCatching { emvHandler.emvProcessCancel() }
                        .onFailure { PinpadTraceLog.device("EMV T31 cancel after missing AID failed=${it.message}") }
                    return
                }
                completeContactApplicationSelect(completed, onApplicationSelect, "0$selectedAid", "T31")
                cacheCurrentKernelTransactionData("T31 beforeGPO")
                if (runtimeTlvHex.isNotBlank()) applyRuntimeTlv(runtimeTlvHex)
                runCatching { emvHandler.onSetTransInitBeforeGPOResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV T31 beforeGPO response failed=${it.message}") }
            }

            override fun onConfirmCardNo(cardInfo: CardInfoEntity?) {
                PinpadTraceLog.device(
                    "EMV_STEP T31 onConfirmCardNo panLength=${cardInfo?.cardNo?.length ?: 0} " +
                        "tk1=${cardInfo?.tk1?.length ?: 0} tk2=${cardInfo?.tk2?.length ?: 0} " +
                        "tk3=${cardInfo?.tk3?.length ?: 0}",
                )
                cacheCurrentKernelTransactionData("T31 confirmCardNo")
                runCatching { emvHandler.onSetConfirmCardNoResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV T31 confirm card response failed=${it.message}") }
            }

            override fun onCardHolderInputPin(isOnlinePin: Boolean, leftTimes: Int) {
                PinpadTraceLog.device("EMV_STEP T31 unexpected onCardHolderInputPin online=$isOnlinePin left=$leftTimes")
                cacheCurrentKernelTransactionData("T31 pin request")
                signalT31ReadDataReady("T31 pin request")
                runCatching { emvHandler.emvProcessCancel() }
                    .onFailure { PinpadTraceLog.device("EMV T31 cancel after PIN request failed=${it.message}") }
            }

            override fun onContactlessTapCardAgain() {
                PinpadTraceLog.device("EMV_STEP T31 unexpected onContactlessTapCardAgain")
            }

            override fun onOnlineProc() {
                cacheCurrentKernelTransactionData("T31 onlineProc")
                PinpadTraceLog.device("EMV_STEP T31 onOnlineProc cached onlineTlvChars=${onlineAuthorizationTlvHex.length}")
                signalT31ReadDataReady("T31 onlineProc")
            }

            override fun onPrompt(prompt: PromptEnum?) {
                PinpadTraceLog.device("EMV_STEP T31 onPrompt prompt=${prompt?.name}")
                runCatching { emvHandler.onSetPromptResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV T31 prompt response failed=${it.message}") }
            }

            override fun onRemoveCard() {
                PinpadTraceLog.device("EMV_STEP T31 onRemoveCard")
                runCatching { emvHandler.onSetRemoveCardResponse() }
                    .onFailure { PinpadTraceLog.device("EMV T31 remove card response failed=${it.message}") }
            }

            override fun onFinish(resultCode: Int, processResult: EmvProcessResultEntity?) {
                PinpadTraceLog.device(
                    "EMV_STEP T31 onFinish result=${NexgoSdkResultNames.format(resultCode)} " +
                        "scriptBytes=${processResult?.scriptResult?.size ?: 0}",
                )
                cacheCurrentKernelTransactionData("T31 finish")
                signalT31ReadDataReady("T31 finish")
                transactionRunning.set(false)
            }
        }
        val result = runCatching {
            emvHandler.emvProcess(config, listener)
        }.onFailure {
            Log.w(TAG, "Unable to start T31 EMV card data read", it)
            PinpadTraceLog.device("EMV T31 emvProcess failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        PinpadTraceLog.device(
            "EMV T31 emvProcess result=${NexgoSdkResultNames.format(result)} " +
                "trace=${config.traceNo} amount=${config.transAmount} " +
                "country=${config.countryCode} currency=${config.currencyCode}",
        )
        return result == SdkResult.Success
    }

    private fun waitForT31ReadDataIfNeeded(): Boolean {
        val latch = t31ReadDataReady ?: return false
        if (latch.count == 0L) return true
        PinpadTraceLog.device("EMV T3C waiting for T31 read data ${T31_FORCE_COMPLETE_WAIT_MS}ms")
        val completed = runCatching {
            latch.await(T31_FORCE_COMPLETE_WAIT_MS, TimeUnit.MILLISECONDS)
        }.onFailure {
            PinpadTraceLog.device("EMV T3C T31 read wait failed=${it.message}")
        }.getOrDefault(false)
        PinpadTraceLog.device("EMV T3C T31 read wait completed=$completed")
        return completed
    }

    private fun signalT31ReadDataReady(source: String) {
        val latch = t31ReadDataReady ?: return
        if (latch.count <= 0L) return
        PinpadTraceLog.device("EMV $source read data ready")
        latch.countDown()
    }

    private fun startStandardEmvTransaction(
        request: ContactTransactionRequest,
        sourceCommand: String,
        entryMode: EmvEntryModeEnum,
        completed: AtomicBoolean,
        onResult: (EmvCommandResult) -> Unit,
    ): Boolean {
        if (!applyAidListForTransaction(entryMode)) return false
        val config = buildStandardTransConfiguration(request, entryMode)
        logEmvConfig("$sourceCommand emvProcess config", config)
        var confirmedPan: String? = null
        val listener = object : OnEmvProcessListener2 {
            override fun onSelApp(
                appLabels: MutableList<String>?,
                candidateApps: MutableList<CandidateAppInfoEntity>?,
                isMandatory: Boolean,
            ) {
                PinpadTraceLog.device(
                    "EMV_STEP $sourceCommand onSelApp labels=${appLabels?.size ?: 0} " +
                        "candidates=${candidateApps?.size ?: 0} mandatory=$isMandatory",
                )
                handleApplicationSelection(sourceCommand, appLabels, isMandatory)
            }

            override fun onTransInitBeforeGPO() {
                PinpadTraceLog.device(
                    "EMV_STEP $sourceCommand onTransInitBeforeGPO " +
                        "4F=${kernelTlvHex(0x4F)} 84=${kernelTlvHex(0x84)} " +
                        "50=${kernelTlvHex(0x50)} 9F12=${kernelTlvHex(0x9F, 0x12)}",
                )
                if (entryMode == EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACTLESS) {
                    selectedAidFromKernel()?.let {
                        applyStoredContactlessKernelTlvs(it)
                        configureContactlessParameters(it, request.transactionType)
                    }
                }
                if (runtimeTlvHex.isNotBlank()) applyRuntimeTlv(runtimeTlvHex)
                runCatching { emvHandler.onSetTransInitBeforeGPOResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV $sourceCommand beforeGPO response failed=${it.message}") }
            }

            override fun onConfirmCardNo(cardInfo: CardInfoEntity?) {
                confirmedPan = cardInfo?.cardNo?.filter(Char::isDigit)?.takeIf { it.isNotBlank() }
                PinpadTraceLog.device(
                    "EMV_STEP $sourceCommand onConfirmCardNo panLength=${cardInfo?.cardNo?.length ?: 0}",
                )
                runCatching { emvHandler.onSetConfirmCardNoResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV $sourceCommand confirm card response failed=${it.message}") }
            }

            override fun onCardHolderInputPin(isOnlinePin: Boolean, leftTimes: Int) {
                PinpadTraceLog.device(
                    "EMV_STEP $sourceCommand onCardHolderInputPin online=$isOnlinePin left=$leftTimes scheme=${request.pinScheme}",
                )
                if (isOnlinePin) {
                    startOnlinePinEntryForEmv(request, sourceCommand, confirmedPan)
                } else {
                    startOfflinePinEntryForEmv(sourceCommand, leftTimes)
                }
            }

            override fun onContactlessTapCardAgain() {
                PinpadTraceLog.device("EMV_STEP $sourceCommand onContactlessTapCardAgain continue=true")
                showContactlessFeedback(ContactlessLedState.Error, CONTACTLESS_ERROR_BEEP_MS)
                runCatching { emvHandler.onSetContactlessTapCardResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV $sourceCommand tap again response failed=${it.message}") }
            }

            override fun onOnlineProc() {
                if (entryMode == EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACTLESS) {
                    showContactlessFeedback(ContactlessLedState.Processing, CONTACTLESS_PROCESSING_BEEP_MS)
                }
                onlineAuthorizationTlvHex = mergeTlvs(onlinePinTlvHex, runtimeTlvHex, collectKnownKernelTlvs(ONLINE_AUTH_TAGS))
                reversalTlvHex = mergeTlvs(runtimeTlvHex, collectKnownKernelTlvs(REVERSAL_TAGS))
                transactionTlvHex = mergeTlvs(transactionTlvHex, onlineAuthorizationTlvHex)
                onlineAuthorizationPending.set(true)
                PinpadTraceLog.device(
                    "EMV_STEP $sourceCommand onOnlineProc onlineTlvChars=${onlineAuthorizationTlvHex.length} " +
                        "onlinePinTlvChars=${onlinePinTlvHex.length}",
                )
                onResult(EmvCommandResult.ok(transactionCode = RESULT_ONLINE_REQUEST, financialNeed = '1'))
            }

            override fun onPrompt(prompt: PromptEnum?) {
                PinpadTraceLog.device("EMV_STEP $sourceCommand onPrompt prompt=${prompt?.name}")
                runCatching { emvHandler.onSetPromptResponse(true) }
                    .onFailure { PinpadTraceLog.device("EMV $sourceCommand prompt response failed=${it.message}") }
            }

            override fun onRemoveCard() {
                PinpadTraceLog.device("EMV_STEP $sourceCommand onRemoveCard")
                runCatching { emvHandler.onSetRemoveCardResponse() }
                    .onFailure { PinpadTraceLog.device("EMV $sourceCommand remove card response failed=${it.message}") }
            }

            override fun onFinish(resultCode: Int, processResult: EmvProcessResultEntity?) {
                PinpadTraceLog.device(
                    "EMV_STEP $sourceCommand onFinish result=${NexgoSdkResultNames.format(resultCode)} " +
                        "scriptBytes=${processResult?.scriptResult?.size ?: 0}",
                )
                val data = mergeTlvs(
                    runtimeTlvHex,
                    collectKnownKernelTlvs(COMPLETION_TAGS),
                    processResult?.scriptResult?.takeIf { it.isNotEmpty() }?.let {
                        PinpadEmvDataObjects.encodeTlv("9F5B", PinpadEmvDataObjects.run { it.toHex() })
                    }.orEmpty(),
                )
                transactionTlvHex = data
                reversalTlvHex = mergeTlvs(reversalTlvHex, data)
                if (data.isNotBlank()) batchRecords += data
                val result = resultFromSdkResult(resultCode, tlvValueHex(data, EMV_CID_TAG))
                completeContactTransaction(onResult, result, completed)
            }
        }
        val result = runCatching {
            emvHandler.emvProcess(config, listener)
        }.onFailure {
            Log.w(TAG, "Unable to start $sourceCommand EMV transaction", it)
            PinpadTraceLog.device("EMV $sourceCommand emvProcess failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        PinpadTraceLog.device(
            "EMV $sourceCommand emvProcess result=${NexgoSdkResultNames.format(result)} " +
                "trace=${config.traceNo} amount=${config.transAmount}",
        )
        return result == SdkResult.Success
    }

    private fun startOnlinePinEntryForEmv(
        request: ContactTransactionRequest,
        sourceCommand: String,
        confirmedPan: String?,
    ) {
        val starter = onlinePinEntryStarter
        val account = emvPinAccount(confirmedPan)
        if (starter == null || account.isBlank()) {
            PinpadTraceLog.device(
                "EMV_PIN $sourceCommand unable to start online=true " +
                    "starter=${starter != null} accountDigits=${account.length}",
            )
            respondToEmvPinRequest(sourceCommand, isConfirm = false, isBypass = false)
            return
        }
        val pinRequest = EmvOnlinePinRequest(
            sourceCommand = sourceCommand,
            account = account,
            sessionKey = request.encryptedSessionKey,
            pinScheme = request.pinScheme,
        )
        val started = starter(pinRequest) { result ->
            handleEmvOnlinePinResult(sourceCommand, result)
        }
        if (!started) {
            PinpadTraceLog.device("EMV_PIN $sourceCommand start failed scheme=${request.pinScheme}")
            respondToEmvPinRequest(sourceCommand, isConfirm = false, isBypass = false)
        }
    }

    private fun startOfflinePinEntryForEmv(sourceCommand: String, leftTimes: Int) {
        val enteredDigits = AtomicInteger(0)
        val listener = object : OnPinPadInputListener {
            override fun onInputResult(retCode: Int, data: ByteArray?) {
                offlinePinRunning.set(false)
                PinpadTraceLog.device(
                    "EMV_PIN $sourceCommand offline result=${NexgoSdkResultNames.format(retCode)} " +
                        "left=$leftTimes dataBytes=${data?.size ?: 0}",
                )
                val (isConfirm, isBypass) = when (retCode) {
                    SdkResult.Success -> true to false
                    SdkResult.PinPad_No_Pin_Input -> true to true
                    SdkResult.PinPad_Input_Cancel,
                    SdkResult.PinPad_Input_Timeout -> false to false
                    else -> false to false
                }
                when {
                    isConfirm && !isBypass -> PinpadDisplayController.showProcessing()
                    isConfirm && isBypass -> PinpadDisplayController.showProcessing()
                    else -> PinpadDisplayController.showOperationCancelledThenIdle()
                }
                respondToEmvPinRequest(sourceCommand, isConfirm = isConfirm, isBypass = isBypass)
            }

            override fun onSendKey(keyCode: Byte) {
                when (keyCode) {
                    PinPadKeyCode.KEYCODE_CLEAR,
                    PinPadKeyCode.KEYCODE_BACKSPACE -> enteredDigits.updateAndGet { (it - 1).coerceAtLeast(0) }
                    PinPadKeyCode.KEYCODE_CANCEL,
                    PinPadKeyCode.KEYCODE_CONFIRM -> Unit
                    else -> enteredDigits.updateAndGet { (it + 1).coerceAtMost(EMV_OFFLINE_MAX_PIN) }
                }
                PinpadDisplayController.updatePinDigits(enteredDigits.get())
                PinpadTraceLog.device(
                    "EMV_PIN $sourceCommand offline keyCode=$keyCode digits=${enteredDigits.get()}",
                )
            }
        }

        val startResult = runCatching {
            pinPad.setPinKeyboardMode(PinKeyboardModeEnum.FIXED)
            beepForPinPrompt(sourceCommand)
            PinpadDisplayController.showEnterPin()
            PinpadDisplayController.updatePinDigits(0)
            offlinePinRunning.set(true)
            pinPad.inputOfflinePin(EMV_PIN_LENGTHS, EMV_PIN_TIMEOUT_SECONDS, listener)
        }.onFailure {
            Log.w(TAG, "Unable to start offline PIN entry", it)
            PinpadTraceLog.device("EMV_PIN $sourceCommand offline start failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)

        PinpadTraceLog.device(
            "EMV_PIN $sourceCommand offline start timeout=$EMV_PIN_TIMEOUT_SECONDS " +
                "pinLengths=${EMV_PIN_LENGTHS.joinToString(",")} left=$leftTimes " +
                "result=${NexgoSdkResultNames.format(startResult)}",
        )
        if (startResult != SdkResult.Success) {
            offlinePinRunning.set(false)
            PinpadDisplayController.showBadReadThenIdle()
            respondToEmvPinRequest(sourceCommand, isConfirm = false, isBypass = false)
        }
    }

    private fun beepForPinPrompt(sourceCommand: String) {
        runCatching { deviceEngine.beeper.beep(PIN_PROMPT_BEEP_MS) }
            .onSuccess { PinpadTraceLog.device("EMV_PIN $sourceCommand prompt beep=${PIN_PROMPT_BEEP_MS}ms") }
            .onFailure { PinpadTraceLog.device("EMV_PIN $sourceCommand prompt beep failed=${it.message}") }
    }

    private fun beepForCardPrompt(sourceCommand: String) {
        runCatching { deviceEngine.beeper.beep(CARD_PROMPT_BEEP_MS) }
            .onSuccess { PinpadTraceLog.device("EMV $sourceCommand card prompt beep=${CARD_PROMPT_BEEP_MS}ms") }
            .onFailure { PinpadTraceLog.device("EMV $sourceCommand card prompt beep failed=${it.message}") }
    }

    private fun handleEmvOnlinePinResult(sourceCommand: String, result: EmvOnlinePinResult) {
        when (result) {
            is EmvOnlinePinResult.Success -> {
                onlinePinTlvHex = mergeTlvs(onlinePinTlvHex, emvOnlinePinTlv(result))
                PinpadTraceLog.device(
                    "EMV_PIN $sourceCommand result=OK scheme=${result.scheme} " +
                        "pinBlockBytes=${result.pinBlock.length / 2} ksnBytes=${result.ksn.length / 2} " +
                        "pinTlvChars=${onlinePinTlvHex.length}",
                )
                respondToEmvPinRequest(sourceCommand, isConfirm = true, isBypass = false)
            }
            EmvOnlinePinResult.NoPin -> {
                PinpadTraceLog.device("EMV_PIN $sourceCommand result=NO_PIN")
                respondToEmvPinRequest(sourceCommand, isConfirm = true, isBypass = true)
            }
            EmvOnlinePinResult.Canceled -> {
                PinpadTraceLog.device("EMV_PIN $sourceCommand result=CANCELED")
                respondToEmvPinRequest(sourceCommand, isConfirm = false, isBypass = false)
            }
            is EmvOnlinePinResult.Error -> {
                PinpadTraceLog.device("EMV_PIN $sourceCommand result=ERROR code=${result.code}")
                respondToEmvPinRequest(sourceCommand, isConfirm = false, isBypass = false)
            }
        }
    }

    private fun respondToEmvPinRequest(sourceCommand: String, isConfirm: Boolean, isBypass: Boolean) {
        runCatching { emvHandler.onSetPinInputResponse(isConfirm, isBypass) }
            .onFailure { PinpadTraceLog.device("EMV $sourceCommand PIN response failed=${it.message}") }
    }

    private fun emvOnlinePinTlv(result: EmvOnlinePinResult.Success): String {
        val tlvs = mutableListOf<String>()
        val pinBlockTag = store.terminalAssignedTag(TERMINAL_ONLINE_PIN_BLOCK_TAG) ?: EMV_PIN_BLOCK_TAG
        encodeSensitiveTlv(pinBlockTag, result.pinBlock)?.let { tlvs += it }
        val keyTag = store.terminalAssignedTag(TERMINAL_ONLINE_PIN_KEY_TAG)
        if (!keyTag.isNullOrBlank() && result.keyData.length >= MIN_PIN_KEY_HEX_CHARS) {
            encodeSensitiveTlv(keyTag, result.keyData)?.let { tlvs += it }
        }
        val ksnTag = store.terminalAssignedTag(TERMINAL_ONLINE_PIN_KSN_TAG) ?: EMV_DUKPT_KSN_TAG
        if (result.ksn.isNotBlank()) {
            encodeSensitiveTlv(ksnTag, result.ksn)?.let { tlvs += it }
        }
        return tlvs.joinToString(separator = "")
    }

    private fun encodeSensitiveTlv(tagHex: String, valueHex: String): String? {
        val tlv = PinpadEmvDataObjects.encodeTlv(tagHex, valueHex)
        if (tlv == null) {
            PinpadTraceLog.device("EMV_PIN unable to encode tag=$tagHex valueBytes=${valueHex.length / 2}")
        }
        return tlv
    }

    private fun emvPinAccount(confirmedPan: String?): String {
        confirmedPan?.filter(Char::isDigit)?.takeIf { it.isNotBlank() }?.let { return it }
        kernelTlvHexFromTag("5A")
            ?.trimEnd('F')
            ?.filter(Char::isDigit)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        kernelTlvHexFromTag("57")
            ?.substringBefore('D')
            ?.trimEnd('F')
            ?.filter(Char::isDigit)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return ""
    }

    private fun buildApplicationSelectTransConfiguration(
        transactionDisplay: PinpadTransactionDisplay?,
    ): EmvTransConfigurationEntity {
        val now = LocalDateTime.now()
        return EmvTransConfigurationEntity().apply {
            traceNo = DEFAULT_TRACE_NO
            transAmount = transactionDisplay?.emvAmount ?: DEFAULT_APP_SELECT_AMOUNT
            cashbackAmount = DEFAULT_CASHBACK_AMOUNT
            transDate = now.format(DateTimeFormatter.ofPattern("yyMMdd"))
            transTime = now.format(DateTimeFormatter.ofPattern("HHmmss"))
            emvTransType = transactionDisplay?.transactionTypeByte ?: DEFAULT_EMV_TRANS_TYPE
            countryCode = terminalCountryCode()
            currencyCode = transactionDisplay?.currencyCode ?: DEFAULT_CURRENCY_CODE
            emvEntryModeEnum = EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACT
            emvProcessFlowEnum = EmvProcessFlowEnum.EMV_PROCESS_FLOW_READ_APPDATA
            isContactForceOnline = false
            isContactlessSupportSelectApp = false
        }
    }

    private fun buildStandardTransConfiguration(
        request: ContactTransactionRequest,
        entryMode: EmvEntryModeEnum,
    ): EmvTransConfigurationEntity {
        val now = LocalDateTime.now()
        val sequence = nextTransactionSequenceHex()
        return EmvTransConfigurationEntity().apply {
            traceNo = sequence.takeLast(6)
            transAmount = request.amountAuthorized
            cashbackAmount = request.amountOther
            transDate = now.format(DateTimeFormatter.ofPattern("yyMMdd"))
            transTime = now.format(DateTimeFormatter.ofPattern("HHmmss"))
            emvTransType = request.transactionType
            countryCode = terminalCountryCode()
            currencyCode = request.currencyCode
            emvEntryModeEnum = entryMode
            emvProcessFlowEnum = EmvProcessFlowEnum.EMV_PROCESS_FLOW_STANDARD
            isContactForceOnline = request.forceOnline
            isContactlessSupportSelectApp = entryMode == EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACTLESS
        }
    }

    private fun nextTransactionSequenceHex(): String {
        val next = synchronized(sequencePrefs) {
            val current = sequencePrefs.getInt(KEY_TRANSACTION_SEQUENCE, 0)
            val value = if (current >= MAX_TRANSACTION_SEQUENCE) 1 else current + 1
            sequencePrefs.edit().putInt(KEY_TRANSACTION_SEQUENCE, value).apply()
            value
        }
        return "%08d".format(next).also { sequence ->
            lastTransactionSequenceHex = sequence
            setKernelTlv(TERMINAL_TRANSACTION_SEQUENCE_TAG, sequence)
            PinpadTraceLog.device("EMV transaction sequence 9F41=$sequence")
        }
    }

    private fun applyContactAidListForTransaction(): Boolean {
        return applyAidListForTransaction(EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACT)
    }

    private fun applyAidListForTransaction(entryMode: EmvEntryModeEnum): Boolean {
        val aids = when (entryMode) {
            EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACTLESS -> contactlessAidEntities()
            else -> contactAidEntities()
        }
        return runCatching {
            check(ensureEmvConfigurationLoaded("before $entryMode AID use")) { "EMV config reload failed" }
        }.onSuccess {
            PinpadTraceLog.device("EMV $entryMode AID list ready count=${aids.size}")
            verifyAidList(entryMode.name)
        }.onFailure {
            Log.w(TAG, "Unable to prepare AID list for transaction", it)
            PinpadTraceLog.device("EMV $entryMode AID prepare failed=${it.message}")
        }.isSuccess
    }

    private fun contactAidEntities(): List<AidEntity> {
        return store.aidTlvs().values.mapNotNull { aidEntityFromTlv(it, AidEntryModeEnum.AID_ENTRY_CONTACT) }
    }

    private fun contactlessAidEntities(): List<AidEntity> {
        return store.pcdAidTlvs().values.mapNotNull { contactlessAidEntityFromTlv(it) }
    }

    private fun aidEntityFromTlv(tlvHex: String, mode: AidEntryModeEnum): AidEntity? {
        val aid = tlvValueHex(tlvHex, AID_TAG) ?: return null
        return AidEntity().apply {
            this.aid = aid.lowercase(Locale.US)
            asi = contactAsi(tlvValueHex(tlvHex, AID_SELECTION_INDICATOR_TAG))
            appVerNum = tlvValueHex(tlvHex, AID_APPLICATION_VERSION_TAG).orEmpty()
            maxTargetPercent = tlvValueHex(tlvHex, AID_MAX_TARGET_PERCENT_TAG).hexByteToInt()
            targetPercent = tlvValueHex(tlvHex, AID_TARGET_PERCENT_TAG).hexByteToInt()
            threshold = tlvValueHex(tlvHex, AID_THRESHOLD_TAG).bcdNumericToLong()
            tacDefault = tlvValueHex(tlvHex, AID_TAC_DEFAULT_TAG).orEmpty()
            tacDenial = tlvValueHex(tlvHex, AID_TAC_DENIAL_TAG).orEmpty()
            tacOnline = tlvValueHex(tlvHex, AID_TAC_ONLINE_TAG).orEmpty()
            tlvValueHex(tlvHex, AID_DEFAULT_DDOL_TAG)?.takeIf { it.isNotBlank() }?.let(::setDdol)
            floorLimit = tlvValueHex(tlvHex, AID_FLOOR_LIMIT_TAG).hexToLong()
            onlinePinCap = DEFAULT_ONLINE_PIN_CAPABILITY
            aidEntryModeEnum = mode
        }.also {
            PinpadTraceLog.device(
                "EMV $mode AID mapped aid=${it.aid} asi=${it.asi} appVer=${it.appVerNum} " +
                    "tacDefault=${it.tacDefault} tacDenial=${it.tacDenial} tacOnline=${it.tacOnline} " +
                    "ddol=${it.getDdol()} floorLimit=${it.floorLimit} threshold=${it.threshold} " +
                    "targetPct=${it.targetPercent} maxTargetPct=${it.maxTargetPercent}",
            )
        }
    }

    private fun contactlessAidEntityFromTlv(tlvHex: String): AidEntity? {
        val aid = tlvValueHex(tlvHex, AID_TAG) ?: return null
        val floorLimitValue = tlvValueHex(tlvHex, PCD_READER_FLOOR_LIMIT_TAG).bcdNumericToLong()
        val transLimitValue = tlvValueHex(tlvHex, PCD_READER_TRANS_LIMIT_TAG).bcdNumericToLong()
        val cvmLimitValue = tlvValueHex(tlvHex, PCD_READER_CVM_LIMIT_TAG).bcdNumericToLong()
        return AidEntity().apply {
            this.aid = aid.lowercase(Locale.US)
            asi = NEXGO_PARTIAL_AID_MATCH
            appVerNum = tlvValueHex(tlvHex, AID_APPLICATION_VERSION_TAG).orEmpty()
            tacDefault = tlvValueHex(tlvHex, PCD_TAC_DEFAULT_TAG).orEmpty()
            tacDenial = tlvValueHex(tlvHex, PCD_TAC_DENIAL_TAG).orEmpty()
            tacOnline = tlvValueHex(tlvHex, PCD_TAC_ONLINE_TAG).orEmpty()
            floorLimit = tlvValueHex(tlvHex, AID_FLOOR_LIMIT_TAG).hexToLong().takeIf { it > 0 } ?: floorLimitValue
            contactlessFloorLimit = floorLimitValue
            contactlessTransLimit = transLimitValue
            contactlessCvmLimit = cvmLimitValue
            onlinePinCap = DEFAULT_ONLINE_PIN_CAPABILITY
            aidEntryModeEnum = AidEntryModeEnum.AID_ENTRY_CONTACTLESS
        }.also {
            PinpadTraceLog.device(
                "EMV AID_ENTRY_CONTACTLESS AID mapped aid=${it.aid} asi=${it.asi} " +
                    "appVer=${it.appVerNum} tacDefault=${it.tacDefault} tacDenial=${it.tacDenial} " +
                    "tacOnline=${it.tacOnline} floorLimit=${it.floorLimit} " +
                    "ctlsFloor=${it.contactlessFloorLimit} ctlsTrans=${it.contactlessTransLimit} " +
                    "ctlsCvm=${it.contactlessCvmLimit}",
            )
        }
    }

    private fun tlvValueHex(tlvHex: String, tag: String): String? {
        return PinpadEmvDataObjects.findEncodedTlvValue(tlvHex, tag)
            ?.let { PinpadEmvDataObjects.run { it.toHex() } }
    }

    private fun contactAsi(valueHex: String?): Int {
        return if (valueHex.hexByteToInt() == AID_PARTIAL_SELECTION_VALUE) {
            NEXGO_PARTIAL_AID_MATCH
        } else {
            NEXGO_EXACT_AID_MATCH
        }
    }

    private fun String?.hexByteToInt(): Int {
        return this?.takeLast(2)?.toIntOrNull(16) ?: 0
    }

    private fun String?.hexToLong(): Long {
        return this?.trimStart('0')?.takeIf { it.isNotBlank() }?.toLongOrNull(16) ?: 0L
    }

    private fun String?.bcdNumericToLong(): Long {
        return this?.trimStart('0')?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: 0L
    }

    private fun completeContactApplicationSelect(
        completed: AtomicBoolean,
        onResult: (String) -> Unit,
        payload: String,
        sourceCommand: String = "T11",
    ) {
        if (!completed.compareAndSet(false, true)) return
        contactSearchRunning.set(false)
        clearPendingAppSelect()
        if (payload == T12_MSR_FALLBACK) {
            showIccFallbackWarning(sourceCommand)
        } else if (!payload.startsWith('0')) {
            PinpadDisplayController.showIdle()
        }
        PinpadTraceLog.device("EMV $sourceCommand complete T12 payload=$payload")
        onResult(payload)
    }

    private fun t12PayloadFromApplicationSelectFinish(resultCode: Int, selectedAid: String?): String {
        return when (resultCode) {
            SdkResult.Emv_FallBack -> T12_MSR_FALLBACK
            else -> if (selectedAid.isNullOrBlank()) T12_FATAL_ERROR else "0$selectedAid"
        }
    }

    private fun selectedAidFromKernel(): String? {
        return kernelTlvHex(EMV_TAG_SELECTED_AID)
    }

    private fun configureContactlessParameters(aidHex: String, transactionType: Byte) {
        val selectedAid = aidHex.uppercase(Locale.US)
        PinpadTraceLog.device(
            "EMV contactless configure aid=$selectedAid transType=%02X".format(transactionType.toInt() and 0xFF),
        )
        when {
            selectedAid.contains("A000000004") -> configPaypassParameter(selectedAid, transactionType)
            selectedAid.contains("A000000003") -> configPaywaveParameters()
            selectedAid.contains("A000000025") -> configExpressPayParameter()
            selectedAid.contains("A000000152") -> configDpasParameter()
            selectedAid.contains("A000000065") -> configJcbContactlessParameter()
            selectedAid.contains("A000000333010108") -> configUnionPayParameter()
            else -> PinpadTraceLog.device("EMV contactless no scheme override for aid=$selectedAid")
        }
    }

    private fun configPaywaveParameters() {
        PinpadTraceLog.device("EMV contactless PayWave parameters")
        val terminalCapabilities = getKernelTlvBytes("9F33")
        if (terminalCapabilities != null && terminalCapabilities.size >= 2) {
            terminalCapabilities[1] = (terminalCapabilities[1].toInt() or 0x60).toByte()
            setKernelTlv("9F33", terminalCapabilities)
        }
        val ttq = getKernelTlvBytes("9F66")
        val requestedTtq = "36004000".hexBytesOrNull()
        if (ttq != null && requestedTtq != null && ttq.size >= 4 && requestedTtq.size >= 4) {
            ttq[0] = requestedTtq[0]
            ttq[1] = requestedTtq[1]
            ttq[2] = requestedTtq[2]
            ttq[3] = requestedTtq[3]
            setKernelTlv("9F66", ttq)
        }
    }

    private fun configPaypassParameter(aidHex: String, transactionType: Byte) {
        PinpadTraceLog.device("EMV contactless PayPass parameters aid=$aidHex")
        setKernelTlvIfMissing("DF811B", byteArrayOf(0xB0.toByte()))

        if (transactionType == REFUND_TRANS_TYPE) {
            setKernelTlvIfMissing("DF8120", "0000000000")
            setKernelTlvIfMissing("DF8121", "FFFFFFFFFF")
            setKernelTlvIfMissing("DF8122", "0000000000")
            return
        }

        when {
            aidHex.contains("A0000000043060") -> {
                setKernelTlvIfMissing("9F1D", "4C7A800000000000")
                setKernelTlvIfMissing("DF8118", byteArrayOf(0x40.toByte()))
                setKernelTlvIfMissing("DF8119", byteArrayOf(0x08.toByte()))
                setKernelTlvIfMissing("DF8120", "F45004800C")
                setKernelTlvIfMissing("DF8121", "0000800000")
                setKernelTlvIfMissing("DF8122", "F45004800C")
            }
            aidHex.contains("A0000000041010") -> {
                setKernelTlvIfMissing("9F1D", "6C7A800000000000")
                setKernelTlvIfMissing("DF8118", byteArrayOf(0x60.toByte()))
                setKernelTlvIfMissing("DF8119", byteArrayOf(0x08.toByte()))
                setKernelTlvIfMissing("DF8120", "F45084800C")
                setKernelTlvIfMissing("DF8121", "0000000000")
                setKernelTlvIfMissing("DF8122", "F45084800C")
            }
            aidHex.contains("A0000000042203") -> {
                val kernelDf811b = getKernelTlvBytes("DF811B")
                if (kernelDf811b != null && kernelDf811b.isNotEmpty()) {
                    kernelDf811b[0] = (kernelDf811b[0].toInt() and 0xD0).toByte()
                    setKernelTlv("DF811B", kernelDf811b)
                }
                setKernelTlvIfMissing("9F1D", "487A800000000000")
                setKernelTlvIfMissing("DF8118", byteArrayOf(0x40.toByte()))
                setKernelTlvIfMissing("DF8119", byteArrayOf(0x08.toByte()))
                setKernelTlvIfMissing("DF8120", "F45084800C")
                setKernelTlvIfMissing("DF8121", "0000000000")
                setKernelTlvIfMissing("DF8122", "F45084800C")
            }
        }
    }

    private fun configExpressPayParameter() {
        PinpadTraceLog.device("EMV contactless ExpressPay parameters")
        val ttc = getKernelTlvBytes("9F6E")
        val requestedTtc = "DCE00003".hexBytesOrNull()
        if (ttc != null && requestedTtc != null && ttc.size >= 4 && requestedTtc.size >= 4) {
            ttc[1] = requestedTtc[1]
            ttc[3] = (ttc[3].toInt() and 0x7F).toByte()
            setKernelTlv("9F6E", ttc)
        }
    }

    private fun configDpasParameter() {
        PinpadTraceLog.device("EMV contactless DPAS parameters")
        val terminalCapabilities = getKernelTlvBytes("9F33")
        if (terminalCapabilities != null && terminalCapabilities.size >= 2) {
            terminalCapabilities[1] = (terminalCapabilities[1].toInt() or 0x60).toByte()
            setKernelTlv("9F33", terminalCapabilities)
        }
        val ttq = getKernelTlvBytes("9F66")
        val requestedTtq = "36A04000".hexBytesOrNull()
        if (ttq != null && requestedTtq != null && ttq.size >= 4 && requestedTtq.size >= 4) {
            ttq[0] = requestedTtq[0]
            ttq[2] = requestedTtq[2]
            setKernelTlv("9F66", ttq)
        }
    }

    private fun configJcbContactlessParameter() {
        PinpadTraceLog.device("EMV contactless JCB parameters")
        val tip = runCatching { emvHandler.jcbContactlessTIP }.getOrNull()
        if (tip != null && tip.size >= 2) {
            tip[1] = (tip[1].toInt() and 0x7F).toByte()
            runCatching { emvHandler.setJcbContactlessTIP(tip) }
                .onFailure { PinpadTraceLog.device("EMV contactless JCB TIP failed=${it.message}") }
        }
    }

    private fun configUnionPayParameter() {
        PinpadTraceLog.device("EMV contactless UnionPay parameters")
        setKernelTlv("9F09", "0030")
    }

    private fun applyStoredContactlessKernelTlvs(aidHex: String) {
        val selectedAid = aidHex.uppercase(Locale.US)
        val tlvHex = store.pcdAidTlvs().values.firstOrNull { stored ->
            val storedAid = tlvValueHex(stored, AID_TAG)?.uppercase(Locale.US)
            storedAid != null && (storedAid == selectedAid || selectedAid.startsWith(storedAid))
        } ?: return
        var applied = 0
        for (record in PinpadEmvDataObjects.parseTlvRecords(tlvHex)) {
            if (record.tag in CONTACTLESS_KERNEL_TLV_EXCLUDES) continue
            if (CONTACTLESS_KERNEL_TLV_EXCLUDED_PREFIXES.any { record.tag.startsWith(it) }) continue
            setKernelTlv(record.tag, record.value)
            applied++
        }
        PinpadTraceLog.device("EMV contactless stored TLVs applied aid=$selectedAid count=$applied")
    }

    private fun terminalCountryCode(): String {
        val stored = PinpadEmvDataObjects.findEncodedTlvValue(store.terminalConfigTlv(), EMV_TAG_COUNTRY_CODE)
            ?.let { PinpadEmvDataObjects.run { it.toHex() } }
            ?.takeLast(4)
        return stored?.takeIf { it.isNotBlank() } ?: DEFAULT_COUNTRY_CODE
    }

    private fun kernelTlvHex(vararg tagBytes: Int): String? {
        return runCatching {
            emvHandler.getTlv(tagBytes.map { it.toByte() }.toByteArray(), EmvDataSourceEnum.FROM_KERNEL)
                ?.takeIf { it.isNotEmpty() }
                ?.let { PinpadEmvDataObjects.run { it.toHex() } }
        }.onFailure {
            PinpadTraceLog.device(
                "EMV T11 get kernel TLV tag=${tagBytes.joinToString(separator = "") { "%02X".format(it) }} " +
                    "failed=${it.message}",
            )
        }.getOrNull()
    }

    private fun completeContactTransaction(
        onResult: (EmvCommandResult) -> Unit,
        result: EmvCommandResult,
        completed: AtomicBoolean? = pendingTransactionCompleted,
    ) {
        if (completed != null && !completed.compareAndSet(false, true)) return
        transactionRunning.set(false)
        clearPendingTransaction()
        if (!onlineAuthorizationPending.get()) {
            if (result.success) {
                if (result.isDeclined()) {
                    showTransactionDeclined(result)
                } else {
                    val sensoryBrand = approvedSaleSensoryBrand(result)
                    if (sensoryBrand != null) {
                        PinpadTraceLog.device(
                            "EMV sensory brand=$sensoryBrand result=${result.transactionCode} " +
                                "transType=%02X".format(activeTransactionType.toInt() and 0xFF),
                        )
                        PinpadDisplayController.showBrandSensory(sensoryBrand)
                    } else {
                        PinpadDisplayController.showIdle()
                    }
                }
            } else {
                showTransactionWarning("EMV", "result reason=${result.reason} error=${result.errorMessage}")
            }
        }
        onResult(result)
    }

    private fun approvedSaleSensoryBrand(result: EmvCommandResult): SensoryBrand? {
        if (!BuildConfig.SENSORY_KITS_ENABLED) return null
        if (activeTransactionType != SALE_TRANS_TYPE) return null
        if (result.transactionCode !in APPROVED_SENSORY_RESULTS) return null
        val aid = selectedAidFromKernel()
            ?: tlvValueHex(transactionTlvHex, "4F")
            ?: tlvValueHex(transactionTlvHex, "84")
            ?: tlvValueHex(transactionTlvHex, "9F06")
        return sensoryBrandFromAid(aid)
    }

    private fun handleApplicationSelection(
        sourceCommand: String,
        appLabels: List<String>?,
        isMandatory: Boolean,
    ) {
        val labels = appLabels.orEmpty()
        if (labels.isEmpty()) {
            if (sourceCommand == "T11") pendingT11ApplicationSelection = null
            val responseIndex = if (isMandatory) 0 else -1
            PinpadTraceLog.device("EMV $sourceCommand app selection no labels response=$responseIndex")
            runCatching { emvHandler.onSetSelAppResponse(responseIndex) }
                .onFailure { PinpadTraceLog.device("EMV $sourceCommand onSetSelAppResponse failed=${it.message}") }
            return
        }
        val stickySelection = stickyApplicationSelectionFor(sourceCommand, labels)
        if (stickySelection != null) {
            PinpadTraceLog.device(
                "EMV $sourceCommand app selection sticky response=${stickySelection.responseIndex} " +
                    "label=${stickySelection.label}",
            )
            runCatching { emvHandler.onSetSelAppResponse(stickySelection.responseIndex) }
                .onFailure { PinpadTraceLog.device("EMV $sourceCommand onSetSelAppResponse failed=${it.message}") }
            return
        }
        if (labels.size == 1) {
            PinpadTraceLog.device("EMV $sourceCommand app selection auto response=1 label=${labels.first()}")
            rememberT11ApplicationSelection(sourceCommand, labels, selectedIndex = 0, responseIndex = 1)
            runCatching { emvHandler.onSetSelAppResponse(1) }
                .onFailure { PinpadTraceLog.device("EMV $sourceCommand onSetSelAppResponse failed=${it.message}") }
            return
        }

        val selectionCompleted = AtomicBoolean(false)
        PinpadTraceLog.device("EMV $sourceCommand app selection prompt labels=${labels.joinToString(separator = "|")}")
        PinpadDisplayController.showApplicationSelection(labels) { selectedIndex ->
            if (!selectionCompleted.compareAndSet(false, true)) return@showApplicationSelection
            val responseIndex = (selectedIndex + 1).coerceIn(1, labels.size)
            PinpadTraceLog.device(
                "EMV $sourceCommand app selection selectedIndex=$selectedIndex response=$responseIndex " +
                    "label=${labels[selectedIndex]}",
            )
            rememberT11ApplicationSelection(sourceCommand, labels, selectedIndex, responseIndex)
            runCatching { emvHandler.onSetSelAppResponse(responseIndex) }
                .onFailure { PinpadTraceLog.device("EMV $sourceCommand onSetSelAppResponse failed=${it.message}") }
        }
    }

    private fun stickyApplicationSelectionFor(
        sourceCommand: String,
        labels: List<String>,
    ): SelectedApplicationSelection? {
        if (sourceCommand == "T11") return null
        val pending = pendingT11ApplicationSelection ?: return null
        val pendingLabels = pending.labels.map { it.normalizedAppLabel() }
        val currentLabels = labels.map { it.normalizedAppLabel() }
        if (pendingLabels != currentLabels) {
            PinpadTraceLog.device(
                "EMV $sourceCommand app selection sticky ignored labels changed " +
                    "pending=${pending.labels.joinToString(separator = "|")} current=${labels.joinToString(separator = "|")}",
            )
            pendingT11ApplicationSelection = null
            return null
        }

        val preferredIndex = pending.responseIndex - 1
        val matchedIndex = when {
            labels.getOrNull(preferredIndex)?.normalizedAppLabel() == pending.label.normalizedAppLabel() -> preferredIndex
            else -> labels.indexOfFirst { it.normalizedAppLabel() == pending.label.normalizedAppLabel() }
        }
        if (matchedIndex < 0) {
            pendingT11ApplicationSelection = null
            return null
        }
        pendingT11ApplicationSelection = null
        return pending.copy(responseIndex = matchedIndex + 1, label = labels[matchedIndex])
    }

    private fun rememberT11ApplicationSelection(
        sourceCommand: String,
        labels: List<String>,
        selectedIndex: Int,
        responseIndex: Int,
    ) {
        if (sourceCommand != "T11") return
        pendingT11ApplicationSelection = SelectedApplicationSelection(
            labels = labels,
            selectedIndex = selectedIndex,
            responseIndex = responseIndex,
            label = labels.getOrElse(selectedIndex) { "" },
        )
        PinpadTraceLog.device(
            "EMV T11 app selection remembered response=$responseIndex " +
                "label=${labels.getOrElse(selectedIndex) { "" }}",
        )
    }

    private fun String.normalizedAppLabel(): String = trim().uppercase(Locale.US)

    private fun showTransactionDeclined(result: EmvCommandResult) {
        PinpadTraceLog.device(
            "EMV declined result=${result.transactionCode} " +
                "entry=$activeEntryMode transType=%02X".format(activeTransactionType.toInt() and 0xFF),
        )
        if (activeEntryMode == EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACTLESS) {
            PinpadDisplayController.showContactlessLeds(ContactlessLedState.Error)
        } else {
            PinpadDisplayController.clearContactlessLeds()
        }
        PinpadDisplayController.showDeclinedThenIdle()
        runCatching { deviceEngine.beeper.beep(DECLINED_BEEP_MS) }
            .onFailure { PinpadTraceLog.device("EMV declined beep failed=${it.message}") }
    }

    private fun sensoryBrandFromAid(aid: String?): SensoryBrand? {
        val normalized = aid?.uppercase(Locale.US).orEmpty()
        return when {
            normalized.contains(VISA_RID) -> SensoryBrand.Visa
            normalized.contains(MASTERCARD_RID) -> SensoryBrand.Mastercard
            else -> null
        }
    }

    private fun clearPendingAppSelect() {
        pendingAppSelectCompleted = null
        pendingAppSelectResult = null
    }

    private fun clearPendingTransaction() {
        pendingTransactionCompleted = null
        pendingTransactionResult = null
    }

    private fun showTransactionWarning(sourceCommand: String, reason: String) {
        PinpadTraceLog.device("EMV_WARNING $sourceCommand $reason")
        if (sourceCommand.isContactlessCommand() || PinpadDisplayController.contactlessLedState != ContactlessLedState.Off) {
            PinpadDisplayController.showContactlessLeds(ContactlessLedState.Error)
        }
        if (!PinpadDisplayController.state.isPinKeyConfigurationError()) {
            PinpadDisplayController.showBadReadThenIdle()
        }
        runCatching { deviceEngine.beeper.beep(ERROR_BEEP_MS) }
            .onFailure { PinpadTraceLog.device("EMV_WARNING beep failed=${it.message}") }
    }

    private fun showIccFallbackWarning(sourceCommand: String) {
        PinpadTraceLog.device("EMV_WARNING $sourceCommand ICC fallback use magnetic stripe")
        PinpadDisplayController.showIccFallbackThenIdle()
        runCatching { deviceEngine.beeper.beep(ICC_FALLBACK_BEEP_MS) }
            .onFailure { PinpadTraceLog.device("EMV_WARNING fallback beep failed=${it.message}") }
    }

    private fun showContactlessFeedback(state: ContactlessLedState, beepMs: Int = 0) {
        PinpadDisplayController.showContactlessLeds(state)
        if (beepMs <= 0) return
        runCatching { deviceEngine.beeper.beep(beepMs) }
            .onFailure { PinpadTraceLog.device("EMV contactless feedback beep failed=${it.message}") }
    }

    private fun String.isContactlessCommand(): Boolean {
        return startsWith("T6", ignoreCase = true) ||
            startsWith("T7", ignoreCase = true) ||
            equals("T81", ignoreCase = true)
    }

    private fun PinpadDisplayState.isPinKeyConfigurationError(): Boolean {
        return this is PinpadDisplayState.PinKeySchemeError || this is PinpadDisplayState.KeyMetadataMissing
    }

    private fun EmvCommandResult.isDeclined(): Boolean {
        return transactionCode in DECLINED_RESULTS
    }

    private fun resultFromSdkResult(resultCode: Int, cidHex: String? = null): EmvCommandResult {
        val onlineApproved = lastOnlineApproved
        val declinedByKernel = resultCode == SdkResult.Emv_Offline_Declined ||
            resultCode == SdkResult.Emv_Declined ||
            cidHex.isAacCryptogram()
        val approvedByKernel = resultCode == SdkResult.Success ||
            resultCode == SdkResult.Emv_Offline_Accept ||
            cidHex.isTcCryptogram()
        val result = when {
            declinedByKernel && onlineApproved != null -> RESULT_ONLINE_DECLINED
            declinedByKernel -> RESULT_OFFLINE_DECLINED
            onlineApproved == true && approvedByKernel -> RESULT_ONLINE_APPROVED
            onlineApproved == false -> RESULT_ONLINE_DECLINED
            approvedByKernel -> RESULT_OFFLINE_APPROVED
            resultCode == SdkResult.Emv_Online -> RESULT_ONLINE_REQUEST
            resultCode == SdkResult.Emv_App_Block || resultCode == SdkResult.Emv_Card_Block -> RESULT_APP_RESELECT
            resultCode == SdkResult.Emv_FallBack -> RESULT_ONLINE_REQUEST
            else -> return EmvCommandResult.failure('1', "%08X".format(resultCode))
        }
        PinpadTraceLog.device(
            "EMV result map sdk=${NexgoSdkResultNames.format(resultCode)} " +
                "hostApproved=$onlineApproved cid=${cidHex.orEmpty()} result=$result",
        )
        val financialNeed = if (result.startsWith('Y') || result == RESULT_ONLINE_REQUEST) '1' else '0'
        return EmvCommandResult.ok(transactionCode = result, financialNeed = financialNeed)
    }

    private fun String?.isAacCryptogram(): Boolean {
        val cid = this?.take(2)?.toIntOrNull(16) ?: return false
        return cid and CRYPTOGRAM_TYPE_MASK == CRYPTOGRAM_AAC
    }

    private fun String?.isTcCryptogram(): Boolean {
        val cid = this?.take(2)?.toIntOrNull(16) ?: return false
        return cid and CRYPTOGRAM_TYPE_MASK == CRYPTOGRAM_TC
    }

    private fun collectKnownKernelTlvs(tags: List<String>): String {
        return tags.joinToString(separator = "") { tag ->
            val value = kernelTlvHexFromTag(tag).orEmpty()
            if (value.isBlank()) "" else PinpadEmvDataObjects.encodeTlv(tag, value).orEmpty()
        }
    }

    private fun cacheCurrentKernelTransactionData(source: String) {
        val data = mergeTlvs(
            runtimeTlvHex,
            onlinePinTlvHex,
            transactionTlvHex,
            onlineAuthorizationTlvHex,
            reversalTlvHex,
            collectKnownKernelTlvs(NON_EMV_CARD_DATA_TAGS),
        )
        if (data.isBlank()) {
            PinpadTraceLog.device("EMV $source cardData empty")
            return
        }
        transactionTlvHex = data
        onlineAuthorizationTlvHex = mergeTlvs(onlineAuthorizationTlvHex, data)
        reversalTlvHex = mergeTlvs(reversalTlvHex, data)
        PinpadTraceLog.device(
            "EMV $source cardData cached transactionChars=${transactionTlvHex.length} " +
                "onlineChars=${onlineAuthorizationTlvHex.length}",
        )
    }

    private fun kernelTlvHexFromTag(tag: String): String? {
        if (tag.equals(AID_TAG, ignoreCase = true)) {
            return selectedAidFromKernel()
        }
        if (tag.equals(TERMINAL_TRANSACTION_SEQUENCE_TAG, ignoreCase = true)) {
            return getKernelTlvBytes(TERMINAL_TRANSACTION_SEQUENCE_TAG)?.toHexString()
                ?: lastTransactionSequenceHex.takeIf { it.isNotBlank() }
        }
        if (tag.equals(CARDHOLDER_NAME_EXTENDED_TAG, ignoreCase = true)) {
            val extendedName = getKernelTlvBytes(CARDHOLDER_NAME_EXTENDED_TAG)?.toHexString()
            return extendedName ?: getKernelTlvBytes(CARDHOLDER_NAME_TAG)?.toHexString()
        }
        val tagBytes = PinpadEmvDataObjects.run { tag.hexToBytesOrNull() } ?: return null
        return runCatching {
            emvHandler.getTlv(tagBytes, EmvDataSourceEnum.FROM_KERNEL)
                ?.takeIf { it.isNotEmpty() }
                ?.let { PinpadEmvDataObjects.run { it.toHex() } }
        }.getOrNull()
    }

    private fun getKernelTlvBytes(tagHex: String): ByteArray? {
        val tagBytes = tagHex.hexBytesOrNull() ?: return null
        return runCatching {
            emvHandler.getTlv(tagBytes, EmvDataSourceEnum.FROM_KERNEL)
                ?.takeIf { it.isNotEmpty() }
        }.onFailure {
            PinpadTraceLog.device("EMV getTlv tag=$tagHex failed=${it.message}")
        }.getOrNull()
    }

    private fun setKernelTlv(tagHex: String, valueHex: String) {
        val value = valueHex.hexBytesOrNull() ?: return
        setKernelTlv(tagHex, value)
    }

    private fun setKernelTlvIfMissing(tagHex: String, valueHex: String) {
        val value = valueHex.hexBytesOrNull() ?: return
        setKernelTlvIfMissing(tagHex, value)
    }

    private fun setKernelTlvIfMissing(tagHex: String, value: ByteArray) {
        val current = getKernelTlvBytes(tagHex)
        if (current != null && current.isNotEmpty()) {
            PinpadTraceLog.device("EMV keepTlv tag=$tagHex value=${current.toHexString()}")
            return
        }
        setKernelTlv(tagHex, value)
    }

    private fun setKernelTlv(tagHex: String, value: ByteArray) {
        val tagBytes = tagHex.hexBytesOrNull() ?: return
        runCatching { emvHandler.setTlv(tagBytes, value) }
            .onSuccess { PinpadTraceLog.device("EMV setTlv tag=$tagHex value=${value.toHexString()}") }
            .onFailure { PinpadTraceLog.device("EMV setTlv tag=$tagHex failed=${it.message}") }
    }

    private fun String.hexBytesOrNull(): ByteArray? {
        return PinpadEmvDataObjects.run { uppercase(Locale.US).hexToBytesOrNull() }
    }

    private fun ByteArray.toHexString(): String {
        return PinpadEmvDataObjects.run { toHex() }
    }

    private fun String.previewHex(edgeChars: Int = 16): String {
        return when {
            isBlank() -> "<empty>"
            length <= edgeChars * 2 -> uppercase(Locale.US)
            else -> "${take(edgeChars).uppercase(Locale.US)}...${takeLast(edgeChars).uppercase(Locale.US)}"
        }
    }

    private fun mergedTransactionTlv(): String = mergeTlvs(runtimeTlvHex, transactionTlvHex)

    private fun mergeTlvs(vararg tlvs: String): String {
        val records = linkedMapOf<String, PinpadEmvDataObjects.TlvRecord>()
        for (tlv in tlvs) {
            for (record in PinpadEmvDataObjects.parseTlvRecords(tlv)) {
                records[record.tag] = record
            }
        }
        return records.values.joinToString(separator = "") {
            PinpadEmvDataObjects.encodeTlv(it.tag, PinpadEmvDataObjects.run { it.value.toHex() }).orEmpty()
        }
    }

    private fun logEmvConfig(label: String, config: EmvTransConfigurationEntity) {
        PinpadTraceLog.device(
            "EMV_STEP $label trace=${config.traceNo} amount=${config.transAmount} " +
                "cashback=${config.cashbackAmount} date=${config.transDate} time=${config.transTime} " +
                "type=${config.emvTransType} country=${config.countryCode} currency=${config.currencyCode} " +
                "entry=${config.emvEntryModeEnum} flow=${config.emvProcessFlowEnum} " +
                "forceOnline=${config.isContactForceOnline} clSelect=${config.isContactlessSupportSelectApp}",
        )
    }

    private fun verifyAidList(label: String) {
        val loaded = runCatching { emvHandler.aidList }.getOrNull()
        if (loaded == null) {
            PinpadTraceLog.device("EMV $label AID verification unavailable")
            return
        }
        PinpadTraceLog.device("EMV $label AID verified count=${loaded.size}")
        for (aid in loaded) {
            PinpadTraceLog.device(
                "EMV $label AID aid=${aid.aid} mode=${aid.aidEntryModeEnum} asi=${aid.asi} " +
                    "tacDefault=${aid.tacDefault} tacOnline=${aid.tacOnline} tacDenial=${aid.tacDenial}",
            )
        }
    }

    private fun loadCapkHeader(payload: String, replaceExisting: Boolean): EmvCapkResult {
        if (payload.length < 60) return EmvCapkResult('1', EmvCommandResult.failure('2'))
        val header = PendingCapkHeader(
            rid = payload.substring(1, 11).uppercase(Locale.US),
            pki = payload.substring(11, 13).uppercase(Locale.US),
            hashAlgorithm = payload.substring(13, 15).uppercase(Locale.US),
            hash = payload.substring(15, 55).uppercase(Locale.US),
            publicKeyAlgorithm = payload.substring(55, 57).uppercase(Locale.US),
            modulusLengthHex = payload.substring(57, 59).uppercase(Locale.US),
            exponentCode = payload[59],
            replaceExisting = replaceExisting,
        )
        pendingCapkHeaders[header.id] = header
        PinpadTraceLog.device(
            "EMV CAPK header id=${header.id} replaceExisting=$replaceExisting " +
                "exists=${store.capkTlvs().containsKey(header.id)}",
        )
        return EmvCapkResult('1', EmvCommandResult.ok())
    }

    private fun loadCapkModulus(payload: String): EmvCapkResult {
        val header = pendingCapkHeaders.values.lastOrNull() ?: return EmvCapkResult('2', EmvCommandResult.failure('2'))
        val modulus = payload.drop(1).uppercase(Locale.US)
        if (!modulus.all { it.digitToIntOrNull(16) != null }) return EmvCapkResult('2', EmvCommandResult.failure('2'))
        val exponent = when (header.exponentCode) {
            '1' -> "03"
            '2' -> "010001"
            else -> return EmvCapkResult('2', EmvCommandResult.failure('2'))
        }
        val checksum = capkChecksum(header.rid, header.pki, modulus, exponent)
            ?: return EmvCapkResult('2', EmvCommandResult.failure('3'))
        if (!checksum.equals(header.hash, ignoreCase = true)) {
            PinpadTraceLog.device(
                "EMV CAPK checksum corrected id=${header.id} supplied=${header.hash} computed=$checksum",
            )
        }
        val tlv = buildString {
            append(PinpadEmvDataObjects.encodeTlv("9F06", header.rid) ?: return EmvCapkResult('2', EmvCommandResult.failure('3')))
            append(PinpadEmvDataObjects.encodeTlv("9F22", header.pki) ?: return EmvCapkResult('2', EmvCommandResult.failure('3')))
            append(PinpadEmvDataObjects.encodeTlv("DF05", DEFAULT_CAPK_EXPIRY_ASCII_HEX) ?: return EmvCapkResult('2', EmvCommandResult.failure('3')))
            append(PinpadEmvDataObjects.encodeTlv("DF06", header.hashAlgorithm) ?: return EmvCapkResult('2', EmvCommandResult.failure('3')))
            append(PinpadEmvDataObjects.encodeTlv("DF07", header.publicKeyAlgorithm) ?: return EmvCapkResult('2', EmvCommandResult.failure('3')))
            append(PinpadEmvDataObjects.encodeTlv("DF02", modulus) ?: return EmvCapkResult('2', EmvCommandResult.failure('3')))
            append(PinpadEmvDataObjects.encodeTlv("DF04", exponent) ?: return EmvCapkResult('2', EmvCommandResult.failure('3')))
            append(PinpadEmvDataObjects.encodeTlv("DF03", checksum) ?: return EmvCapkResult('2', EmvCommandResult.failure('3')))
        }
        if (!header.replaceExisting && store.capkTlvs().containsKey(header.id)) {
            pendingCapkHeaders.remove(header.id)
            PinpadTraceLog.device("EMV CAPK skipped existing id=${header.id} source=PCD bytes=${modulus.length / 2}")
            return EmvCapkResult('2', EmvCommandResult.ok())
        }
        store.setCapkTlv(header.id, tlv)
        pendingCapkHeaders.remove(header.id)
        markEmvConfigReloadRequired("CAPK loaded id=${header.id}")
        PinpadTraceLog.device("EMV CAPK modulus id=${header.id} bytes=${modulus.length / 2}")
        return EmvCapkResult('2', EmvCommandResult.ok())
    }

    private fun capkChecksum(rid: String, pki: String, modulus: String, exponent: String): String? {
        val data = PinpadEmvDataObjects.run {
            (rid + pki + modulus + exponent).hexToBytesOrNull()
        } ?: return null
        return MessageDigest.getInstance("SHA-1")
            .digest(data)
            .joinToString(separator = "") { "%02X".format(it) }
    }

    private fun applyTerminalConfig(tlvHex: String): Boolean {
        val kernelTlvHex = kernelTerminalConfigTlv(tlvHex) ?: return false
        val bytes = PinpadEmvDataObjects.run { kernelTlvHex.hexToBytesOrNull() } ?: return false
        return runCatching { emvHandler.initTermConfig(bytes) }
            .onSuccess {
                PinpadTraceLog.device(
                    "EMV initTermConfig result=$it storedTlvChars=${tlvHex.length} " +
                        "kernelTlvChars=${kernelTlvHex.length}",
                )
            }
            .onFailure {
                Log.w(TAG, "Unable to apply terminal EMV config", it)
                PinpadTraceLog.device("EMV initTermConfig failed=${it.message}")
            }
            .getOrNull()
            .isSdkSuccess("initTermConfig")
    }

    private fun kernelTerminalConfigTlv(tlvHex: String): String? {
        val filtered = PinpadEmvDataObjects.filterTlvRecords(tlvHex) { tag ->
            KERNEL_EXCLUDED_TERMINAL_TAG_PREFIXES.any { tag.startsWith(it) }
        } ?: return null
        val removed = PinpadEmvDataObjects.parseTlvRecords(tlvHex).size -
            PinpadEmvDataObjects.parseTlvRecords(filtered).size
        if (removed > 0) {
            PinpadTraceLog.device("EMV terminal config filtered A10 meta tags removed=$removed")
        }
        return filtered
    }

    private fun applyAidList(): Boolean {
        val aids = contactAidEntities() + contactlessAidEntities()
        return runCatching {
            emvHandler.delAllAid()
            if (aids.isNotEmpty()) emvHandler.setAidParaList(aids) else SdkResult.Success
        }.onSuccess {
            PinpadTraceLog.device("EMV setContactAidParaList count=${aids.size} result=$it")
            verifyAidList("stored")
        }.onFailure {
            Log.w(TAG, "Unable to apply AID list", it)
            PinpadTraceLog.device("EMV setContactAidParaList failed=${it.message}")
        }
            .getOrNull()
            .isSdkSuccess("setContactAidParaList")
    }

    private fun applyCapkList(): Boolean {
        val storedCapks = store.capkTlvs().toSortedMap()
        val capks = storedCapks.values.toList()
        traceStoredCapks(storedCapks)
        return runCatching {
            emvHandler.delAllCapk()
            if (BuildConfig.EMV_CAPK_TRACE_ENABLED) {
                PinpadTraceLog.device("EMV CAPK delAllCapk done")
            }
            if (capks.isNotEmpty()) emvHandler.setCAPKList(capks) else SdkResult.Success
        }.onSuccess {
            PinpadTraceLog.device("EMV setCAPKList count=${capks.size} result=$it")
            traceLoadedCapks()
        }.onFailure {
            Log.w(TAG, "Unable to apply CAPK list", it)
            PinpadTraceLog.device("EMV setCAPKList failed=${it.message}")
        }
            .getOrNull()
            .isSdkSuccess("setCAPKList")
    }

    private fun Int?.isSdkSuccess(operation: String): Boolean {
        val result = this ?: return false
        if (result != SdkResult.Success) {
            PinpadTraceLog.device("EMV $operation rejected result=$result")
            return false
        }
        return true
    }

    private fun traceStoredCapks(capks: Map<String, String>) {
        if (!BuildConfig.EMV_CAPK_TRACE_ENABLED) return
        PinpadTraceLog.device(
            "EMV CAPK stored count=${capks.size} ids=${capks.keys.joinToString(separator = ",")}",
        )
        capks.forEach { (id, tlv) ->
            PinpadTraceLog.device("EMV CAPK stored ${formatCapkTlvSummary(id, tlv)}")
        }
        if (!capks.containsKey("A000000004F1")) {
            PinpadTraceLog.device("EMV CAPK warning missing Mastercard test CAPK id=A000000004F1")
        }
    }

    private fun traceLoadedCapks() {
        if (!BuildConfig.EMV_CAPK_TRACE_ENABLED) return
        runCatching { emvHandler.capkList.orEmpty() }
            .onSuccess { loaded ->
                PinpadTraceLog.device("EMV CAPK loaded count=${loaded.size}")
                loaded.sortedWith(compareBy({ it.rid.orEmpty() }, { it.capkIdx })).forEach { capk ->
                    PinpadTraceLog.device(
                        "EMV CAPK loaded rid=${capk.rid.orEmpty().uppercase(Locale.US)} " +
                            "pki=%02X hashAlg=%02X pkAlg=%02X expiry=${capk.expireDate.orEmpty()} " +
                            "modLen=${capk.modulus.orEmpty().length / 2} " +
                            "exp=${capk.exponent.orEmpty().uppercase(Locale.US)} " +
                            "hash=${capk.checkSum.orEmpty().uppercase(Locale.US)}".format(
                                capk.capkIdx and 0xFF,
                                capk.hashInd and 0xFF,
                                capk.arithInd and 0xFF,
                            ),
                    )
                }
            }
            .onFailure {
                PinpadTraceLog.device("EMV CAPK loaded query failed=${it.message}")
            }
    }

    private fun formatCapkTlvSummary(id: String, tlv: String): String {
        val values = PinpadEmvDataObjects.parseTlvRecords(tlv).associate { record ->
            record.tag to PinpadEmvDataObjects.run { record.value.toHex() }
        }
        val rid = values["9F06"].orEmpty()
        val pki = values["9F22"].orEmpty()
        val hashAlgorithm = values["DF06"].orEmpty()
        val publicKeyAlgorithm = values["DF07"].orEmpty()
        val expiry = values["DF05"].orEmpty()
        val modulus = values["DF02"].orEmpty()
        val exponent = values["DF04"].orEmpty()
        val hash = values["DF03"].orEmpty()
        val missing = listOf("9F06", "9F22", "DF06", "DF07", "DF02", "DF04", "DF03")
            .filterNot(values::containsKey)
        return "id=$id rid=$rid pki=$pki hashAlg=$hashAlgorithm pkAlg=$publicKeyAlgorithm " +
            "expiry=$expiry modLen=${modulus.length / 2} mod=${modulus.previewHex()} " +
            "expLen=${exponent.length / 2} exp=$exponent hash=$hash " +
            "tags=${values.keys.sorted().joinToString(separator = ",")} " +
            if (missing.isEmpty()) {
                "status=OK"
            } else {
                "missing=${missing.joinToString(separator = ",")}"
            }
    }

    private fun formatDataFormatPreview(
        definitions: Map<String, PinpadEmvDataObjects.EmvDataFormatDefinition>,
    ): String {
        val priority = listOf("DF01", "DF02", "DF03", "DF71", "DF72")
        val selectedKeys = (priority.filter { it in definitions } + definitions.keys.sorted())
            .distinct()
            .take(DATA_FORMAT_TRACE_LIMIT)
        val preview = selectedKeys.joinToString(separator = ", ") { key ->
            val item = definitions.getValue(key)
            "${item.tag}=${item.format} min=${item.minLength} max=${item.maxLength} " +
                "flag=${item.lengthFlag} var=${item.isVariableLength}"
        }
        val remaining = definitions.size - selectedKeys.size
        return if (remaining > 0) "$preview, +$remaining more" else preview
    }

    private fun formatTerminalAssignmentPreview(): String {
        return TERMINAL_ASSIGNMENT_TAGS.joinToString(separator = ", ") { configTag ->
            val assignedTag = store.terminalAssignedTag(configTag)
            val format = assignedTag?.let { store.dataFormatDefinition(it) }
            when {
                assignedTag == null -> "$configTag=<missing>"
                format == null -> "$configTag=$assignedTag format=<missing>"
                else -> "$configTag=$assignedTag ${format.format} min=${format.minLength} " +
                    "max=${format.maxLength} flag=${format.lengthFlag} var=${format.isVariableLength}"
            }
        }
    }

    private fun applyRuntimeTlv(tlvHex: String) {
        val method = emvHandler.javaClass.methods.firstOrNull {
            it.name == "setTlv" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == ByteArray::class.java && it.parameterTypes[1] == ByteArray::class.java
        } ?: return
        for (record in PinpadEmvDataObjects.parseTlvRecords(tlvHex)) {
            val tag = PinpadEmvDataObjects.run { record.tag.hexToBytesOrNull() } ?: continue
            runCatching { method.invoke(emvHandler, tag, record.value) }
                .onFailure { PinpadTraceLog.device("EMV setTlv tag=${record.tag} failed=${it.message}") }
        }
    }

    private fun nextAidPacketNo(): Int? {
        if (pendingAid == null || pendingAidTotalPackets == null || aidPacketTlvs.isEmpty()) return null
        return (aidPacketTlvs.keys.maxOrNull() ?: return null) + 1
    }

    private fun nextPcdAidPacketNo(): Int? {
        if (pendingPcdAid == null || pendingPcdAidTotalPackets == null || pcdAidPacketTlvs.isEmpty()) return null
        return (pcdAidPacketTlvs.keys.maxOrNull() ?: return null) + 1
    }

    data class EmvCommandResult(
        val success: Boolean,
        val reason: Char? = null,
        val errorMessage: String = "",
        val transactionCode: String = RESULT_ONLINE_REQUEST,
        val adviceNeed: Char = '0',
        val reversalNeed: Char = '0',
        val financialNeed: Char = '1',
    ) {
        companion object {
            fun ok(
                transactionCode: String = RESULT_ONLINE_REQUEST,
                adviceNeed: Char = '0',
                reversalNeed: Char = '0',
                financialNeed: Char = '1',
            ): EmvCommandResult = EmvCommandResult(
                success = true,
                transactionCode = transactionCode,
                adviceNeed = adviceNeed,
                reversalNeed = reversalNeed,
                financialNeed = financialNeed,
            )

            fun failure(reason: Char, errorMessage: String = ""): EmvCommandResult {
                return EmvCommandResult(success = false, reason = reason, errorMessage = errorMessage)
            }
        }
    }

    enum class EmvPinScheme {
        MASTER_SESSION,
        DUKPT;

        companion object {
            fun fromCode(code: Char?): EmvPinScheme {
                return if (code == '1') DUKPT else MASTER_SESSION
            }
        }
    }

    data class EmvOnlinePinRequest(
        val sourceCommand: String,
        val account: String,
        val sessionKey: String,
        val pinScheme: EmvPinScheme,
    )

    sealed class EmvOnlinePinResult {
        data class Success(
            val scheme: EmvPinScheme,
            val pinBlock: String,
            val pinLength: Int,
            val keyData: String = "",
            val ksn: String = "",
        ) : EmvOnlinePinResult()

        data class Error(val code: Char) : EmvOnlinePinResult()
        data object NoPin : EmvOnlinePinResult()
        data object Canceled : EmvOnlinePinResult()
    }

    data class EmvCapkResult(
        val sequence: Char,
        val commandResult: EmvCommandResult,
    )

    data class EmvConfigQuery(
        val configType: Char,
        val status: Char,
        val ids: List<String>,
    )

    data class EmvConfigDelete(
        val configType: Char,
        val status: Char,
        val deleted: List<Boolean>,
    )

    private data class PendingCapkHeader(
        val rid: String,
        val pki: String,
        val hashAlgorithm: String,
        val hash: String,
        val publicKeyAlgorithm: String,
        val modulusLengthHex: String,
        val exponentCode: Char,
        val replaceExisting: Boolean,
    ) {
        val id: String = rid + pki
    }

    private data class ContactTransactionRequest(
        val amountAuthorized: String,
        val amountOther: String,
        val currencyCode: String,
        val transactionType: Byte,
        val forceOnline: Boolean,
        val timeoutSeconds: Int,
        val encryptedSessionKey: String,
        val pinScheme: EmvPinScheme,
    ) {
        companion object {
            fun parse(
                payload: String,
                transactionDisplay: PinpadTransactionDisplay? = null,
            ): ContactTransactionRequest {
                val parts = payload.trimStart(SUB).split(SUB)
                val currency = transactionDisplay?.currencyCode
                    ?: parts.getOrNull(2).orEmpty().takeLast(3).padStart(4, '0')
                val sessionKey = parts.getOrNull(7)
                    ?.replace(" ", "")
                    ?.uppercase(Locale.US)
                    ?.takeIf { it.length in SESSION_KEY_HEX_LENGTHS && it.all { ch -> ch.digitToIntOrNull(16) != null } }
                    .orEmpty()
                return ContactTransactionRequest(
                    amountAuthorized = transactionDisplay?.emvAmount
                        ?: parts.getOrNull(0)?.takeIf { it.length == 12 && it.all(Char::isDigit) }
                        ?: DEFAULT_APP_SELECT_AMOUNT,
                    amountOther = parts.getOrNull(1)?.takeIf { it.length == 12 && it.all(Char::isDigit) }
                        ?: DEFAULT_CASHBACK_AMOUNT,
                    currencyCode = currency.takeIf { it != "0000" } ?: DEFAULT_CURRENCY_CODE,
                    transactionType = transactionDisplay?.transactionTypeByte
                        ?: parts.getOrNull(3)?.takeLast(2)?.toIntOrNull(16)?.toByte()
                        ?: DEFAULT_EMV_TRANS_TYPE,
                    forceOnline = parts.getOrNull(6)?.firstOrNull() == '1',
                    timeoutSeconds = parts.getOrNull(6)?.takeIf { it.length == 3 && it.all(Char::isDigit) }?.toInt()
                        ?: CONTACT_SEARCH_TIMEOUT_SECONDS,
                    encryptedSessionKey = sessionKey,
                    pinScheme = EmvPinScheme.fromCode(parts.drop(8).lastOrNull()?.singleOrNull()),
                )
            }
        }
    }

    private data class HostOnlineResponse(
        val hostReachable: Boolean,
        val approved: Boolean,
        val arc: String,
        val authCode: String,
        val issuerAuthenticationData: String,
        val field55: String,
    ) {
        fun field55WithScripts(scripts: List<String>): ByteArray? {
            val iadTlv = if (issuerAuthenticationData.isNotBlank() && field55.isBlank()) {
                PinpadEmvDataObjects.encodeTlv("91", issuerAuthenticationData).orEmpty()
            } else {
                ""
            }
            val combined = (field55 + iadTlv + scripts.joinToString(separator = ""))
                .uppercase(Locale.US)
                .replace(" ", "")
            return PinpadEmvDataObjects.run { combined.takeIf { it.isNotBlank() }?.hexToBytesOrNull() }
        }

        companion object {
            fun parse(payload: String): HostOnlineResponse {
                val clean = payload.trimStart(SUB)
                val onlineRes = clean.firstOrNull() ?: '0'
                val fields = clean.drop(1).trimStart(SUB).split(SUB)
                val arc = fields.getOrNull(0)?.take(2)?.uppercase(Locale.US)?.takeIf { it.length == 2 } ?: when (onlineRes) {
                    '1', '3', '4' -> "00"
                    else -> "05"
                }
                val data = fields.getOrNull(1).orEmpty().uppercase(Locale.US).replace(" ", "")
                val field55 = data.takeIf { it.startsWith("91") || it.startsWith("71") || it.startsWith("72") }.orEmpty()
                return HostOnlineResponse(
                    hostReachable = onlineRes != '0',
                    approved = onlineRes in setOf('1', '3', '4') && arc == "00",
                    arc = arc,
                    authCode = DEFAULT_AUTH_CODE,
                    issuerAuthenticationData = if (field55.isBlank() && data.all { it.digitToIntOrNull(16) != null }) data else "",
                    field55 = field55,
                )
            }
        }
    }

    private data class SelectedApplicationSelection(
        val labels: List<String>,
        val selectedIndex: Int,
        val responseIndex: Int,
        val label: String,
    )

    private companion object {
        private const val TAG = "PinpadContactEmv"
        private const val EMV_APP_ID = "pinpad"
        private const val CONTACT_SEARCH_TIMEOUT_SECONDS = 60
        private const val EMV_PIN_TIMEOUT_SECONDS = 60
        private const val EMV_OFFLINE_MAX_PIN = 12
        private const val PIN_PROMPT_BEEP_MS = 120
        private const val CARD_PROMPT_BEEP_MS = 90
        private const val DATA_FORMAT_TRACE_LIMIT = 8
        private const val ERROR_BEEP_MS = 200
        private const val DECLINED_BEEP_MS = 600
        private const val ICC_FALLBACK_BEEP_MS = 600
        private const val CONTACTLESS_READY_BEEP_MS = 60
        private const val CONTACTLESS_DETECTED_BEEP_MS = 80
        private const val CONTACTLESS_PROCESSING_BEEP_MS = 120
        private const val CONTACTLESS_ERROR_BEEP_MS = 200
        private const val T31_FORCE_COMPLETE_WAIT_MS = 2_500L
        private val TERMINAL_ASSIGNMENT_TAGS = listOf("50000004", "50000005", "50000022")
        private val EMV_PIN_LENGTHS = intArrayOf(4, 5, 6, 7, 8, 9, 10, 11, 12)
        private const val TERMINAL_ONLINE_PIN_BLOCK_TAG = "50000004"
        private const val TERMINAL_ONLINE_PIN_KEY_TAG = "50000005"
        private const val TERMINAL_ONLINE_PIN_KSN_TAG = "50000022"
        private val KERNEL_EXCLUDED_TERMINAL_TAG_PREFIXES = listOf("500000", "FFFF81")
        private const val SUB = '\u001A'
        private const val FS = '\u001C'
        private const val DEFAULT_CAPK_EXPIRY_ASCII_HEX = "3230393931323331"
        private const val DEFAULT_TRACE_NO = "000000"
        private const val DEFAULT_APP_SELECT_AMOUNT = "000000000001"
        private const val DEFAULT_CASHBACK_AMOUNT = "000000000000"
        private const val DEFAULT_COUNTRY_CODE = "0840"
        private const val DEFAULT_CURRENCY_CODE = "0840"
        private const val DEFAULT_AUTH_CODE = "000000"
        private const val DEFAULT_EMV_TRANS_TYPE: Byte = 0x00
        private const val SALE_TRANS_TYPE: Byte = 0x00
        private const val REFUND_TRANS_TYPE: Byte = 0x20
        private const val DEFAULT_ONLINE_PIN_CAPABILITY = 1
        private const val RESULT_OFFLINE_APPROVED = "Y1"
        private const val RESULT_UNABLE_ONLINE_OFFLINE_APPROVED = "Y3"
        private const val RESULT_OFFLINE_DECLINED = "Z1"
        private const val RESULT_ONLINE_APPROVED = "Y4"
        private const val RESULT_ONLINE_DECLINED = "Z4"
        private const val RESULT_ONLINE_REQUEST = "A1"
        private const val RESULT_APP_RESELECT = "A4"
        private const val VISA_RID = "A000000003"
        private const val MASTERCARD_RID = "A000000004"
        private val APPROVED_SENSORY_RESULTS = setOf(
            RESULT_OFFLINE_APPROVED,
            RESULT_UNABLE_ONLINE_OFFLINE_APPROVED,
            RESULT_ONLINE_APPROVED,
        )
        private val DECLINED_RESULTS = setOf(
            RESULT_OFFLINE_DECLINED,
            RESULT_ONLINE_DECLINED,
        )
        private const val EMV_TAG_SELECTED_AID = 0x4F
        private const val EMV_TAG_COUNTRY_CODE = "9F1A"
        private const val T12_FATAL_ERROR = "11"
        private const val T12_MSR_FALLBACK = "14"
        private const val EMV_CANCELLED_ERROR = "EFFFFFFF"
        private const val T12_CANCELLED_ERROR = "11$EMV_CANCELLED_ERROR"
        private const val AID_TAG = "9F06"
        private const val TERMINAL_TRANSACTION_SEQUENCE_TAG = "9F41"
        private const val EMV_CID_TAG = "9F27"
        private const val CARDHOLDER_NAME_TAG = "5F20"
        private const val CARDHOLDER_NAME_EXTENDED_TAG = "9F0B"
        private const val EMV_SEQUENCE_PREFS = "pinpad_emv_sequence"
        private const val KEY_TRANSACTION_SEQUENCE = "transaction_sequence"
        private const val MAX_TRANSACTION_SEQUENCE = 99_999_999
        private const val EMV_PIN_BLOCK_TAG = "99"
        private const val EMV_DUKPT_KSN_TAG = "FFFF810B"
        private const val CRYPTOGRAM_TYPE_MASK = 0xC0
        private const val CRYPTOGRAM_AAC = 0x00
        private const val CRYPTOGRAM_TC = 0x40
        private const val MIN_PIN_KEY_HEX_CHARS = 16
        private const val AID_APPLICATION_VERSION_TAG = "9F09"
        private const val AID_FLOOR_LIMIT_TAG = "9F1B"
        private const val AID_DEFAULT_DDOL_TAG = "9F49"
        private const val AID_SELECTION_INDICATOR_TAG = "FFFF8214"
        private const val AID_THRESHOLD_TAG = "FFFF8215"
        private const val AID_TARGET_PERCENT_TAG = "FFFF8216"
        private const val AID_MAX_TARGET_PERCENT_TAG = "FFFF8217"
        private const val AID_TAC_DEFAULT_TAG = "FFFF8205"
        private const val AID_TAC_DENIAL_TAG = "FFFF8206"
        private const val AID_TAC_ONLINE_TAG = "FFFF8207"
        private const val PCD_READER_FLOOR_LIMIT_TAG = "DF8123"
        private const val PCD_READER_TRANS_LIMIT_TAG = "DF8124"
        private const val PCD_READER_CVM_LIMIT_TAG = "DF8126"
        private const val PCD_TAC_DEFAULT_TAG = "DF8120"
        private const val PCD_TAC_DENIAL_TAG = "DF8121"
        private const val PCD_TAC_ONLINE_TAG = "DF8122"
        private val CONTACTLESS_KERNEL_TLV_EXCLUDES = setOf(AID_TAG, "9C")
        private val CONTACTLESS_KERNEL_TLV_EXCLUDED_PREFIXES = listOf("FFFF")
        private val SESSION_KEY_HEX_LENGTHS = setOf(16, 32, 48)
        private const val AID_PARTIAL_SELECTION_VALUE = 1
        private const val NEXGO_PARTIAL_AID_MATCH = 0
        private const val NEXGO_EXACT_AID_MATCH = 1
        private val ONLINE_AUTH_TAGS = listOf(
            "9F02", "9F03", "9F1A", "95", "5F2A", "9A", "9C", "9F37", "82", "9F36",
            "9F26", "9F27", "9F10", "9F33", "9F34", "9F35", "9F1E", "84", "4F", "50",
            "5A", "57", "5F24", "5F34", "9B", "9F06", "9F4B", "9F6B",
        )
        private val REVERSAL_TAGS = ONLINE_AUTH_TAGS + listOf("9F41", "9F53")
        private val COMPLETION_TAGS = REVERSAL_TAGS + listOf("8A", "91", "9F5B", "71", "72")
        private val NON_EMV_CARD_DATA_TAGS = (
            COMPLETION_TAGS + listOf(
                "5F20", "5F25", "5F28", "5F30", "8C", "8D", "8E", "9F01", "9F07", "9F08",
                "9F09", "9F0B", "9F11", "9F12", "9F15", "9F16", "9F1C", "9F20", "9F21",
                "9F39", "9F3B", "9F42", "9F43", "9F44", "9F45", "9F4A",
            )
        ).distinct()
    }
}
