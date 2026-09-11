package one.globalconnect.pinpad.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.card.cpu.CPUCardHandler
import com.nexgo.oaf.apiv3.device.pinpad.AlgorithmModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.DukptAlgorithmModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.DukptKeyModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.DukptKeyTypeEnum
import com.nexgo.oaf.apiv3.device.pinpad.MacAlgorithmModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.OnPinPadInputListener
import com.nexgo.oaf.apiv3.device.pinpad.PinPad
import com.nexgo.oaf.apiv3.device.pinpad.PinAlgorithmModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinPadKeyCode
import com.nexgo.oaf.apiv3.device.pinpad.PinPadTypeEnum
import com.nexgo.oaf.apiv3.device.pinpad.PinKeyboardModeEnum
import com.nexgo.oaf.apiv3.device.pinpad.WorkKeyTypeEnum
import com.nexgo.oaf.apiv3.device.reader.CardInfoEntity
import com.nexgo.oaf.apiv3.device.reader.CardReader
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import com.nexgo.oaf.apiv3.device.reader.OnCardInfoListener
import com.nexgo.oaf.apiv3.emv.EmvEntryModeEnum
import one.globalconnect.pinpad.R
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.model.PinpadTransactionDisplay
import one.globalconnect.pinpad.protocol.PinpadKeypadKey
import one.globalconnect.pinpad.requirements.ApplicationRequirementClient
import one.globalconnect.pinpad.storage.PinpadJpegStore
import one.globalconnect.pinpad.storage.PinpadMediaStore
import one.globalconnect.pinpad.storage.PinpadPreferences
import one.globalconnect.pinpad.storage.PusnStore
import one.globalconnect.pinpad.ui.PinpadDisplayController
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PinpadDeviceCommands(
    context: Context,
    private val deviceEngine: DeviceEngine,
) {
    private val applicationContext = context.applicationContext
    private val prefs = PinpadPreferences(context)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val textToSpeechHandler = Handler(Looper.getMainLooper())
    @Volatile private var textToSpeech: TextToSpeech? = null
    @Volatile private var selectedTextToSpeechEngine: String? = null
    private val textToSpeechReady = AtomicBoolean(false)
    private val textToSpeechFailed = AtomicBoolean(false)
    private val textToSpeechInitializing = AtomicBoolean(false)
    private val applicationRequirementsRequested = ConcurrentHashMap.newKeySet<String>()
    private val speechProvisioningRequested = ConcurrentHashMap.newKeySet<String>()
    private val textToSpeechRequirementRetry = Runnable {
        val missing = firstMissingTextToSpeechRequirement()
        if (missing != null) {
            applicationRequirementsRequested.remove(missing.first)
            requestTextToSpeechApplication(missing)
        } else {
            initializeTextToSpeech()
        }
    }
    private val speechVoiceReadinessCheck = Runnable {
        textToSpeech?.takeIf { selectedTextToSpeechEngine == RHVOICE_ENGINE_PACKAGE }
            ?.let(::requestMissingSpeechVoices)
    }
    private val applicationRequirementReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ApplicationRequirementClient.ACTION_APPLICATION_REQUIREMENT_READY) {
                return
            }
            val capability = intent.getStringExtra(ApplicationRequirementClient.EXTRA_CAPABILITY)
                ?.takeIf { it in REQUIRED_TEXT_TO_SPEECH_CAPABILITIES }
                ?: return
            PinpadTraceLog.device(
                "text-to-speech application requirement ready capability=$capability; rescanning packages",
            )
            textToSpeechHandler.removeCallbacks(textToSpeechRequirementRetry)
            applicationRequirementsRequested.remove(capability)
            initializeTextToSpeech()
        }
    }
    private val jpegStore = PinpadJpegStore(context)
    private val mediaStore = PinpadMediaStore(context)
    private val pusnStore = PusnStore(context) {
        runCatching { deviceEngine.deviceInfo.sn }.getOrDefault("")
    }
    private val pinPad: PinPad by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        deviceEngine.pinPad.also { pinPad ->
            PinpadTraceLog.device("initPinPad type=INTERNAL")
            runCatching { pinPad.initPinPad(PinPadTypeEnum.INTERNAL) }
                .onSuccess { PinpadTraceLog.device("initPinPad result=$it") }
                .onFailure { Log.w(TAG, "Unable to initialize internal pinpad", it) }
        }
    }
    private val cardReader: CardReader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        deviceEngine.cardReader
    }
    private val primaryCpuCard: CPUCardHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        deviceEngine.getCPUCardHandler(CardSlotTypeEnum.ICC1)
    }
    private val contactEmv = PinpadContactEmvController(context, deviceEngine, ::startEmvOnlinePinEntry)
    private val mifare = PinpadMifareController(context, deviceEngine)
    private val secureRandom = SecureRandom()
    private val secretKeyQueue: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pinpad-secret-key-queue").apply { isDaemon = true }
    }
    private val msrSearchRunning = AtomicBoolean(false)
    private val multiInterfaceDetectionRunning = AtomicBoolean(false)
    @Volatile private var multiInterfaceCompleted: AtomicBoolean? = null
    @Volatile private var multiInterfaceOnResult: ((MultiInterfaceDetectionResult) -> Unit)? = null
    @Volatile private var msrReadCancelCallback: (() -> Unit)? = null
    @Volatile private var pcdReadCancelCallback: (() -> Unit)? = null
    @Volatile private var pinEntryCompleted: AtomicBoolean? = null
    @Volatile private var pinEntryOnResult: ((PinEntryResult) -> Unit)? = null
    @Volatile private var cachedMsrTrackData: MsrTrackData? = null
    @Volatile private var cachedPcdTrackData: MsrTrackData? = null
    @Volatile private var msrTrackMode = prefs.msrTrackMode().firstOrNull() ?: MSR_DISABLED
    @Volatile private var msrRetryCount = prefs.msrRetryCount()
    @Volatile private var msrOutputFormat = prefs.msrOutputFormat().firstOrNull() ?: MSR_CLEAR_TEXT
    @Volatile private var msrAutoArmEnabled = prefs.msrAutoArmEnabled()
    @Volatile private var msrAutoArmCallback: ((MsrTrackData) -> Unit)? = null
    @Volatile private var pcdTrackMode = MSR_ALL_TRACKS
    @Volatile private var pcdOutputFormat = MSR_CLEAR_TEXT
    @Volatile private var pcdAutoArmEnabled = false
    @Volatile private var activeDukptKeySet = prefs.activeDukptKeySet()
    @Volatile private var dukptFullKsnOutput = prefs.dukptFullKsnOutput()
    @Volatile private var secretMasterKeyGeneration = 0
    @Volatile private var secretMasterKeyCompletedGeneration = 0
    private val secretMasterKeyLoadResults = ConcurrentHashMap<Int, Boolean>()
    @Volatile private var secretMasterKeyLoadedThisRun = false
    @Volatile private var secretSessionKeyIdsLoaded = emptySet<Int>()
    @Volatile private var primaryCpuCardPowered = false
    @Volatile private var activeSamSlot = CardSlotTypeEnum.PSAM1
    @Volatile private var samCpuCardPowered = false

    init {
        registerApplicationRequirementReceiver()
        initializeTextToSpeech()
        contactEmv.applyStoredConfiguration()
    }

    fun shutdown() {
        runCatching { applicationContext.unregisterReceiver(applicationRequirementReceiver) }
        textToSpeechHandler.removeCallbacksAndMessages(null)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        textToSpeechReady.set(false)
        textToSpeechInitializing.set(false)
        secretKeyQueue.shutdownNow()
    }

    fun handleKeypadKey(key: PinpadKeypadKey): Boolean {
        return when (key) {
            PinpadKeypadKey.Function1 -> PinpadDisplayController.moveApplicationSelection(-1)
            PinpadKeypadKey.Function2 -> PinpadDisplayController.moveApplicationSelection(1)
            PinpadKeypadKey.Enter -> PinpadDisplayController.confirmApplicationSelection()
            else -> false
        }.also { handled ->
            if (handled) PinpadTraceLog.device("application selection keypad key=$key")
        }
    }

    fun loadMasterKey(keyId: Char, keyPayloadAscii: String): KeyLoadResult {
        val slot = keyId.toMasterKeySlot()
        if (slot == null) {
            PinpadTraceLog.device("loadMasterKey keyId=$keyId invalid slot")
            return KeyLoadResult.Error('A')
        }
        val material = keyPayloadAscii.substringBefore(FS_CHAR)
        val materialType = if (material.isHexKeyValue()) "CLEAR_HEX" else "TR31"
        val attribute = parseMasterKeyAttribute(keyId, keyPayloadAscii)
        PinpadTraceLog.device(
            "loadMasterKey keyId=$keyId slot=$slot materialType=$materialType materialChars=${material.length} " +
                "payloadChars=${keyPayloadAscii.length} attr=${attribute.usage}${attribute.mode}${attribute.algorithm}",
        )

        val result = if (materialType == "CLEAR_HEX") {
            injectClearKey(slot, material, attribute)
        } else {
            PinpadTraceLog.device("loadMasterKey keyId=$keyId TR31 deferred/unsupported in current SDK build")
            KeyLoadResult.Error('A')
        }
        if (result == KeyLoadResult.Success) {
            prefs.setMasterKeyAttribute(keyId, attribute.storageValue())
            showKeyLoadCue(KEY_LOAD_MASTER_MESSAGE, returnToKeyInjectionMode = true)
        }
        return result
    }

    fun loadSecretMasterKey(payloadAscii: String): KeyLoadResult {
        val keyOption = payloadAscii.firstOrNull()
        val keyHex = payloadAscii.drop(1)
        if (keyOption == null || keyOption !in SECRET_MASTER_KEY_OPTIONS ||
            keyHex.length !in SECRET_KEY_HEX_LENGTHS || !keyHex.isHex()
        ) {
            PinpadTraceLog.device(
                "loadSecretMasterKey invalid option=${keyOption ?: "<missing>"} keyChars=${keyHex.length}",
            )
            return KeyLoadResult.Error('A')
        }
        val keyBytes = keyHex.hexToBytesOrNull() ?: return KeyLoadResult.Error('A')
        val generation = secretMasterKeyGeneration + 1
        secretMasterKeyGeneration = generation
        secretMasterKeyLoadResults.clear()
        secretMasterKeyLoadedThisRun = true
        secretSessionKeyIdsLoaded = emptySet()
        return if (enqueueSecretKeyWork("secret MK generation=$generation") {
            pinPad.setAlgorithmMode(AlgorithmModeEnum.DES)
            val success = SECRET_KEY_ID_RANGE.all { logicalSlot ->
                val slot = logicalSlot.toSecretSdkSlot()
                val result = pinPad.writeMKey(slot, keyBytes, keyBytes.size)
                PinpadTraceLog.device("write secret MKey id=$logicalSlot slot=$slot option=$keyOption sdkResult=$result")
                if (logicalSlot == SECRET_KEY_ID_RANGE.first && result == SdkResult.Success) {
                    showKeyLoadCue(KEY_LOAD_SECRET_MASTER_MESSAGE)
                }
                result == SdkResult.Success
            }
            secretMasterKeyCompletedGeneration = maxOf(secretMasterKeyCompletedGeneration, generation)
            secretMasterKeyLoadResults[generation] = success
            if (!success && secretMasterKeyGeneration == generation) secretSessionKeyIdsLoaded = emptySet()
            PinpadTraceLog.device("secret MKey batch generation=$generation success=$success")
        }) {
            KeyLoadResult.Success
        } else {
            KeyLoadResult.Error('Z')
        }
    }

    fun loadSecretSessionKey(payloadAscii: String): KeyLoadResult {
        val keyId = payloadAscii.firstOrNull()
        val logicalSlot = keyId?.digitToIntOrNull()
        val keyHex = payloadAscii.drop(1)
        if (logicalSlot == null || logicalSlot !in SECRET_KEY_ID_RANGE ||
            keyHex.length !in SECRET_KEY_HEX_LENGTHS || !keyHex.isHex()
        ) {
            PinpadTraceLog.device(
                "loadSecretSessionKey invalid keyId=${keyId ?: "<missing>"} keyChars=${keyHex.length}",
            )
            return KeyLoadResult.Error('A')
        }
        val slot = logicalSlot.toSecretSdkSlot()
        val keyBytes = keyHex.hexToBytesOrNull() ?: return KeyLoadResult.Error('A')
        val generation = secretMasterKeyGeneration
        return if (enqueueSecretKeyWork("secret SKey id=$logicalSlot generation=$generation") {
            if (generation > 0) {
                val masterSuccess = secretMasterKeyLoadResults[generation] == true
                if (!masterSuccess || secretMasterKeyCompletedGeneration < generation) {
                    PinpadTraceLog.device(
                        "write secret SKey id=$logicalSlot slot=$slot skipped masterGeneration=$generation " +
                            "completed=$secretMasterKeyCompletedGeneration success=$masterSuccess",
                    )
                    return@enqueueSecretKeyWork
                }
            }
            pinPad.setAlgorithmMode(AlgorithmModeEnum.DES)
            val result = pinPad.writeWKey(slot, WorkKeyTypeEnum.PINKEY, keyBytes, keyBytes.size)
            PinpadTraceLog.device("write secret SKey id=$logicalSlot slot=$slot type=PINKEY sdkResult=$result")
            if (result == SdkResult.Success) {
                secretSessionKeyIdsLoaded = secretSessionKeyIdsLoaded + logicalSlot
                showKeyLoadCue(KEY_LOAD_SECRET_SESSION_MESSAGE)
            }
        }) {
            KeyLoadResult.Success
        } else {
            KeyLoadResult.Error('Z')
        }
    }

    private fun enqueueSecretKeyWork(label: String, work: () -> Unit): Boolean {
        return runCatching {
            secretKeyQueue.execute {
                runCatching(work)
                    .onFailure {
                        Log.w(TAG, "Secret key queued work failed: $label", it)
                        PinpadTraceLog.device("secret key queued work failed label=$label error=${it.message}")
                    }
            }
        }.onFailure {
            Log.w(TAG, "Unable to enqueue secret key work: $label", it)
            PinpadTraceLog.device("secret key enqueue failed label=$label error=${it.message}")
        }.isSuccess
    }

    fun masterKeyLoaded(keyId: Char): Boolean {
        val slot = keyId.toMasterKeySlot()
        if (slot == null) {
            PinpadTraceLog.device("isKeyExist keyId=$keyId invalid slot")
            return false
        }
        val attribute = masterKeyAttribute(keyId)
        val workKeyType = attribute.workKeyType()
        PinpadTraceLog.device("isKeyExist keyId=$keyId slot=$slot usage=${attribute.usage} workKeyType=$workKeyType")
        return runCatching {
            if (workKeyType == null) pinPad.isKeyExist(slot) else pinPad.isKeyExist(slot, workKeyType)
        }
            .onSuccess { PinpadTraceLog.device("isKeyExist slot=$slot usage=${attribute.usage} result=$it") }
            .onFailure { Log.w(TAG, "Unable to check master key existence", it) }
            .getOrDefault(false)
    }

    fun masterKeyKcv(keyId: Char): String? {
        val slot = keyId.toMasterKeySlot()
        if (slot == null) {
            PinpadTraceLog.device("masterKeyKcv keyId=$keyId invalid slot")
            return null
        }
        if (!masterKeyLoaded(keyId)) {
            PinpadTraceLog.device("masterKeyKcv keyId=$keyId slot=$slot not loaded")
            return null
        }
        val attribute = masterKeyAttribute(keyId)
        val workKeyType = attribute.workKeyType()
        if (workKeyType != null) {
            PinpadTraceLog.device("calcWKeyKCV keyId=$keyId slot=$slot usage=${attribute.usage} type=$workKeyType")
            return runCatching {
                pinPad.calcWKeyKCV(slot, workKeyType)
            }.fold(
                onSuccess = { kcv ->
                    val value = kcv?.take(KCV_BYTES)?.toByteArray()?.toHex()
                    PinpadTraceLog.device("calcWKeyKCV slot=$slot type=$workKeyType resultChars=${value?.length ?: 0}")
                    value
                },
                onFailure = {
                    Log.w(TAG, "Unable to calculate work key KCV", it)
                    PinpadTraceLog.device("calcWKeyKCV slot=$slot type=$workKeyType failed=${it.message}")
                    null
                },
            )
        }
        PinpadTraceLog.device("encryptByMKey for KCV keyId=$keyId slot=$slot usage=${attribute.usage} dataBytes=$KCV_ZERO_BLOCK_BYTES")
        return runCatching {
            pinPad.encryptByMKey(slot, ByteArray(KCV_ZERO_BLOCK_BYTES), KCV_ZERO_BLOCK_BYTES)
        }.fold(
            onSuccess = { encrypted ->
                val kcv = encrypted?.take(KCV_BYTES)?.toByteArray()?.toHex()
                PinpadTraceLog.device("encryptByMKey KCV slot=$slot resultChars=${kcv?.length ?: 0}")
                kcv
            },
            onFailure = {
                Log.w(TAG, "Unable to calculate master key KCV", it)
                PinpadTraceLog.device("encryptByMKey KCV slot=$slot failed=${it.message}")
                null
            },
        )
    }

    fun masterKeyAttributePayload(keyId: Char): String {
        return masterKeyAttribute(keyId).protocolPayload()
    }

    fun startMasterSessionPinEntry(
        commandId: String,
        payload: String,
        onResult: (PinEntryResult) -> Unit,
    ): PinEntryStartResult {
        val request = parsePinEntryRequest(commandId, payload, PinKeyScheme.MASTER_SESSION)
            ?: return PinEntryStartResult.ImmediateResponse("E")
        return startPinEntry(request, onResult)
    }

    fun startDukptPinEntry(
        commandId: String,
        payload: String,
        onResult: (PinEntryResult) -> Unit,
    ): PinEntryStartResult {
        val request = parsePinEntryRequest(commandId, payload, PinKeyScheme.DUKPT)
            ?: return PinEntryStartResult.ImmediateResponse("8")
        return startPinEntry(request, onResult)
    }

    /**
     * Starts a two-entry new-PIN capture encrypted under the active MK/SK PIN key.
     *
     * @param commandId source protocol command, normally `7G`.
     * @param payload command payload containing the account and session-key data.
     * @param onResult receives one confirmed encrypted PIN block, an error, or cancellation.
     * @return whether capture started or an immediate protocol response; starts secure keypad input as a side effect.
     */
    fun startMasterSessionNewPinEntry(
        commandId: String,
        payload: String,
        onResult: (PinEntryResult) -> Unit,
    ): PinEntryStartResult {
        val request = parsePinEntryRequest(commandId, payload, PinKeyScheme.MASTER_SESSION)
            ?: return PinEntryStartResult.ImmediateResponse("E")
        return startNewPinEntry(request, onResult)
    }

    /**
     * Starts a two-entry new-PIN capture encrypted under the active DUKPT PIN key.
     *
     * @param commandId source protocol command, normally `7H`.
     * @param payload command payload containing the card account.
     * @param onResult receives one confirmed encrypted PIN block and KSN, an error, or cancellation.
     * @return whether capture started or an immediate protocol response; reserves one KSN as a side effect.
     */
    fun startDukptNewPinEntry(
        commandId: String,
        payload: String,
        onResult: (PinEntryResult) -> Unit,
    ): PinEntryStartResult {
        val request = parsePinEntryRequest(commandId, payload, PinKeyScheme.DUKPT)
            ?: return PinEntryStartResult.ImmediateResponse("8")
        return startNewPinEntry(request, onResult)
    }

    fun startSecretPinEntry(
        commandId: String,
        payload: String,
        onResult: (PinEntryResult) -> Unit,
    ): PinEntryStartResult {
        val request = parseSecretPinEntryRequest(commandId, payload)
            ?: return PinEntryStartResult.ImmediateResponse("8")
        return startPinEntry(request, onResult)
    }

    fun startEmvOnlinePinEntry(
        request: PinpadContactEmvController.EmvOnlinePinRequest,
        onResult: (PinpadContactEmvController.EmvOnlinePinResult) -> Unit,
    ): Boolean {
        val scheme = when (request.pinScheme) {
            PinpadContactEmvController.EmvPinScheme.DUKPT -> PinKeyScheme.DUKPT
            PinpadContactEmvController.EmvPinScheme.MASTER_SESSION -> PinKeyScheme.MASTER_SESSION
        }
        val pinRequest = PinEntryRequest(
            commandId = request.sourceCommand,
            scheme = scheme,
            account = request.account,
            sessionKey = emvSessionKeyForActiveMaster(request.sessionKey, scheme),
            minPin = DEFAULT_MIN_PIN,
            maxPin = DEFAULT_MAX_PIN,
            allowNullPin = false,
            timeoutSeconds = DEFAULT_PIN_TIMEOUT_SECONDS,
            promptLines = emptyList(),
            completionPrompt = "",
        )
        val startResult = startPinEntry(pinRequest) { result ->
            onResult(emvOnlinePinResultFrom(result, scheme))
        }
        return when (startResult) {
            PinEntryStartResult.Started -> true
            is PinEntryStartResult.ImmediateResponse -> {
                PinpadTraceLog.device(
                    "EMV PIN input immediate error command=${request.sourceCommand} code=${startResult.payload}",
                )
                false
            }
        }
    }

    private fun emvSessionKeyForActiveMaster(sessionKey: String, scheme: PinKeyScheme): String {
        if (scheme != PinKeyScheme.MASTER_SESSION) return ""
        val activeKeyId = prefs.activeMasterKeyId().firstOrNull() ?: '0'
        val usage = storedMasterKeyAttribute(activeKeyId)?.usage ?: return sessionKey
        if (usage != "P0") return sessionKey
        if (sessionKey.isNotBlank()) return sessionKey
        PinpadTraceLog.device(
            "EMV PIN using active P0 working key keyId=$activeKeyId with zero session-key placeholder",
        )
        return "0".repeat(DEFAULT_SESSION_KEY_HEX_CHARS)
    }

    fun cancelPinEntry(sendResult: Boolean = false): Boolean {
        PinpadTraceLog.device("PIN cancel requested")
        val completed = pinEntryCompleted
        val onResult = pinEntryOnResult
        if (completed == null && onResult == null) {
            PinpadTraceLog.device("PIN cancel ignored; no active PIN entry")
            return false
        }
        if (sendResult) {
            if (completed != null && onResult != null) {
                completePinEntry(completed, PinEntryResult.Eot, onResult)
            } else {
                clearPinEntryPendingCallback()
            }
        }
        runCatching { pinPad.cancelInput() }
            .onFailure { Log.w(TAG, "Unable to cancel PIN entry", it) }
        return true
    }

    fun loadDukptInitialKey(keySet: Int, payload: String): DukptLoadResult {
        if (keySet !in DUKPT_KEY_SET_RANGE) return DukptLoadResult(false, '4')
        val material = payload.substringBefore(FS_CHAR).trim()
        PinpadTraceLog.device("loadDukptInitialKey keySet=$keySet chars=${material.length}")
        return if (material.length == DUKPT_CLEAR_KEY_CHARS && material.isHex()) {
            injectClearDukptKey(keySet, material).also { result ->
                if (result.success) {
                    showKeyLoadCue(KEY_LOAD_IPEK_MESSAGE, returnToKeyInjectionMode = true)
                }
            }
        } else {
            PinpadTraceLog.device("loadDukptInitialKey keySet=$keySet TR31 deferred/unsupported in current SDK build")
            DukptLoadResult(false, 'A')
        }
    }

    fun selectDukptKeySet(keySet: Char): Boolean {
        val slot = keySet.digitToIntOrNull() ?: return false
        if (slot !in DUKPT_KEY_SET_RANGE) return false
        activeDukptKeySet = slot
        prefs.setActiveDukptKeySet(slot)
        PinpadTraceLog.device("selectDukptKeySet keySet=$slot")
        return true
    }

    fun setDukptKsnOutputFormat(format: Char): Boolean {
        if (format !in setOf('0', '1')) return false
        dukptFullKsnOutput = format == '1'
        prefs.setDukptFullKsnOutput(dukptFullKsnOutput)
        PinpadTraceLog.device("setDukptKsnOutputFormat full=$dukptFullKsnOutput")
        return true
    }

    fun dukptKeySetStatus(keySet: Char, includeInfo: Boolean): DukptStatus {
        val slot = keySet.digitToIntOrNull()
        if (slot == null || slot !in DUKPT_KEY_SET_RANGE) return DukptStatus('0', null)
        val exists = runCatching { pinPad.dukptCurrentKsn(slot) != null }
            .onFailure { Log.w(TAG, "Unable to query DUKPT KSN", it) }
            .getOrDefault(false)
        val kcv = if (exists && includeInfo) dukptKcv(slot) else null
        return DukptStatus(if (exists) 'F' else '0', kcv)
    }

    fun generateMac(payload: String): MacResult {
        val fields = payload.split(FS_CHAR)
        val header = fields.firstOrNull().orEmpty()
        val message = fields.drop(1).joinToString(FS_CHAR.toString()).ifEmpty { header.drop(2) }
        val keyId = header.getOrNull(1) ?: prefs.activeMasterKeyId().firstOrNull() ?: '0'
        val slot = keyId.toMasterKeySlot() ?: return MacResult(false, "")
        val data = if (message.isHex() && message.length % 2 == 0) {
            message.hexToBytesOrNull() ?: message.toByteArray(Charsets.US_ASCII)
        } else {
            message.toByteArray(Charsets.US_ASCII)
        }
        return runCatching {
            pinPad.setAlgorithmMode(AlgorithmModeEnum.DES)
            pinPad.calcMac(slot, MacAlgorithmModeEnum.X919, data)
        }.fold(
            onSuccess = { mac ->
                val value = mac?.toHex().orEmpty().take(MAC_RESPONSE_HEX_CHARS)
                PinpadTraceLog.device("calcMac keyId=$keyId slot=$slot resultChars=${value.length}")
                MacResult(value.isNotEmpty(), value)
            },
            onFailure = {
                Log.w(TAG, "Unable to calculate MAC", it)
                MacResult(false, "")
            },
        )
    }

    fun selectActiveMasterKey(keyId: Char): Boolean {
        val slot = keyId.toMasterKeySlot()
        if (slot == null) {
            PinpadTraceLog.device("selectActiveMasterKey keyId=$keyId invalid slot")
            return false
        }
        if (slot !in 0..9) return false
        prefs.setActiveMasterKeyId(keyId.toString())
        PinpadTraceLog.device("selectActiveMasterKey keyId=$keyId slot=$slot")
        return true
    }

    fun randomBlockHex(): String {
        val random = ByteArray(RANDOM_BLOCK_BYTES)
        val sdkResult = runCatching { pinPad.getRandomNum(random) }
            .onFailure { Log.w(TAG, "Nexgo random generation failed; using SecureRandom fallback", it) }
            .getOrDefault(SdkResult.Fail)
        if (sdkResult != SdkResult.Success) {
            secureRandom.nextBytes(random)
        }
        return random.toHex()
    }

    fun setKeypadBeeper(enabled: Boolean): Boolean {
        prefs.setKeypadBeeperEnabled(enabled)
        return prefs.keypadBeeperEnabled() == enabled
    }

    fun setPromptLanguage(index: Char): Char {
        PinpadTraceLog.device("prompt language index=$index ignored; Android locale remains authoritative")
        return '0'
    }

    fun promptLanguageIndex(): Char = prefs.promptLanguageIndex().firstOrNull() ?: '0'

    fun setDisplayFontSize(option: Char): Char {
        if (option !in '0'..'5') return '1'
        prefs.setDisplayFontSize(option.toString())
        PinpadTraceLog.device("display font size option=$option")
        return '0'
    }

    fun setDisplayFontColor(foreground: String, background: String): Char {
        if (!foreground.isRgbHex() || !background.isRgbHex()) return '1'
        prefs.setDisplayFontColor(foreground.uppercase(), background.uppercase())
        PinpadTraceLog.device("display font color foreground=${foreground.uppercase()} background=${background.uppercase()}")
        return '0'
    }

    fun setIdlePrompt(prompt: String): Boolean {
        prefs.setIdleMessage(prompt)
        PinpadDisplayController.updateIdleMessage(prompt)
        PinpadTraceLog.device("idle prompt chars=${prompt.length}")
        return true
    }

    fun controlBeeper(count: Int, durationUnits: Int, intervalUnits: Int): Boolean {
        if (count !in 1..9 || durationUnits !in 0..999 || intervalUnits !in 0..999) return false
        Thread {
            repeat(count) { index ->
                runCatching { deviceEngine.beeper.beep(durationUnits * 10) }
                    .onFailure { Log.w(TAG, "Beeper command failed", it) }
                if (index < count - 1 && intervalUnits > 0) {
                    runCatching { Thread.sleep(intervalUnits * 10L) }
                        .onFailure { Thread.currentThread().interrupt() }
                }
            }
        }.apply {
            name = "PINPADBeeper"
            isDaemon = true
            start()
        }
        return true
    }

    fun primarySmartCardPresent(): Boolean {
        return runCatching { cardReader.isCardExist(CardSlotTypeEnum.ICC1) }
            .onSuccess { PinpadTraceLog.device("CPU I00 cardPresent=$it") }
            .onFailure {
                Log.w(TAG, "Unable to query primary smart card presence", it)
                PinpadTraceLog.device("CPU I00 presence failed=${it.message}")
            }
            .getOrDefault(false)
    }

    fun primarySmartCardColdReset(): CpuCardResult {
        val atrBuffer = ByteArray(CPU_ATR_BUFFER_BYTES)
        return runCatching {
            if (!primarySmartCardPresent()) return@runCatching CpuCardResult.Error("01")
            if (!primaryCpuCard.powerOn(atrBuffer)) return@runCatching CpuCardResult.Error("01")
            primaryCpuCardPowered = true
            val atr = cpuAtrHex(atrBuffer)
            if (atr.isBlank()) CpuCardResult.Error("01") else CpuCardResult.Response("I02", atr)
        }.onFailure {
            Log.w(TAG, "Unable to reset primary smart card", it)
            PinpadTraceLog.device("CPU I01 reset failed=${it.message}")
            primaryCpuCardPowered = false
        }.getOrElse {
            CpuCardResult.Error("01")
        }.also { result ->
            PinpadTraceLog.device("CPU I01 result=$result")
        }
    }

    fun deactivatePrimarySmartCard(): CpuCardResult {
        return runCatching {
            primaryCpuCard.powerOff()
            primaryCpuCardPowered = false
            CpuCardResult.Response("I04", "")
        }.onFailure {
            Log.w(TAG, "Unable to deactivate primary smart card", it)
            PinpadTraceLog.device("CPU I04 deactivate failed=${it.message}")
        }.getOrElse {
            CpuCardResult.Error("08")
        }.also { result ->
            PinpadTraceLog.device("CPU I04 result=$result")
        }
    }

    fun exchangePrimarySmartCardApdu(apduHex: String): CpuCardResult {
        if (!primaryCpuCardPowered) return CpuCardResult.Error("03")
        if (apduHex.length < CPU_APDU_MIN_HEX_CHARS || apduHex.length > CPU_APDU_MAX_HEX_CHARS) {
            return CpuCardResult.Error("02")
        }
        val apdu = apduHex.hexToBytesOrNull() ?: return CpuCardResult.Error("04")
        return runCatching {
            val response = primaryCpuCard.exchangeAPDUCmd(apdu)
            if (response == null || response.isEmpty()) {
                CpuCardResult.Error("08")
            } else {
                CpuCardResult.Response("I07", response.toHex())
            }
        }.onFailure {
            Log.w(TAG, "Unable to exchange APDU with primary smart card", it)
            PinpadTraceLog.device("CPU I06 APDU failed=${it.message}")
        }.getOrElse {
            CpuCardResult.Error("08")
        }.also { result ->
            PinpadTraceLog.device("CPU I06 result=$result requestBytes=${apdu.size}")
        }
    }

    fun unsupportedCpuCardCommand(commandId: String): CpuCardResult {
        PinpadTraceLog.device("CPU $commandId unsupported")
        return CpuCardResult.Error("09")
    }

    fun processMifareCommand(commandId: String, payload: String): String {
        return mifare.process(commandId, payload)
    }

    fun samCardColdReset(): CpuCardResult {
        val atrBuffer = ByteArray(CPU_ATR_BUFFER_BYTES)
        return runCatching {
            if (!activeSamCpuCard().powerOn(atrBuffer)) return@runCatching CpuCardResult.Error("01")
            samCpuCardPowered = true
            val atr = cpuAtrHex(atrBuffer)
            if (atr.isEmpty()) {
                samCpuCardPowered = false
                CpuCardResult.Error("01")
            } else {
                CpuCardResult.Response("I12", atr)
            }
        }.onFailure {
            samCpuCardPowered = false
            Log.w(TAG, "Unable to cold reset SAM card", it)
            PinpadTraceLog.device("CPU I11 SAM cold reset failed=${it.message}")
        }.getOrElse {
            CpuCardResult.Error("01")
        }.also { result ->
            PinpadTraceLog.device("CPU I11 result=$result slot=$activeSamSlot")
        }
    }

    fun deactivateSamCard(): CpuCardResult {
        return runCatching {
            activeSamCpuCard().powerOff()
            samCpuCardPowered = false
            CpuCardResult.Response("I14", "")
        }.onFailure {
            Log.w(TAG, "Unable to deactivate SAM card", it)
            PinpadTraceLog.device("CPU I14 SAM deactivate failed=${it.message}")
        }.getOrElse {
            CpuCardResult.Error("08")
        }.also { result ->
            PinpadTraceLog.device("CPU I14 result=$result slot=$activeSamSlot")
        }
    }

    fun selectSamInterface(interfaceCode: Char?): CpuCardResult {
        val slot = when (interfaceCode) {
            '1' -> CardSlotTypeEnum.PSAM1
            '2' -> CardSlotTypeEnum.PSAM2
            else -> return CpuCardResult.Error("06")
        }
        activeSamSlot = slot
        samCpuCardPowered = false
        PinpadTraceLog.device("CPU I15 select SAM slot=$slot")
        return CpuCardResult.Response("I15", interfaceCode.toString())
    }

    fun exchangeSamCardApdu(apduHex: String): CpuCardResult {
        if (!samCpuCardPowered) return CpuCardResult.Error("03")
        if (apduHex.length < CPU_APDU_MIN_HEX_CHARS || apduHex.length > CPU_APDU_MAX_HEX_CHARS) {
            return CpuCardResult.Error("02")
        }
        val apdu = apduHex.hexToBytesOrNull() ?: return CpuCardResult.Error("04")
        return runCatching {
            val response = activeSamCpuCard().exchangeAPDUCmd(apdu)
            if (response == null || response.isEmpty()) {
                CpuCardResult.Error("08")
            } else {
                CpuCardResult.Response("I17", response.toHex())
            }
        }.onFailure {
            Log.w(TAG, "Unable to exchange APDU with SAM card", it)
            PinpadTraceLog.device("CPU I16 SAM APDU failed=${it.message}")
        }.getOrElse {
            CpuCardResult.Error("08")
        }.also { result ->
            PinpadTraceLog.device("CPU I16 result=$result slot=$activeSamSlot requestBytes=${apdu.size}")
        }
    }

    fun startMsrRead(
        transactionDisplay: PinpadTransactionDisplay? = null,
        onRead: (MsrTrackData) -> Unit,
        onCancel: () -> Unit = {},
    ): Boolean {
        consumeCachedMsrTrackData()?.let { cached ->
            PinpadTraceLog.device(
                "MSR using cached QK data t1=${cached.track1.length} t2=${cached.track2.length} t3=${cached.track3.length}",
            )
            PinpadDisplayController.showProcessing()
            onRead(cached)
            return true
        }
        return startMsrSearch(autoArm = false, transactionDisplay = transactionDisplay, onRead = onRead, onCancel = onCancel)
    }

    fun startPcdTrackRead(
        onRead: (MsrTrackData) -> Unit,
        onCancel: () -> Unit = {},
    ): Boolean {
        consumeCachedPcdTrackData()?.takeIf { it.hasAnyTrack() }?.let { cached ->
            PinpadTraceLog.device(
                "PCD using cached QK data t1=${cached.track1.length} t2=${cached.track2.length} t3=${cached.track3.length}",
            )
            PinpadDisplayController.showProcessing()
            onRead(cached)
            return true
        }
        return startPcdEmvTrackRead(onRead = onRead, onCancel = onCancel)
    }

    fun startDualTrackRead(
        onMsrRead: (MsrTrackData) -> Unit,
        onPcdRead: (MsrTrackData) -> Unit,
        onCancel: () -> Unit = {},
    ): Boolean {
        if (msrTrackMode == MSR_DISABLED && pcdTrackMode == MSR_DISABLED) {
            PinpadTraceLog.device("dual track read skipped because MSR and PCD are disabled")
            return false
        }
        if (!msrSearchRunning.compareAndSet(false, true)) {
            PinpadTraceLog.device("dual track read already running")
            return true
        }
        msrReadCancelCallback = onCancel
        pcdReadCancelCallback = onCancel
        val slots = hashSetOf<CardSlotTypeEnum>().apply {
            if (msrTrackMode != MSR_DISABLED) add(CardSlotTypeEnum.SWIPE)
            if (pcdTrackMode != MSR_DISABLED) add(CardSlotTypeEnum.RF)
        }
        val listener = object : OnCardInfoListener {
            override fun onCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
                msrSearchRunning.set(false)
                clearMsrReadCancelCallback()
                clearPcdReadCancelCallback()
                PinpadTraceLog.device("dual track onCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot}")
                if (retCode != SdkResult.Success || cardInfo == null) {
                    onCancel()
                    return
                }
                when (cardInfo.cardExistslot) {
                    CardSlotTypeEnum.SWIPE -> {
                        PinpadDisplayController.showProcessing()
                        onMsrRead(trackDataFromCardInfo(cardInfo))
                    }
                    CardSlotTypeEnum.RF -> {
                        val pcdData = pcdTrackDataFromCardInfo(cardInfo)
                        if (pcdData.hasAnyTrack()) {
                            PinpadDisplayController.showProcessing()
                            onPcdRead(pcdData)
                        } else {
                            PinpadTraceLog.device("dual track RF callback had no track data; falling back to PCD EMV track read")
                            if (!startPcdEmvTrackRead(onPcdRead, onCancel)) {
                                onCancel()
                            }
                        }
                    }
                    else -> onCancel()
                }
            }

            override fun onSwipeIncorrect() {
                PinpadTraceLog.device("dual track onSwipeIncorrect")
                PinpadDisplayController.showPresentCard()
            }

            override fun onMultipleCards() {
                PinpadTraceLog.device("dual track onMultipleCards")
            }
        }
        val result = runCatching {
            cardReader.searchCard(slots, MSR_SEARCH_TIMEOUT_SECONDS, listener)
        }.onFailure {
            msrSearchRunning.set(false)
            clearMsrReadCancelCallback()
            clearPcdReadCancelCallback()
            Log.w(TAG, "Unable to start dual track read", it)
            PinpadTraceLog.device("dual track search failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        if (result != SdkResult.Success) {
            msrSearchRunning.set(false)
            clearMsrReadCancelCallback()
            clearPcdReadCancelCallback()
            PinpadTraceLog.device("dual track search start sdkResult=$result")
            return false
        }
        PinpadDisplayController.showPresentCard()
        PinpadTraceLog.device("dual track search started msrMode=$msrTrackMode pcdMode=$pcdTrackMode")
        return true
    }

    fun startMultiInterfaceDetection(
        transactionDisplay: PinpadTransactionDisplay? = null,
        onResult: (MultiInterfaceDetectionResult) -> Unit,
    ): MultiInterfaceDetectionStartResult {
        if (!multiInterfaceDetectionRunning.compareAndSet(false, true)) {
            PinpadTraceLog.device("multi-interface detection already running")
            return MultiInterfaceDetectionStartResult.Started
        }
        val completed = AtomicBoolean(false)
        multiInterfaceCompleted = completed
        multiInterfaceOnResult = onResult
        val listener = object : OnCardInfoListener {
            override fun onCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
                PinpadTraceLog.device("multi-interface onCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot}")
                val result = if (retCode == SdkResult.Success) {
                    when (cardInfo?.cardExistslot) {
                        CardSlotTypeEnum.SWIPE -> {
                            cacheMsrTrackData(cardInfo)
                            MultiInterfaceDetectionResult.msr()
                        }
                        CardSlotTypeEnum.ICC1 -> MultiInterfaceDetectionResult.icc()
                        CardSlotTypeEnum.RF -> {
                            cachePcdTrackData(cardInfo)
                            MultiInterfaceDetectionResult.pcd()
                        }
                        else -> MultiInterfaceDetectionResult.canceled()
                    }
                } else {
                    MultiInterfaceDetectionResult.canceled()
                }
                completeMultiInterfaceDetection(completed, result, onResult)
            }

            override fun onSwipeIncorrect() {
                PinpadTraceLog.device("multi-interface onSwipeIncorrect")
                completeMultiInterfaceDetection(completed, MultiInterfaceDetectionResult.msr(), onResult)
            }

            override fun onMultipleCards() {
                PinpadTraceLog.device("multi-interface onMultipleCards")
                completeMultiInterfaceDetection(completed, MultiInterfaceDetectionResult.canceled(), onResult)
            }
        }
        val result = runCatching {
            cardReader.searchCard(
                hashSetOf(CardSlotTypeEnum.SWIPE, CardSlotTypeEnum.ICC1, CardSlotTypeEnum.RF),
                MULTI_INTERFACE_SEARCH_TIMEOUT_SECONDS,
                listener,
            )
        }.onFailure {
            multiInterfaceDetectionRunning.set(false)
            clearMultiInterfacePendingCallback()
            Log.w(TAG, "Unable to start multi-interface detection", it)
            PinpadTraceLog.device("multi-interface search failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        if (result != SdkResult.Success) {
            multiInterfaceDetectionRunning.set(false)
            clearMultiInterfacePendingCallback()
            PinpadDisplayController.showIdle()
            PinpadTraceLog.device("multi-interface search sdkResult=$result")
            return MultiInterfaceDetectionStartResult.ImmediateResponse(MultiInterfaceDetectionResult.enableMsrFail())
        }
        beepForCardPrompt("QK")
        PinpadDisplayController.showPresentCard(transactionDisplay)
        PinpadTraceLog.device("multi-interface search started transactionDisplay=${transactionDisplay != null}")
        return MultiInterfaceDetectionStartResult.Started
    }

    fun completeTransaction() {
        PinpadTraceLog.device("MSR transaction completed")
        clearTransactionState()
        stopMsrSearch()
        PinpadDisplayController.showThankYouAfterTransactionComplete()
    }

    fun ignoreCardSwipe() {
        PinpadTraceLog.device("MSR ignore card swipe")
        clearTransactionState()
        stopMsrSearch()
        cancelMultiInterfaceDetection()
        PinpadDisplayController.showProcessing()
    }

    fun cancelMultiInterfaceDetection(sendResult: Boolean = false): Boolean {
        if (!multiInterfaceDetectionRunning.getAndSet(false)) return false
        runCatching { cardReader.stopSearch() }
            .onFailure {
                Log.w(TAG, "Unable to stop multi-interface detection", it)
                PinpadTraceLog.device("multi-interface stopSearch failed=${it.message}")
            }
        PinpadTraceLog.device("multi-interface detection canceled")
        if (sendResult) {
            val completed = multiInterfaceCompleted
            val onResult = multiInterfaceOnResult
            if (completed != null && onResult != null) {
                completeMultiInterfaceDetection(completed, MultiInterfaceDetectionResult.canceled(), onResult)
            } else {
                clearMultiInterfacePendingCallback()
            }
        } else {
            clearMultiInterfacePendingCallback()
        }
        return true
    }

    fun cancelUserOperation(showCancelMessage: Boolean = true): Boolean {
        PinpadTraceLog.device("user cancel requested")
        val canceled = listOf(
            cancelMultiInterfaceDetection(sendResult = true),
            cancelMsrRead(sendResult = true),
            cancelPcdRead(sendResult = true),
            cancelPinEntry(sendResult = true),
            contactEmv.cancelTransaction(sendResult = true, showCancelMessage = false),
        ).any { it }
        if (!canceled) {
            PinpadTraceLog.device("user cancel ignored; no active operation")
            return false
        }
        if (showCancelMessage) {
            PinpadDisplayController.showOperationCancelledThenIdle()
        } else {
            PinpadDisplayController.showIdle()
        }
        return true
    }

    fun clearTransactionState() {
        cachedMsrTrackData = null
        cachedPcdTrackData = null
        PinpadTraceLog.device("transaction state cleared")
    }

    fun setMsrTrackMode(flag: Char): Boolean {
        if (flag !in setOf(MSR_TRACK2_ONLY, MSR_DISABLED, MSR_ALL_TRACKS)) return false
        msrTrackMode = flag
        prefs.setMsrTrackMode(flag.toString())
        PinpadTraceLog.device("MSR track mode flag=$flag")
        if (flag == MSR_DISABLED) {
            stopMsrSearch()
            PinpadDisplayController.showIdle()
        }
        return true
    }

    fun setMsrRetryCount(count: Int): Boolean {
        if (count !in 0..9) return false
        msrRetryCount = count
        prefs.setMsrRetryCount(count)
        PinpadTraceLog.device("MSR retry count=$count")
        return true
    }

    fun setMsrOutputFormat(flag: Char): Boolean {
        if (flag !in setOf(MSR_CLEAR_TEXT, MSR_CLEAR_TEXT_SENTINELS)) return false
        msrOutputFormat = flag
        prefs.setMsrOutputFormat(flag.toString())
        PinpadTraceLog.device("MSR output format=$flag")
        return true
    }

    fun msrOutputFormat(): Char = msrOutputFormat

    fun setPcdTrackMode(flag: Char): Boolean {
        if (flag !in setOf(MSR_TRACK2_ONLY, MSR_DISABLED, MSR_ALL_TRACKS)) return false
        pcdTrackMode = flag
        PinpadTraceLog.device("PCD track mode flag=$flag")
        if (flag == MSR_DISABLED) {
            cancelPcdRead(sendResult = false)
        }
        return true
    }

    fun setPcdOutputFormat(flag: Char): Boolean {
        if (flag !in setOf(MSR_CLEAR_TEXT, MSR_CLEAR_TEXT_SENTINELS)) return false
        pcdOutputFormat = flag
        PinpadTraceLog.device("PCD output format=$flag")
        return true
    }

    fun setPcdAutoArm(flag: Char): Char {
        return when (flag) {
            '0' -> {
                pcdAutoArmEnabled = false
                cancelPcdRead(sendResult = false)
                PinpadTraceLog.device("PCD auto arm disabled")
                '0'
            }
            '1' -> {
                pcdAutoArmEnabled = true
                PinpadTraceLog.device("PCD auto arm enabled")
                '0'
            }
            else -> '1'
        }
    }

    fun setMsrAutoArm(flag: Char, onRead: (MsrTrackData) -> Unit): Char {
        return when (flag) {
            '0' -> {
                msrAutoArmEnabled = false
                msrAutoArmCallback = null
                prefs.setMsrAutoArmEnabled(false)
                stopMsrSearch()
                PinpadDisplayController.showIdle()
                PinpadTraceLog.device("MSR auto arm disabled")
                '0'
            }
            '1' -> {
                msrAutoArmEnabled = true
                msrAutoArmCallback = onRead
                prefs.setMsrAutoArmEnabled(true)
                PinpadTraceLog.device("MSR auto arm enabled")
                startMsrSearch(autoArm = true, onRead = onRead)
                '0'
            }
            else -> '1'
        }
    }

    fun loadPusn(pusn: String, slot: Int): Char {
        if (slot !in PUSN_SLOT_RANGE) return '3'
        if (!pusn.matches(PUSN_REGEX)) return '1'
        if (pusnStore.read(slot).isNotBlank()) return '2'
        if (!pusnStore.write(slot, pusn)) return '3'
        return '0'
    }

    fun readPusn(slot: Int): String {
        if (slot !in PUSN_SLOT_RANGE) return "1"
        return pusnStore.read(slot)
    }

    fun initializeJpegTable(): Boolean = jpegStore.initialize()

    fun jpegTable(): List<PinpadJpegStore.JpegEntry> = jpegStore.table()

    fun selectJpegs(control: Char, names: List<String>): List<Char> = jpegStore.select(control, names)

    fun deleteJpegs(names: List<String>): List<Char> = jpegStore.delete(names)

    fun downloadJpegPacket(payload: String): Char = jpegStore.downloadPacket(payload)

    fun startJpegUpload(fileName: String): PinpadJpegStore.JpegUploadPacket = jpegStore.startUpload(fileName)

    fun nextJpegUploadPacket(): PinpadJpegStore.JpegUploadPacket = jpegStore.nextUploadPacket()

    fun playSelectedJpegs(): Boolean {
        val paths = jpegStore.selectedFilePaths()
        if (paths.isEmpty()) return false
        PinpadDisplayController.showJpegSequence(paths)
        return true
    }

    fun setIdleJpeg(name: String): Char = jpegStore.setIdleLogo(name)

    fun setIdleJpegEnabled(op: Char): Char {
        val result = jpegStore.setIdleLogoEnabled(op)
        if (result == '0' && op == '1') {
            jpegStore.enabledIdleLogoPath()?.let(PinpadDisplayController::showJpeg)
        } else if (result == '0' && op == '0') {
            PinpadDisplayController.showIdle()
        }
        return result
    }

    fun showJpeg(name: String): Char {
        val result = jpegStore.showFile(name)
        result.path?.let(PinpadDisplayController::showJpeg)
        return result.status
    }

    fun downloadBootLogoPacket(payload: String): Char = jpegStore.bootLogoPacket(payload)

    fun initializeMediaTable(): Boolean = mediaStore.initialize()

    fun mediaTable(): List<PinpadMediaStore.MediaEntry> = mediaStore.table()

    fun downloadMediaPacket(payload: String): Char = mediaStore.downloadPacket(payload)

    fun startMediaUpload(fileName: String): PinpadMediaStore.MediaUploadPacket = mediaStore.startUpload(fileName)

    fun nextMediaUploadPacket(): PinpadMediaStore.MediaUploadPacket = mediaStore.nextUploadPacket()

    fun deleteMedia(names: List<String>): List<Char> = mediaStore.delete(names)

    fun playMedia(name: String): Char {
        val result = mediaStore.playableFile(name)
        if (result.path != null && result.type != null) {
            PinpadDisplayController.showMedia(
                path = result.path,
                video = result.type == PinpadMediaStore.MediaType.Mp4,
            )
        }
        return result.status
    }

    fun setMediaVolume(payload: String): Char {
        if (payload.length != 2 || payload.any { !it.isDigit() }) return '1'
        val percentage = payload.toIntOrNull()?.takeIf { it in 0..99 } ?: return '1'
        return runCatching {
            val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val streamVolume = if (percentage == 0) {
                0
            } else {
                ((percentage * maximum) + 98) / 99
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamVolume, 0)
            PinpadTraceLog.device(
                "media volume percentage=$percentage streamVolume=$streamVolume maximum=$maximum",
            )
            '0'
        }.onFailure {
            Log.w(TAG, "Unable to set media volume", it)
        }.getOrDefault('2')
    }

    fun speakText(languageTag: String, text: String): Char {
        if (text.isBlank() || text.length > MAX_SPEECH_CHARACTERS) return '1'
        if (textToSpeechFailed.get()) initializeTextToSpeech()
        if (!textToSpeechReady.get()) return '2'
        val engine = textToSpeech ?: return '2'
        val locales = speechLocales(languageTag) ?: return '1'
        val locale = locales.firstOrNull { candidate ->
            engine.isLanguageAvailable(candidate) >= TextToSpeech.LANG_AVAILABLE
        } ?: run {
            requestSpeechVoiceProvisioning(engine, locales.first())
            return '2'
        }

        return runCatching {
            val selectedVoice = if (
                selectedTextToSpeechEngine == RHVOICE_ENGINE_PACKAGE &&
                isSpanishSpeechLanguage(languageTag)
            ) {
                val mateo = runCatching { engine.voices }
                    .getOrNull()
                    .orEmpty()
                    .firstOrNull { it.name.equals(RHVOICE_SPANISH_VOICE, ignoreCase = true) }
                    ?: run {
                        requestSpeechVoiceProvisioning(engine, locales.first())
                        return '2'
                    }
                if (engine.setVoice(mateo) != TextToSpeech.SUCCESS) return '2'
                mateo.name
            } else {
                if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return '2'
                engine.voice?.name
            }
            val utteranceId = "M17-${System.currentTimeMillis()}"
            val speechParameters = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, TTS_MAX_OUTPUT_VOLUME)
            }
            val result = engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                speechParameters,
                utteranceId,
            )
            PinpadTraceLog.device(
                "text-to-speech language=${locale.toLanguageTag()} voice=${selectedVoice ?: "default"} " +
                    "chars=${text.length} result=$result",
            )
            if (result == TextToSpeech.SUCCESS) '0' else '3'
        }.onFailure {
            Log.w(TAG, "Unable to speak text", it)
        }.getOrDefault('3')
    }

    private fun initializeTextToSpeech() {
        if (!textToSpeechInitializing.compareAndSet(false, true)) return
        textToSpeechHandler.removeCallbacks(speechVoiceReadinessCheck)
        textToSpeech?.shutdown()
        textToSpeech = null
        textToSpeechReady.set(false)
        textToSpeechFailed.set(false)

        val installedEngines = installedTextToSpeechEngines()
        val missingRequirement = firstMissingTextToSpeechRequirement()
        if (missingRequirement != null) requestTextToSpeechApplication(missingRequirement)
        val rhVoiceInstalled = RHVOICE_ENGINE_PACKAGE in installedEngines
        val rhVoiceProvisioned = rhVoiceInstalled && missingRequirement == null
        if (installedEngines.isEmpty()) {
            textToSpeechInitializing.set(false)
            textToSpeechFailed.set(true)
            return
        }
        val requestedEngine = when {
            rhVoiceProvisioned -> RHVOICE_ENGINE_PACKAGE
            ESPEAK_ENGINE_PACKAGE in installedEngines -> ESPEAK_ENGINE_PACKAGE
            !rhVoiceInstalled -> installedEngines.first()
            else -> {
                textToSpeechInitializing.set(false)
                textToSpeechFailed.set(true)
                return
            }
        }
        selectedTextToSpeechEngine = null
        val listener = TextToSpeech.OnInitListener { status ->
            textToSpeechInitializing.set(false)
            val ready = status == TextToSpeech.SUCCESS
            textToSpeechReady.set(ready)
            textToSpeechFailed.set(!ready)
            if (ready) selectedTextToSpeechEngine = requestedEngine
            val audioAttributesStatus = if (ready) {
                textToSpeech?.setAudioAttributes(TTS_AUDIO_ATTRIBUTES) ?: TextToSpeech.ERROR
            } else {
                TextToSpeech.ERROR
            }
            val speechRateStatus = if (ready) {
                textToSpeech?.setSpeechRate(TTS_SPEECH_RATE) ?: TextToSpeech.ERROR
            } else {
                TextToSpeech.ERROR
            }
            PinpadTraceLog.device(
                "text-to-speech initialized ready=$ready status=$status " +
                    "engine=${selectedTextToSpeechEngine ?: "system-default"} " +
                    "requiredEngineInstalled=$rhVoiceInstalled bundleProvisioned=$rhVoiceProvisioned " +
                    "mediaAudioAttributesStatus=$audioAttributesStatus speechRate=$TTS_SPEECH_RATE " +
                    "speechRateStatus=$speechRateStatus",
            )
            if (ready) {
                textToSpeech?.let(::requestMissingSpeechVoices)
            }
        }
        textToSpeech = runCatching {
            TextToSpeech(applicationContext, listener, requestedEngine)
        }.onFailure {
            textToSpeechInitializing.set(false)
            textToSpeechFailed.set(true)
            Log.w(TAG, "Unable to initialize text-to-speech", it)
        }.getOrNull()
    }

    private fun installedTextToSpeechEngines(): Set<String> {
        return applicationContext.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0,
        )
            .asSequence()
            .mapNotNull { it.serviceInfo?.packageName }
            .toSet()
    }

    private fun firstMissingTextToSpeechRequirement(): Pair<String, String>? =
        REQUIRED_TEXT_TO_SPEECH_APPLICATIONS.firstOrNull { (_, packageName) ->
            !isPackageInstalled(packageName)
        }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching { applicationContext.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun requestTextToSpeechApplication(requirement: Pair<String, String>) {
        val (capability, packageName) = requirement
        if (!applicationRequirementsRequested.add(capability)) return
        PinpadTraceLog.device(
            "required text-to-speech package is missing capability=$capability package=$packageName",
        )
        ApplicationRequirementClient.request(applicationContext, capability) { accepted, error ->
            PinpadTraceLog.device(
                "text-to-speech application requirement capability=$capability accepted=$accepted " +
                    "error=${error ?: "none"}",
            )
            textToSpeechHandler.removeCallbacks(textToSpeechRequirementRetry)
            if (!accepted) applicationRequirementsRequested.remove(capability)
            textToSpeechHandler.postDelayed(
                textToSpeechRequirementRetry,
                if (accepted) TTS_APPLICATION_INSTALL_RECHECK_MS else TTS_APPLICATION_RETRY_MS,
            )
        }
    }

    private fun registerApplicationRequirementReceiver() {
        val filter = IntentFilter(ApplicationRequirementClient.ACTION_APPLICATION_REQUIREMENT_READY)
        ContextCompat.registerReceiver(
            applicationContext,
            applicationRequirementReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun requestMissingSpeechVoices(engine: TextToSpeech) {
        if (selectedTextToSpeechEngine != RHVOICE_ENGINE_PACKAGE) return
        val installedVoices = runCatching { engine.voices }.getOrNull().orEmpty()
        val mateoReady = installedVoices.any {
            it.name.equals(RHVOICE_SPANISH_VOICE, ignoreCase = true)
        }
        val englishReady = installedVoices.any {
            it.locale.language.equals(Locale.ENGLISH.language, ignoreCase = true) &&
                !it.isNetworkConnectionRequired
        }
        val missingLocales = buildList {
            if (!mateoReady) add(RHVOICE_SPANISH_PROVISIONING_LOCALE)
            if (!englishReady) add(RHVOICE_ENGLISH_PROVISIONING_LOCALE)
        }
        PinpadTraceLog.device(
            "text-to-speech offline voices mateoReady=$mateoReady englishReady=$englishReady " +
                "installed=${installedVoices.map { it.name }.sorted()}",
        )
        if (missingLocales.isEmpty()) return

        missingLocales.forEach { requestSpeechVoiceProvisioning(engine, it) }
        textToSpeechHandler.removeCallbacks(speechVoiceReadinessCheck)
        textToSpeechHandler.postDelayed(
            speechVoiceReadinessCheck,
            TTS_VOICE_READINESS_RECHECK_MS,
        )
    }

    private fun requestSpeechVoiceProvisioning(engine: TextToSpeech, locale: Locale) {
        if (selectedTextToSpeechEngine != RHVOICE_ENGINE_PACKAGE) return
        val language = runCatching { locale.isO3Language }.getOrDefault(locale.language)
        val country = runCatching { locale.isO3Country }.getOrDefault(locale.country)
        val requestKey = "$language-$country"
        if (!speechProvisioningRequested.add(requestKey)) return

        val params = Bundle().apply {
            putString(TTS_PARAM_LANGUAGE, language)
            putString(TTS_PARAM_COUNTRY, country)
            putString(TTS_PARAM_VARIANT, locale.variant)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f)
        }
        val result = engine.speak(
            VOICE_PROVISIONING_TEXT,
            TextToSpeech.QUEUE_ADD,
            params,
            "TTS-PROVISION-$requestKey",
        )
        if (result != TextToSpeech.SUCCESS) {
            speechProvisioningRequested.remove(requestKey)
        } else {
            textToSpeechHandler.postDelayed(
                { speechProvisioningRequested.remove(requestKey) },
                TTS_VOICE_PROVISIONING_RETRY_MS,
            )
        }
        PinpadTraceLog.device(
            "text-to-speech voice provisioning language=${locale.toLanguageTag()} result=$result",
        )
    }

    private fun speechLocales(languageTag: String): List<Locale>? {
        return when (languageTag.trim().lowercase(Locale.US)) {
            "es", "es-mx", "es-419", "es-la" -> listOf(
                Locale.forLanguageTag("es-419"),
                Locale.forLanguageTag("es-MX"),
                Locale.forLanguageTag("es-US"),
                Locale.forLanguageTag("es"),
            )
            "en", "en-us" -> listOf(Locale.US, Locale.ENGLISH)
            else -> null
        }
    }

    private fun isSpanishSpeechLanguage(languageTag: String): Boolean =
        languageTag.trim().lowercase(Locale.US) in SPANISH_SPEECH_LANGUAGE_TAGS

    fun loadEmvTerminalConfiguration(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.loadTerminalConfiguration(payload)
    }

    fun loadEmvCapk(payload: String): PinpadContactEmvController.EmvCapkResult {
        return contactEmv.loadCapk(payload)
    }

    fun loadPcdCapk(payload: String): PinpadContactEmvController.EmvCapkResult {
        return contactEmv.loadCapk(payload, replaceExisting = false)
    }

    fun loadEmvApplicationConfiguration(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.loadApplicationConfiguration(payload)
    }

    fun loadPcdApplicationConfiguration(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.loadPcdApplicationConfiguration(payload)
    }

    fun loadEmvDataFormatTable(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.loadDataFormatTable(payload)
    }

    fun queryEmvConfigIds(configType: Char): PinpadContactEmvController.EmvConfigQuery {
        return contactEmv.queryConfigIds(configType)
    }

    fun queryPcdConfigIds(configType: Char): PinpadContactEmvController.EmvConfigQuery {
        return contactEmv.queryPcdConfigIds(configType)
    }

    fun queryCurrentEmvConfiguration(): PinpadEmvConfigStore.EmvConfigurationSnapshot {
        return contactEmv.queryCurrentConfiguration()
    }

    fun clearAllEmvConfiguration(): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.clearAllConfiguration()
    }

    fun deleteEmvConfig(payload: String): PinpadContactEmvController.EmvConfigDelete {
        return contactEmv.deleteConfig(payload)
    }

    fun deletePcdConfig(payload: String): PinpadContactEmvController.EmvConfigDelete {
        return contactEmv.deletePcdConfig(payload)
    }

    fun housekeepPcdConfig(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.housekeepPcdConfig(payload)
    }

    fun loadPcdDrlConfiguration(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.loadPcdDrlConfiguration(payload)
    }

    fun applyA10DemoEmvConfiguration(
        type: Char,
        fileName: String,
        contents: String,
    ): PinpadContactEmvController.EmvCommandResult {
        return runCatching {
            val store = PinpadEmvConfigStore(applicationContext)
            when (type.uppercaseChar()) {
                'D' -> {
                    val definitions = PinpadEmvDataObjects.parseDataFormatText(contents)
                        ?: return PinpadContactEmvController.EmvCommandResult.failure('2')
                    store.setDataFormatDefinitions(definitions)
                }
                'T' -> {
                    val terminal = PinpadEmvDataObjects.parseConfigurationText(contents)
                        ?: return PinpadContactEmvController.EmvCommandResult.failure('2')
                    store.setTerminalConfigTlv(terminal)
                }
                'K', 'R' -> {
                    val capk = PinpadEmvDataObjects.parseCapkText(contents)
                        ?: return PinpadContactEmvController.EmvCommandResult.failure('2')
                    store.setCapkTlv(capk.id, capk.tlvHex)
                }
                'A', 'L' -> {
                    val application = PinpadEmvDataObjects.parseApplicationConfigurationText(contents)
                        ?: return PinpadContactEmvController.EmvCommandResult.failure('2')
                    if (type.uppercaseChar() == 'A') {
                        store.setAidTlv(application.aid, application.tlvHex)
                    } else {
                        store.setPcdAidTlv(application.aid, application.tlvHex)
                    }
                }
                else -> return PinpadContactEmvController.EmvCommandResult.failure('2')
            }
            val applied = contactEmv.applyStoredConfiguration()
            PinpadTraceLog.device("A10 demo EMV file applied type=$type file=$fileName sdk=$applied")
            if (applied) {
                PinpadContactEmvController.EmvCommandResult.ok()
            } else {
                PinpadContactEmvController.EmvCommandResult.failure('5')
            }
        }.getOrElse { error ->
            PinpadTraceLog.device(
                "A10 demo EMV file failed type=$type file=$fileName error=${error.message}",
            )
            PinpadContactEmvController.EmvCommandResult.failure('6')
        }
    }

    fun deletePcdDrlConfiguration(): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.deletePcdDrlConfiguration()
    }

    fun startContactApplicationSelect(
        transactionDisplay: PinpadTransactionDisplay? = null,
        onResult: (String) -> Unit,
    ): Boolean {
        return contactEmv.startApplicationSelect(transactionDisplay, onResult)
    }

    fun startContactCardDataRead(onApplicationSelect: (String) -> Unit): Boolean {
        return contactEmv.startCardDataRead(onApplicationSelect)
    }

    fun cancelContactTransaction() {
        contactEmv.cancelTransaction()
    }

    fun startContactTransaction(
        payload: String,
        sourceCommand: String,
        transactionDisplay: PinpadTransactionDisplay? = null,
        onResult: (PinpadContactEmvController.EmvCommandResult) -> Unit,
    ): Boolean {
        return contactEmv.startTransaction(
            payload = payload,
            sourceCommand = sourceCommand,
            transactionDisplay = transactionDisplay,
            onResult = onResult,
        )
    }

    /** Starts a documented T37 contact ICC PIN-management operation. */
    internal fun startContactPinManagement(
        operation: PinManagementPolicy.Operation,
        onResult: (PinpadContactEmvController.EmvCommandResult) -> Unit,
    ): Boolean {
        return contactEmv.startPinManagement(operation, onResult)
    }

    fun startContactlessTransaction(
        payload: String,
        sourceCommand: String,
        transactionDisplay: PinpadTransactionDisplay? = null,
        onResult: (PinpadContactEmvController.EmvCommandResult) -> Unit,
    ): Boolean {
        return contactEmv.startTransaction(
            payload = payload,
            sourceCommand = sourceCommand,
            cardSlot = CardSlotTypeEnum.RF,
            entryMode = EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACTLESS,
            transactionDisplay = transactionDisplay,
            onResult = onResult,
        )
    }

    private fun startPcdEmvTrackRead(
        onRead: (MsrTrackData) -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        return contactEmv.startTransaction(
            payload = "",
            sourceCommand = "Q8",
            cardSlot = CardSlotTypeEnum.RF,
            entryMode = EmvEntryModeEnum.EMV_ENTRY_MODE_CONTACTLESS,
            onResult = {
                val data = trackDataFromEmvTlv(contactEmv.onlineAuthorizationData())
                PinpadTraceLog.device(
                    "PCD EMV track-equivalent result success=${it.success} " +
                        "t1=${data.track1.length} t2=${data.track2.length} t3=${data.track3.length}",
                )
                contactEmv.cancelTransaction(sendResult = false)
                if (data.hasAnyTrack()) {
                    PinpadDisplayController.showProcessing()
                    onRead(data)
                } else {
                    onCancel()
                }
            },
        )
    }

    fun completeContactOnlineAuthorization(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.completeOnlineAuthorization(payload)
    }

    fun addIssuerScript(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.addIssuerScript(payload)
    }

    fun clearEmvTransactionLog(): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.clearTransactionLog()
    }

    fun nextEmvBatchData(): String {
        return contactEmv.nextBatchData()
    }

    fun onlineAuthorizationData(): String {
        return contactEmv.onlineAuthorizationData()
    }

    fun reversalData(): String {
        return contactEmv.reversalData()
    }

    fun forceCompleteNonEmvTransaction(): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.forceCompleteNonEmvTransaction()
    }

    fun overwriteEmvTransactionData(payload: String): PinpadContactEmvController.EmvCommandResult {
        return contactEmv.overwriteTransactionData(payload)
    }

    fun queryEmvTransactionData(payload: String): String {
        return contactEmv.queryTransactionData(payload)
    }

    private fun startMsrSearch(
        autoArm: Boolean,
        transactionDisplay: PinpadTransactionDisplay? = null,
        onRead: (MsrTrackData) -> Unit,
        onCancel: () -> Unit = {},
    ): Boolean {
        if (msrTrackMode == MSR_DISABLED) {
            PinpadTraceLog.device("MSR search skipped because reader is disabled")
            return false
        }
        if (!msrSearchRunning.compareAndSet(false, true)) {
            PinpadTraceLog.device("MSR search already running")
            return true
        }
        if (!autoArm) {
            msrReadCancelCallback = onCancel
        }
        val listener = object : OnCardInfoListener {
            override fun onCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
                msrSearchRunning.set(false)
                if (!autoArm) {
                    clearMsrReadCancelCallback()
                }
                PinpadTraceLog.device(
                    "MSR onCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot} " +
                        "tk1=${cardInfo?.tk1?.length ?: 0} tk2=${cardInfo?.tk2?.length ?: 0} tk3=${cardInfo?.tk3?.length ?: 0}",
                )
                if (retCode == SdkResult.Success && cardInfo?.cardExistslot == CardSlotTypeEnum.SWIPE) {
                    PinpadDisplayController.showProcessing()
                    onRead(
                        MsrTrackData(
                            track1 = cardInfo.tk1.orEmpty(),
                            track2 = cardInfo.tk2.orEmpty(),
                            track3 = cardInfo.tk3.orEmpty(),
                            trackMode = msrTrackMode,
                            outputFormat = msrOutputFormat,
                        ),
                    )
                }
                if (autoArm && msrAutoArmEnabled) {
                    msrAutoArmCallback?.let { startMsrSearch(autoArm = true, onRead = it) }
                }
            }

            override fun onSwipeIncorrect() {
                PinpadTraceLog.device("MSR onSwipeIncorrect retryCount=$msrRetryCount")
                PinpadDisplayController.showBadReadThenSwipe()
            }

            override fun onMultipleCards() {
                PinpadTraceLog.device("MSR onMultipleCards")
            }
        }
        val result = runCatching {
            cardReader.searchCard(hashSetOf(CardSlotTypeEnum.SWIPE), MSR_SEARCH_TIMEOUT_SECONDS, listener)
        }.onFailure {
            msrSearchRunning.set(false)
            if (!autoArm) {
                clearMsrReadCancelCallback()
            }
            Log.w(TAG, "Unable to start MSR search", it)
            PinpadTraceLog.device("MSR search failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        if (result != SdkResult.Success) {
            msrSearchRunning.set(false)
            if (!autoArm) {
                clearMsrReadCancelCallback()
            }
            PinpadTraceLog.device("MSR search start sdkResult=$result")
            return false
        }
        PinpadDisplayController.showSwipeCard(transactionDisplay)
        PinpadTraceLog.device("MSR search started autoArm=$autoArm trackMode=$msrTrackMode format=$msrOutputFormat")
        return true
    }

    private fun startPcdSearch(
        onRead: (MsrTrackData) -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        if (!msrSearchRunning.compareAndSet(false, true)) {
            PinpadTraceLog.device("PCD search already running")
            return true
        }
        pcdReadCancelCallback = onCancel
        val listener = object : OnCardInfoListener {
            override fun onCardInfo(retCode: Int, cardInfo: CardInfoEntity?) {
                msrSearchRunning.set(false)
                clearPcdReadCancelCallback()
                PinpadTraceLog.device(
                    "PCD onCardInfo retCode=$retCode slot=${cardInfo?.cardExistslot} " +
                        "tk1=${cardInfo?.tk1?.length ?: 0} tk2=${cardInfo?.tk2?.length ?: 0} tk3=${cardInfo?.tk3?.length ?: 0}",
                )
                if (retCode == SdkResult.Success && cardInfo?.cardExistslot == CardSlotTypeEnum.RF) {
                    PinpadDisplayController.showProcessing()
                    onRead(pcdTrackDataFromCardInfo(cardInfo))
                }
            }

            override fun onSwipeIncorrect() {
                PinpadTraceLog.device("PCD onSwipeIncorrect")
                PinpadDisplayController.showTapCard()
            }

            override fun onMultipleCards() {
                PinpadTraceLog.device("PCD onMultipleCards")
            }
        }
        val result = runCatching {
            cardReader.searchCard(hashSetOf(CardSlotTypeEnum.RF), MSR_SEARCH_TIMEOUT_SECONDS, listener)
        }.onFailure {
            msrSearchRunning.set(false)
            clearPcdReadCancelCallback()
            Log.w(TAG, "Unable to start PCD search", it)
            PinpadTraceLog.device("PCD search failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)
        if (result != SdkResult.Success) {
            msrSearchRunning.set(false)
            clearPcdReadCancelCallback()
            PinpadTraceLog.device("PCD search start sdkResult=$result")
            return false
        }
        PinpadDisplayController.showTapCard()
        PinpadTraceLog.device("PCD search started trackMode=$msrTrackMode format=$msrOutputFormat")
        return true
    }

    private fun completeMultiInterfaceDetection(
        completed: AtomicBoolean,
        result: MultiInterfaceDetectionResult,
        onResult: (MultiInterfaceDetectionResult) -> Unit,
    ) {
        if (!completed.compareAndSet(false, true)) return
        multiInterfaceDetectionRunning.set(false)
        clearMultiInterfacePendingCallback()
        PinpadDisplayController.showProcessing()
        PinpadTraceLog.device("multi-interface result payload=${result.payload}")
        onResult(result)
    }

    private fun clearMultiInterfacePendingCallback() {
        multiInterfaceCompleted = null
        multiInterfaceOnResult = null
    }

    private fun cacheMsrTrackData(cardInfo: CardInfoEntity) {
        val cachedTrackMode = if (msrTrackMode == MSR_TRACK2_ONLY) MSR_TRACK2_ONLY else MSR_ALL_TRACKS
        cachedMsrTrackData = trackDataFromCardInfo(cardInfo, cachedTrackMode)
        PinpadTraceLog.device(
            "MSR cached from QK trackMode=$cachedTrackMode configuredTrackMode=$msrTrackMode format=$msrOutputFormat " +
                "t1=${cardInfo.tk1?.length ?: 0} t2=${cardInfo.tk2?.length ?: 0} t3=${cardInfo.tk3?.length ?: 0}",
        )
    }

    private fun cachePcdTrackData(cardInfo: CardInfoEntity) {
        val cachedTrackMode = if (msrTrackMode == MSR_TRACK2_ONLY) MSR_TRACK2_ONLY else MSR_ALL_TRACKS
        cachedPcdTrackData = pcdTrackDataFromCardInfo(cardInfo).copy(trackMode = cachedTrackMode).takeIf { it.hasAnyTrack() }
        PinpadTraceLog.device(
            "PCD cached from QK trackMode=$cachedTrackMode configuredTrackMode=$pcdTrackMode format=$pcdOutputFormat " +
                "t1=${cardInfo.tk1?.length ?: 0} t2=${cardInfo.tk2?.length ?: 0} t3=${cardInfo.tk3?.length ?: 0}",
        )
    }

    private fun consumeCachedMsrTrackData(): MsrTrackData? {
        val cached = cachedMsrTrackData
        cachedMsrTrackData = null
        return cached
    }

    private fun consumeCachedPcdTrackData(): MsrTrackData? {
        val cached = cachedPcdTrackData
        cachedPcdTrackData = null
        return cached
    }

    private fun trackDataFromCardInfo(
        cardInfo: CardInfoEntity,
        trackMode: Char = msrTrackMode,
    ): MsrTrackData {
        return MsrTrackData(
            track1 = cardInfo.tk1.orEmpty(),
            track2 = cardInfo.tk2.orEmpty(),
            track3 = cardInfo.tk3.orEmpty(),
            trackMode = trackMode,
            outputFormat = msrOutputFormat,
        )
    }

    private fun pcdTrackDataFromCardInfo(cardInfo: CardInfoEntity): MsrTrackData {
        return MsrTrackData(
            track1 = cardInfo.tk1.orEmpty(),
            track2 = cardInfo.tk2.orEmpty(),
            track3 = cardInfo.tk3.orEmpty(),
            trackMode = if (pcdTrackMode == MSR_TRACK2_ONLY) MSR_TRACK2_ONLY else MSR_ALL_TRACKS,
            outputFormat = pcdOutputFormat,
        )
    }

    private fun trackDataFromEmvTlv(tlvHex: String): MsrTrackData {
        val records = PinpadEmvDataObjects.parseTlvRecords(tlvHex)
            .associateBy { it.tag }
        val track1 = records["56"]?.value?.toString(StandardCharsets.US_ASCII).orEmpty()
        val track2 = records["57"]?.value?.let { PinpadEmvDataObjects.run { it.toHex() } }?.trimEnd('F').orEmpty()
        return MsrTrackData(
            track1 = track1,
            track2 = track2,
            track3 = "",
            trackMode = if (pcdTrackMode == MSR_TRACK2_ONLY) MSR_TRACK2_ONLY else MSR_ALL_TRACKS,
            outputFormat = pcdOutputFormat,
        )
    }

    private fun MsrTrackData.hasAnyTrack(): Boolean {
        return track1.isNotBlank() || track2.isNotBlank() || track3.isNotBlank()
    }

    private fun stopMsrSearch() {
        runCatching { cardReader.stopSearch() }
            .onFailure {
                Log.w(TAG, "Unable to stop MSR search", it)
                PinpadTraceLog.device("MSR stopSearch failed=${it.message}")
            }
        msrSearchRunning.set(false)
        clearMsrReadCancelCallback()
        clearPcdReadCancelCallback()
    }

    private fun cancelMsrRead(sendResult: Boolean): Boolean {
        if (msrReadCancelCallback == null) return false
        if (!msrSearchRunning.getAndSet(false)) {
            clearMsrReadCancelCallback()
            return false
        }
        val onCancel = msrReadCancelCallback
        clearMsrReadCancelCallback()
        runCatching { cardReader.stopSearch() }
            .onFailure {
                Log.w(TAG, "Unable to cancel MSR search", it)
                PinpadTraceLog.device("MSR cancel stopSearch failed=${it.message}")
            }
        PinpadTraceLog.device("MSR read canceled sendResult=$sendResult")
        if (sendResult) {
            onCancel?.invoke()
        }
        return true
    }

    private fun cancelPcdRead(sendResult: Boolean): Boolean {
        if (pcdReadCancelCallback == null) return false
        if (!msrSearchRunning.getAndSet(false)) {
            clearPcdReadCancelCallback()
            return false
        }
        val onCancel = pcdReadCancelCallback
        clearPcdReadCancelCallback()
        runCatching { cardReader.stopSearch() }
            .onFailure {
                Log.w(TAG, "Unable to cancel PCD search", it)
                PinpadTraceLog.device("PCD cancel stopSearch failed=${it.message}")
            }
        PinpadTraceLog.device("PCD read canceled sendResult=$sendResult")
        if (sendResult) {
            onCancel?.invoke()
        }
        return true
    }

    private fun clearMsrReadCancelCallback() {
        msrReadCancelCallback = null
    }

    private fun clearPcdReadCancelCallback() {
        pcdReadCancelCallback = null
    }

    private fun activeSamCpuCard(): CPUCardHandler {
        return deviceEngine.getCPUCardHandler(activeSamSlot)
    }

    private fun injectClearKey(slot: Int, hexKey: String, attribute: MasterKeyAttribute): KeyLoadResult {
        val keyBytes = hexKey.hexToBytesOrNull()
        if (keyBytes == null) {
            PinpadTraceLog.device("writeMKey slot=$slot invalid clear key hexChars=${hexKey.length}")
            return KeyLoadResult.Error('A')
        }
        val workKeyType = attribute.workKeyType()
        return if (workKeyType == null) {
            injectClearMasterKey(slot, keyBytes, attribute)
        } else {
            injectClearWorkKey(slot, keyBytes, attribute, workKeyType)
        }
    }

    private fun injectClearMasterKey(slot: Int, keyBytes: ByteArray, attribute: MasterKeyAttribute): KeyLoadResult {
        PinpadTraceLog.device("writeMKey slot=$slot algorithm=DES keyBytes=${keyBytes.size}")
        return runCatching {
            pinPad.setAlgorithmMode(AlgorithmModeEnum.DES)
            pinPad.writeMKey(slot, keyBytes, keyBytes.size)
        }.fold(
            onSuccess = { result ->
                PinpadTraceLog.device("writeMKey slot=$slot usage=${attribute.usage} sdkResult=$result")
                if (result == SdkResult.Success) KeyLoadResult.Success else KeyLoadResult.Error('Z')
            },
            onFailure = {
                Log.w(TAG, "Clear master key injection failed", it)
                PinpadTraceLog.device("writeMKey slot=$slot failed=${it.message}")
                KeyLoadResult.Error('Z')
            },
        )
    }

    private fun injectClearWorkKey(
        slot: Int,
        keyBytes: ByteArray,
        attribute: MasterKeyAttribute,
        workKeyType: WorkKeyTypeEnum,
    ): KeyLoadResult {
        val randomMasterKey = ByteArray(keyBytes.size)
        secureRandom.nextBytes(randomMasterKey)
        PinpadTraceLog.device(
            "writeMKey random wrapper slot=$slot usage=${attribute.usage} workKeyType=$workKeyType keyBytes=${keyBytes.size}",
        )
        return runCatching {
            pinPad.setAlgorithmMode(AlgorithmModeEnum.DES)
            val masterResult = pinPad.writeMKey(slot, randomMasterKey, randomMasterKey.size)
            if (masterResult != SdkResult.Success) return@runCatching masterResult
            val encryptedWorkKey = pinPad.encryptByMKey(slot, keyBytes, keyBytes.size)
                ?: return@runCatching SdkResult.Fail
            pinPad.writeWKey(slot, workKeyType, encryptedWorkKey, encryptedWorkKey.size)
        }.fold(
            onSuccess = { result ->
                PinpadTraceLog.device(
                    "writeWKey clear usage=${attribute.usage} slot=$slot type=$workKeyType sdkResult=$result",
                )
                if (result == SdkResult.Success) KeyLoadResult.Success else KeyLoadResult.Error('Z')
            },
            onFailure = {
                Log.w(TAG, "Clear work key injection failed", it)
                PinpadTraceLog.device("writeWKey clear slot=$slot type=$workKeyType failed=${it.message}")
                KeyLoadResult.Error('Z')
            },
        )
    }

    private fun parseMasterKeyAttribute(keyId: Char, keyPayloadAscii: String): MasterKeyAttribute {
        val fields = keyPayloadAscii.split(FS_CHAR)
        val attribute = fields.getOrNull(1).orEmpty()
        if (attribute.length >= 3) {
            val usage = attribute.take(2).uppercase()
            val mode = attribute.getOrNull(2)?.uppercaseChar() ?: defaultMasterKeyAttribute(keyId).mode
            val algorithm = attribute.getOrNull(3)?.uppercaseChar() ?: DEFAULT_MASTER_KEY_ALGORITHM
            if (usage.matches(KEY_USAGE_REGEX) && mode in KEY_MODE_VALUES && algorithm in KEY_ALGORITHM_VALUES) {
                return MasterKeyAttribute(usage, mode, algorithm)
            }
        }
        return defaultMasterKeyAttribute(keyId)
    }

    private fun defaultMasterKeyAttribute(keyId: Char): MasterKeyAttribute {
        val usage = when (keyId.uppercaseChar()) {
            in '0'..'9' -> "P0"
            in 'B'..'E' -> "M3"
            'F' -> "K1"
            'G' -> "D0"
            else -> "K0"
        }
        val mode = when (usage) {
            "P0", "D0" -> 'E'
            "M3" -> 'G'
            else -> 'D'
        }
        return MasterKeyAttribute(usage, mode, DEFAULT_MASTER_KEY_ALGORITHM)
    }

    private fun masterKeyAttribute(keyId: Char): MasterKeyAttribute {
        return storedMasterKeyAttribute(keyId) ?: defaultMasterKeyAttribute(keyId)
    }

    private fun storedMasterKeyAttribute(keyId: Char): MasterKeyAttribute? {
        return prefs.masterKeyAttribute(keyId)
            ?.let(MasterKeyAttribute::fromStorageValue)
    }

    private fun startPinEntry(
        request: PinEntryRequest,
        onResult: (PinEntryResult) -> Unit,
    ): PinEntryStartResult {
        validateAccount(request.account)?.let { return PinEntryStartResult.ImmediateResponse(it.toString()) }
        val keyIndex = when (request.scheme) {
            PinKeyScheme.DUKPT -> activeDukptKeySet.takeIf { dukptKeyExists(it) }
                ?: return PinEntryStartResult.ImmediateResponse("A")
            PinKeyScheme.SECRET_MASTER_SESSION -> request.secretKeySlot?.takeIf { secretPinKeyExists(it) }
                ?.toSecretSdkSlot()
                ?: return PinEntryStartResult.ImmediateResponse("9")
            PinKeyScheme.MASTER_SESSION -> when (val result = prepareMasterSessionPinKey(request.sessionKey)) {
                is SessionPinKeyResult.Ready -> result.slot
                is SessionPinKeyResult.Error -> return PinEntryStartResult.ImmediateResponse(result.code.toString())
            }
        }
        val pinLengths = request.pinLengths()
        val enteredDigits = AtomicInteger(0)
        val completed = AtomicBoolean(false)
        pinEntryCompleted = completed
        pinEntryOnResult = onResult
        val listener = object : OnPinPadInputListener {
            override fun onInputResult(retCode: Int, data: ByteArray?) {
                PinpadTraceLog.device("PIN input result scheme=${request.scheme} retCode=$retCode dataBytes=${data?.size ?: 0}")
                val result = when (retCode) {
                    SdkResult.Success -> buildSuccessfulPinResult(request, keyIndex, data, enteredDigits.get())
                    SdkResult.PinPad_No_Pin_Input -> buildNoPinResult(request)
                    SdkResult.PinPad_Input_Cancel,
                    SdkResult.PinPad_Input_Timeout -> PinEntryResult.Eot
                    else -> PinEntryResult.Response("B")
                }
                completePinEntry(completed, result, onResult)
            }

            override fun onSendKey(keyCode: Byte) {
                when (keyCode) {
                    PinPadKeyCode.KEYCODE_CLEAR,
                    PinPadKeyCode.KEYCODE_BACKSPACE -> enteredDigits.updateAndGet { (it - 1).coerceAtLeast(0) }
                    PinPadKeyCode.KEYCODE_CANCEL -> {
                        enteredDigits.set(0)
                        PinpadDisplayController.showOperationCancelledThenIdle()
                        cancelPinEntry(sendResult = true)
                    }
                    PinPadKeyCode.KEYCODE_CONFIRM -> Unit
                    else -> if (enteredDigits.get() < request.maxPin) enteredDigits.incrementAndGet()
                }
                PinpadDisplayController.updatePinDigits(enteredDigits.get())
                PinpadTraceLog.device("PIN key event keyCode=$keyCode digits=${enteredDigits.get()}")
            }
        }

        val numericAccount = request.account.filter(Char::isDigit)
        val startResult = runCatching {
            pinPad.setPinKeyboardMode(PinKeyboardModeEnum.FIXED)
            when (request.scheme) {
                PinKeyScheme.DUKPT -> {
                    pinPad.setAlgorithmMode(AlgorithmModeEnum.DUKPT)
                    pinPad.setDukptAlgorithmMode(DukptAlgorithmModeEnum.DES)
                }
                PinKeyScheme.MASTER_SESSION,
                PinKeyScheme.SECRET_MASTER_SESSION -> pinPad.setAlgorithmMode(AlgorithmModeEnum.DES)
            }
            runCatching { deviceEngine.beeper.beep(PIN_PROMPT_BEEP_MS) }
                .onSuccess { PinpadTraceLog.device("PIN prompt beep=${PIN_PROMPT_BEEP_MS}ms") }
                .onFailure { PinpadTraceLog.device("PIN prompt beep failed=${it.message}") }
            PinpadDisplayController.showEnterPin(request.promptLines)
            PinpadDisplayController.updatePinDigits(0)
            pinPad.inputOnlinePin(
                pinLengths,
                SDK_PIN_TIMEOUT_SECONDS,
                numericAccount.toByteArray(Charsets.US_ASCII),
                keyIndex,
                PinAlgorithmModeEnum.ISO9564FMT0,
                listener,
            )
        }.onFailure {
            Log.w(TAG, "Unable to start PIN entry", it)
            PinpadTraceLog.device("PIN input start failed=${it.message}")
        }.getOrDefault(SdkResult.Fail)

        PinpadTraceLog.device(
            "PIN input start scheme=${request.scheme} command=${request.commandId} keyIndex=$keyIndex " +
                "timeout=${request.timeoutSeconds} sdkTimeout=$SDK_PIN_TIMEOUT_SECONDS " +
                "pinLengths=${pinLengths.joinToString(",")} accountDigits=${numericAccount.length} " +
                "min=${request.minPin} max=${request.maxPin} result=$startResult",
        )
        return if (startResult == SdkResult.Success) {
            PinEntryStartResult.Started
        } else {
            clearPinEntryPendingCallback()
            PinpadDisplayController.showIdle()
            PinEntryStartResult.ImmediateResponse("B")
        }
    }

    /**
     * Captures a new PIN and a confirmation using identical key material and account data.
     *
     * @param request parsed secure PIN-entry request.
     * @param onResult receives the confirmed first capture or the terminal error.
     * @param retryAfterMismatch includes the mismatch notice in the next secure-entry prompt.
     * @return whether the first entry started; starts a second secure entry asynchronously on success.
     */
    private fun startNewPinEntry(
        request: PinEntryRequest,
        onResult: (PinEntryResult) -> Unit,
        retryAfterMismatch: Boolean = false,
    ): PinEntryStartResult {
        val firstPromptLines = if (retryAfterMismatch) {
            listOf(
                applicationContext.getString(R.string.prompt_pin_mismatch),
                applicationContext.getString(R.string.prompt_enter_new_pin),
            )
        } else {
            listOf(applicationContext.getString(R.string.prompt_enter_new_pin))
        }
        val firstRequest = request.copy(
            allowNullPin = false,
            promptLines = firstPromptLines,
            completionPrompt = "",
            advanceDukptKsnOnSuccess = false,
        )
        return startPinEntry(firstRequest) { firstResult ->
            if (firstResult !is PinEntryResult.Response || firstResult.payload.length == 1) {
                onResult(firstResult)
                return@startPinEntry
            }
            val scheme = request.newPinConfirmationScheme()
            if (!NewPinConfirmationPolicy.isCapture(firstResult.payload, scheme)) {
                completeNewPinEntry(request, firstResult, PinEntryResult.Response("B"), onResult)
                return@startPinEntry
            }
            val confirmationRequest = firstRequest.copy(
                promptLines = listOf(applicationContext.getString(R.string.prompt_confirm_new_pin)),
            )
            when (val confirmationStart = startPinEntry(confirmationRequest) { confirmationResult ->
                completeNewPinEntry(request, firstResult, confirmationResult, onResult)
            }) {
                PinEntryStartResult.Started -> Unit
                is PinEntryStartResult.ImmediateResponse -> completeNewPinEntry(
                    request,
                    firstResult,
                    PinEntryResult.Response(confirmationStart.payload),
                    onResult,
                )
            }
        }
    }

    /**
     * Finalizes new-PIN confirmation and consumes the reserved DUKPT KSN exactly once when applicable.
     *
     * @param request original parsed request.
     * @param firstResult successful first encrypted capture.
     * @param confirmationResult result of the confirmation capture.
     * @param onResult receives the final protocol result.
     */
    private fun completeNewPinEntry(
        request: PinEntryRequest,
        firstResult: PinEntryResult.Response,
        confirmationResult: PinEntryResult,
        onResult: (PinEntryResult) -> Unit,
    ) {
        if (request.scheme == PinKeyScheme.DUKPT && !advanceReservedDukptKsn()) {
            PinpadTraceLog.device("New-PIN capture could not advance reserved DUKPT KSN")
            PinpadDisplayController.showMessageThenIdle(applicationContext.getString(R.string.prompt_pin_entry_error))
            onResult(PinEntryResult.Response("B"))
            return
        }
        if (confirmationResult !is PinEntryResult.Response || confirmationResult.payload.length == 1) {
            onResult(confirmationResult)
            return
        }
        when (
            NewPinConfirmationPolicy.compare(
                firstResult.payload,
                confirmationResult.payload,
                request.newPinConfirmationScheme(),
            )
        ) {
            is NewPinConfirmationPolicy.Result.Confirmed -> {
                PinpadDisplayController.showProcessing()
                onResult(firstResult)
            }
            NewPinConfirmationPolicy.Result.Mismatch -> {
                PinpadTraceLog.device("New-PIN confirmation mismatch scheme=${request.scheme}")
                when (val restart = startNewPinEntry(request, onResult, retryAfterMismatch = true)) {
                    PinEntryStartResult.Started -> Unit
                    is PinEntryStartResult.ImmediateResponse -> onResult(PinEntryResult.Response(restart.payload))
                }
            }
            NewPinConfirmationPolicy.Result.Invalid -> {
                PinpadTraceLog.device("New-PIN confirmation response invalid scheme=${request.scheme}")
                PinpadDisplayController.showMessageThenIdle(applicationContext.getString(R.string.prompt_pin_entry_error))
                onResult(PinEntryResult.Response("B"))
            }
        }
    }

    /**
     * Advances the active DUKPT KSN and verifies that the device reports a different value.
     *
     * @return `true` only when the active KSN changed; mutates the secure DUKPT counter.
     */
    private fun advanceReservedDukptKsn(): Boolean {
        val keySet = activeDukptKeySet
        val before = currentDukptKsn(keySet)
        if (before.isBlank()) return false
        val incremented = runCatching { pinPad.dukptKsnIncrease(keySet) }
            .onFailure { Log.w(TAG, "Unable to advance reserved DUKPT KSN", it) }
            .isSuccess
        if (!incremented) return false
        val after = currentDukptKsn(keySet)
        return after.isNotBlank() && !after.equals(before, ignoreCase = true)
    }

    /** Maps an internal PIN-key scheme to the confirmation policy scheme. */
    private fun PinEntryRequest.newPinConfirmationScheme(): NewPinConfirmationPolicy.Scheme {
        return when (scheme) {
            PinKeyScheme.DUKPT -> NewPinConfirmationPolicy.Scheme.DUKPT
            PinKeyScheme.MASTER_SESSION,
            PinKeyScheme.SECRET_MASTER_SESSION -> NewPinConfirmationPolicy.Scheme.MASTER_SESSION
        }
    }

    private fun completePinEntry(
        completed: AtomicBoolean,
        result: PinEntryResult,
        onResult: (PinEntryResult) -> Unit,
    ) {
        if (!completed.compareAndSet(false, true)) return
        clearPinEntryPendingCallback()
        onResult(result)
    }

    private fun clearPinEntryPendingCallback() {
        pinEntryCompleted = null
        pinEntryOnResult = null
    }

    private fun buildSuccessfulPinResult(
        request: PinEntryRequest,
        keyIndex: Int,
        data: ByteArray?,
        enteredDigits: Int,
    ): PinEntryResult {
        val pinBlock = data?.toHex().orEmpty()
        if (pinBlock.isBlank()) return PinEntryResult.Response("B")
        showPinCompletionPrompt(request)
        return when (request.scheme) {
            PinKeyScheme.DUKPT -> {
                val ksn = currentDukptKsn(keyIndex)
                if (request.advanceDukptKsnOnSuccess) {
                    runCatching { pinPad.dukptKsnIncrease(keyIndex) }
                        .onFailure { Log.w(TAG, "Unable to advance DUKPT KSN", it) }
                }
                PinEntryResult.Response("0${formatDukptKsn(ksn)}$pinBlock")
            }
            PinKeyScheme.MASTER_SESSION,
            PinKeyScheme.SECRET_MASTER_SESSION -> {
                val pinLength = enteredDigits.coerceIn(request.minPin.coerceAtLeast(0), request.maxPin)
                PinEntryResult.Response(".0${pinLength.toString().padStart(2, '0')}01$pinBlock")
            }
        }
    }

    private fun buildNoPinResult(request: PinEntryRequest): PinEntryResult {
        if (!request.allowNullPin) return PinEntryResult.Response("8")
        showPinCompletionPrompt(request)
        return when (request.scheme) {
            PinKeyScheme.DUKPT -> PinEntryResult.Response("0")
            PinKeyScheme.MASTER_SESSION,
            PinKeyScheme.SECRET_MASTER_SESSION -> PinEntryResult.Response(".0001")
        }
    }

    private fun showPinCompletionPrompt(request: PinEntryRequest) {
        if (request.completionPrompt.isBlank()) {
            PinpadDisplayController.showProcessing()
        } else {
            PinpadDisplayController.showMessageThenIdle(request.completionPrompt)
        }
    }

    private fun emvOnlinePinResultFrom(
        result: PinEntryResult,
        scheme: PinKeyScheme,
    ): PinpadContactEmvController.EmvOnlinePinResult {
        if (result is PinEntryResult.Eot) {
            return PinpadContactEmvController.EmvOnlinePinResult.Canceled
        }
        val payload = (result as? PinEntryResult.Response)?.payload.orEmpty()
        if (payload.length == 1) {
            return PinpadContactEmvController.EmvOnlinePinResult.Error(payload.first())
        }
        return when (scheme) {
            PinKeyScheme.MASTER_SESSION,
            PinKeyScheme.SECRET_MASTER_SESSION -> emvMasterSessionPinResult(payload)
            PinKeyScheme.DUKPT -> emvDukptPinResult(payload)
        }
    }

    private fun emvMasterSessionPinResult(payload: String): PinpadContactEmvController.EmvOnlinePinResult {
        if (payload == ".0001") return PinpadContactEmvController.EmvOnlinePinResult.NoPin
        if (!payload.startsWith(".0") || payload.length <= 6) {
            return PinpadContactEmvController.EmvOnlinePinResult.Error('B')
        }
        val pinLength = payload.substring(2, 4).toIntOrNull() ?: return PinpadContactEmvController.EmvOnlinePinResult.Error('B')
        val keyId = payload.substring(4, 6)
        val pinBlock = payload.drop(6)
        if (!pinBlock.isHex() || pinBlock.length != PIN_BLOCK_HEX_CHARS) {
            return PinpadContactEmvController.EmvOnlinePinResult.Error('B')
        }
        return PinpadContactEmvController.EmvOnlinePinResult.Success(
            scheme = PinpadContactEmvController.EmvPinScheme.MASTER_SESSION,
            pinBlock = pinBlock,
            pinLength = pinLength,
            keyData = keyId,
        )
    }

    private fun emvDukptPinResult(payload: String): PinpadContactEmvController.EmvOnlinePinResult {
        if (payload == "0") return PinpadContactEmvController.EmvOnlinePinResult.NoPin
        if (!payload.startsWith('0') || payload.length <= PIN_BLOCK_HEX_CHARS + 1) {
            return PinpadContactEmvController.EmvOnlinePinResult.Error('B')
        }
        val body = payload.drop(1)
        val pinBlock = body.takeLast(PIN_BLOCK_HEX_CHARS)
        val ksn = body.dropLast(PIN_BLOCK_HEX_CHARS)
        if (!pinBlock.isHex() || !ksn.isHex()) return PinpadContactEmvController.EmvOnlinePinResult.Error('B')
        return PinpadContactEmvController.EmvOnlinePinResult.Success(
            scheme = PinpadContactEmvController.EmvPinScheme.DUKPT,
            pinBlock = pinBlock,
            pinLength = 0,
            ksn = ksn,
        )
    }

    private fun parsePinEntryRequest(commandId: String, payload: String, scheme: PinKeyScheme): PinEntryRequest? {
        val normalized = payload.trimStart('.')
        val fields = normalized.split(FS_CHAR)
        val account = when (commandId) {
            "60" -> payload
            else -> fields.firstOrNull().orEmpty()
        }
        val timeoutChar = fields.lastOrNull()?.singleOrNull()?.takeIf { it in '1'..'9' }
        val timeoutSeconds = timeoutChar?.digitToInt()?.times(PIN_TIMEOUT_UNIT_SECONDS) ?: DEFAULT_PIN_TIMEOUT_SECONDS
        return when (commandId) {
            "Z62" -> parseCustomPinEntryRequest(commandId, account, fields, scheme, timeoutSeconds)
            else -> PinEntryRequest(
                commandId = commandId,
                scheme = scheme,
                account = account,
                sessionKey = sessionKeyFrom(fields, scheme),
                minPin = DEFAULT_MIN_PIN,
                maxPin = DEFAULT_MAX_PIN,
                allowNullPin = false,
                timeoutSeconds = timeoutSeconds,
                promptLines = emptyList(),
                completionPrompt = "",
            )
        }
    }

    private fun parseSecretPinEntryRequest(commandId: String, payload: String): PinEntryRequest? {
        val normalized = payload.trimStart('.')
        val fields = normalized.split(FS_CHAR)
        val account = fields.firstOrNull().orEmpty()
        val control = fields.getOrNull(1).orEmpty()
        val secretKeySlot = control.firstOrNull()?.digitToIntOrNull() ?: return null
        if (secretKeySlot !in SECRET_KEY_ID_RANGE) return null
        if (validateAccount(account) != null) return null
        return when (commandId) {
            "24" -> {
                val pinControl = control.drop(1)
                customRequestFromPinControl(
                    commandId = commandId,
                    scheme = PinKeyScheme.SECRET_MASTER_SESSION,
                    account = account,
                    sessionKey = "",
                    pinControl = pinControl,
                    fields = fields,
                    timeoutSeconds = DEFAULT_PIN_TIMEOUT_SECONDS,
                )?.copy(secretKeySlot = secretKeySlot)
            }
            else -> PinEntryRequest(
                commandId = commandId,
                scheme = PinKeyScheme.SECRET_MASTER_SESSION,
                account = account,
                sessionKey = "",
                minPin = DEFAULT_MIN_PIN,
                maxPin = DEFAULT_MAX_PIN,
                allowNullPin = false,
                timeoutSeconds = DEFAULT_PIN_TIMEOUT_SECONDS,
                promptLines = emptyList(),
                completionPrompt = "",
                secretKeySlot = secretKeySlot,
            )
        }
    }

    private fun parseCustomPinEntryRequest(
        commandId: String,
        account: String,
        fields: List<String>,
        scheme: PinKeyScheme,
        timeoutSeconds: Int,
    ): PinEntryRequest? {
        val control = fields.getOrNull(1).orEmpty()
        if (scheme == PinKeyScheme.MASTER_SESSION) {
            val (sessionKey, pinControl) = extractSessionKey(control) { remainder ->
                remainder.length >= PIN_CONTROL_CHARS && remainder.take(PIN_CONTROL_CHARS).let {
                    val minPin = it.take(2).toIntOrNull()
                    val maxPin = it.drop(2).take(2).toIntOrNull()
                    val allowNull = it.getOrNull(4)?.uppercaseChar() == 'Y'
                    minPin != null && maxPin != null && validPinBounds(minPin, maxPin, allowNull)
                }
            }
                ?: return null
            return customRequestFromPinControl(commandId, scheme, account, sessionKey, pinControl, fields, timeoutSeconds)
        }
        return customRequestFromPinControl(commandId, scheme, account, "", control, fields, timeoutSeconds)
    }

    private fun customRequestFromPinControl(
        commandId: String,
        scheme: PinKeyScheme,
        account: String,
        sessionKey: String,
        pinControl: String,
        fields: List<String>,
        timeoutSeconds: Int,
    ): PinEntryRequest? {
        if (pinControl.length < PIN_CONTROL_CHARS) return null
        val minPin = pinControl.take(2).toIntOrNull() ?: return null
        val maxPin = pinControl.drop(2).take(2).toIntOrNull() ?: return null
        val allowNull = pinControl.getOrNull(4)?.uppercaseChar() == 'Y'
        if (!validPinBounds(minPin, maxPin, allowNull)) return null
        val firstPrompt = pinControl.drop(PIN_CONTROL_CHARS).trim()
        val secondPrompt = fields.getOrNull(2).orEmpty().trim()
        val completionPrompt = fields.getOrNull(3).orEmpty().trim()
        return PinEntryRequest(
            commandId = commandId,
            scheme = scheme,
            account = account,
            sessionKey = sessionKey,
            minPin = minPin,
            maxPin = maxPin,
            allowNullPin = allowNull,
            timeoutSeconds = timeoutSeconds,
            promptLines = listOf(firstPrompt, secondPrompt).filter { it.isNotBlank() },
            completionPrompt = completionPrompt,
        )
    }

    private fun sessionKeyFrom(fields: List<String>, scheme: PinKeyScheme): String {
        if (scheme != PinKeyScheme.MASTER_SESSION) return ""
        val field = fields.getOrNull(1).orEmpty()
        return extractSessionKey(field) { remainder ->
            remainder.isEmpty() || remainder.length in 4..12
        }?.first.orEmpty()
    }

    private fun extractSessionKey(
        value: String,
        remainderValidator: (String) -> Boolean,
    ): Pair<String, String>? {
        return SESSION_KEY_HEX_LENGTHS.firstNotNullOfOrNull { length ->
            if (value.length >= length && value.take(length).isHex()) {
                val remainder = value.drop(length)
                if (remainderValidator(remainder)) value.take(length) to remainder else null
            } else {
                null
            }
        }
    }

    private fun secretPinKeyExists(slot: Int): Boolean {
        if (slot !in SECRET_KEY_ID_RANGE) return false
        if (secretMasterKeyLoadedThisRun && slot !in secretSessionKeyIdsLoaded) {
            PinpadTraceLog.device("isSecretPinKeyExist id=$slot rejected after current Secret MK reload")
            return false
        }
        val sdkSlot = slot.toSecretSdkSlot()
        return runCatching { pinPad.isKeyExist(sdkSlot, WorkKeyTypeEnum.PINKEY) }
            .onSuccess { PinpadTraceLog.device("isSecretPinKeyExist id=$slot slot=$sdkSlot result=$it") }
            .onFailure { Log.w(TAG, "Unable to query secret PIN key", it) }
            .getOrDefault(false)
    }

    private fun prepareMasterSessionPinKey(sessionKey: String): SessionPinKeyResult {
        val activeKeyId = prefs.activeMasterKeyId().firstOrNull() ?: '0'
        val slot = activeKeyId.toMasterKeySlot() ?: return SessionPinKeyResult.Error('9')
        val attribute = storedMasterKeyAttribute(activeKeyId)
            ?: return SessionPinKeyResult.Error('9').also { showKeyMetadataMissing(activeKeyId) }
        if (!masterKeyLoaded(activeKeyId)) return SessionPinKeyResult.Error('9')
        return when (val action = masterSessionPinKeyAction(attribute.usage, sessionKey)) {
            MasterSessionPinKeyAction.UseResidentPinKey -> {
                PinpadTraceLog.device("PINKEY reused slot=$slot usage=${attribute.usage}")
                SessionPinKeyResult.Ready(slot)
            }
            MasterSessionPinKeyAction.LoadEncryptedSessionKey -> {
                val sessionBytes = sessionKey.hexToBytesOrNull() ?: return SessionPinKeyResult.Error('5')
                val result = runCatching {
                    pinPad.setAlgorithmMode(AlgorithmModeEnum.DES)
                    pinPad.writeWKey(slot, WorkKeyTypeEnum.PINKEY, sessionBytes, sessionBytes.size)
                }.onFailure {
                    Log.w(TAG, "Unable to write session PIN key", it)
                }.getOrDefault(SdkResult.Fail)
                PinpadTraceLog.device("writeWKey PINKEY slot=$slot usage=${attribute.usage} sdkResult=$result")
                if (result == SdkResult.Success) SessionPinKeyResult.Ready(slot) else SessionPinKeyResult.Error('B')
            }
            is MasterSessionPinKeyAction.Reject -> {
                if (action.schemeMismatch) showPinKeySchemeError(activeKeyId, attribute.usage)
                SessionPinKeyResult.Error(action.responseCode)
            }
        }
    }

    private fun showPinKeySchemeError(activeKeyId: Char, usage: String) {
        PinpadTraceLog.device("PIN key scheme mismatch activeKey=$activeKeyId usage=$usage")
        PinpadDisplayController.showPinKeySchemeErrorThenIdle(activeKeyId)
    }

    private fun showKeyMetadataMissing(activeKeyId: Char) {
        PinpadTraceLog.device("PIN key metadata missing activeKey=$activeKeyId")
        PinpadDisplayController.showKeyMetadataMissingThenIdle(activeKeyId)
    }

    private fun validateAccount(account: String): Char? {
        return when {
            account.isEmpty() -> '0'
            account.length < MIN_ACCOUNT_DIGITS -> '2'
            account.length > MAX_ACCOUNT_DIGITS -> '3'
            !account.all(Char::isDigit) -> '4'
            else -> null
        }
    }

    private fun validPinBounds(minPin: Int, maxPin: Int, allowNull: Boolean): Boolean {
        if (allowNull && minPin == 0 && maxPin == 0) return true
        return minPin in DEFAULT_MIN_PIN..DEFAULT_MAX_PIN && maxPin in minPin..DEFAULT_MAX_PIN
    }

    private fun showKeyLoadCue(message: String, returnToKeyInjectionMode: Boolean = false) {
        PinpadTraceLog.device("key load cue message=$message")
        if (returnToKeyInjectionMode) {
            PinpadDisplayController.showMessageThenKeyInjectionMode(message)
        } else {
            PinpadDisplayController.showMessageThenIdle(message)
        }
        runCatching { deviceEngine.beeper.beep(KEY_LOAD_BEEP_MS) }
            .onFailure {
                Log.w(TAG, "Key load cue beep failed", it)
                PinpadTraceLog.device("key load cue beep failed=${it.message}")
            }
    }

    private fun beepForCardPrompt(sourceCommand: String) {
        runCatching { deviceEngine.beeper.beep(CARD_PROMPT_BEEP_MS) }
            .onSuccess { PinpadTraceLog.device("$sourceCommand card prompt beep=${CARD_PROMPT_BEEP_MS}ms") }
            .onFailure { PinpadTraceLog.device("$sourceCommand card prompt beep failed=${it.message}") }
    }

    private fun PinEntryRequest.pinLengths(): IntArray {
        if (allowNullPin && minPin == 0 && maxPin == 0) return intArrayOf(0)
        return (minPin.coerceAtLeast(DEFAULT_MIN_PIN)..maxPin.coerceAtMost(DEFAULT_MAX_PIN)).toList().toIntArray()
    }

    private fun injectClearDukptKey(keySet: Int, payload: String): DukptLoadResult {
        val ipek = payload.take(DUKPT_IPEK_CHARS).hexToBytesOrNull()
        val ksn = payload.drop(DUKPT_IPEK_CHARS).take(DUKPT_KSN_CHARS).hexToBytesOrNull()
        if (ipek == null || ksn == null) return DukptLoadResult(false, '2')
        return runCatching {
            pinPad.setAlgorithmMode(AlgorithmModeEnum.DUKPT)
            pinPad.setDukptAlgorithmMode(DukptAlgorithmModeEnum.DES)
            pinPad.dukptKeyInject(keySet, DukptKeyTypeEnum.IPEK, ipek, ipek.size, ksn)
        }.fold(
            onSuccess = { result ->
                PinpadTraceLog.device("dukptKeyInject keySet=$keySet sdkResult=$result")
                DukptLoadResult(result == SdkResult.Success, if (result == SdkResult.Success) null else '4')
            },
            onFailure = {
                Log.w(TAG, "Clear DUKPT injection failed", it)
                DukptLoadResult(false, '4')
            },
        )
    }

    private fun dukptKeyExists(keySet: Int): Boolean {
        return runCatching { pinPad.dukptCurrentKsn(keySet) != null }
            .onFailure { Log.w(TAG, "Unable to query DUKPT key", it) }
            .getOrDefault(false)
    }

    private fun currentDukptKsn(keySet: Int): String {
        return runCatching { pinPad.dukptCurrentKsn(keySet)?.toHex().orEmpty() }
            .onFailure { Log.w(TAG, "Unable to read DUKPT KSN", it) }
            .getOrDefault("")
    }

    private fun formatDukptKsn(ksn: String): String {
        if (dukptFullKsnOutput) return ksn
        val stripped = ksn.dropWhile { it == 'F' }
        return stripped.ifEmpty { ksn }
    }

    private fun cpuAtrHex(buffer: ByteArray): String {
        val length = buffer.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        return if (length in 1 until buffer.size) {
            buffer.copyOfRange(1, length + 1).toHex()
        } else {
            buffer.takeWhile { it != 0.toByte() }.toByteArray().toHex()
        }
    }

    private fun dukptKcv(keySet: Int): String {
        return runCatching {
            pinPad.dukptEncrypt(keySet, DukptKeyModeEnum.REQUEST, ByteArray(KCV_ZERO_BLOCK_BYTES), KCV_ZERO_BLOCK_BYTES)
                ?.take(KCV_BYTES)
                ?.toByteArray()
                ?.toHex()
                .orEmpty()
        }.onFailure {
            Log.w(TAG, "Unable to calculate DUKPT KCV", it)
        }.getOrDefault("")
    }

    private fun Char.toMasterKeySlot(): Int? {
        return when (uppercaseChar()) {
            in '0'..'9' -> digitToInt()
            in 'A'..'G' -> 10 + (uppercaseChar() - 'A')
            else -> null
        }
    }

    private fun Int.toSecretSdkSlot(): Int = SECRET_KEY_BASE_SLOT + this

    private fun String.isHexKeyValue(): Boolean {
        return length in setOf(16, 32, 48) && all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
    }

    private fun String.isHex(): Boolean = isNotEmpty() && all { it.digitToIntOrNull(16) != null }

    private fun String.isRgbHex(): Boolean {
        return length == 6 && all { it.digitToIntOrNull(16) != null }
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || !all { it.digitToIntOrNull(16) != null }) return null
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it) }

    sealed class KeyLoadResult {
        data object Success : KeyLoadResult()
        data class Error(val code: Char) : KeyLoadResult()
    }

    sealed class PinEntryStartResult {
        data object Started : PinEntryStartResult()
        data class ImmediateResponse(val payload: String) : PinEntryStartResult()
    }

    sealed class PinEntryResult {
        data class Response(val payload: String) : PinEntryResult()
        data object Eot : PinEntryResult()
    }

    sealed class CpuCardResult {
        data class Response(val commandId: String, val payload: String) : CpuCardResult()
        data class Error(val code: String) : CpuCardResult()
    }

    sealed class MultiInterfaceDetectionStartResult {
        data object Started : MultiInterfaceDetectionStartResult()
        data class ImmediateResponse(val result: MultiInterfaceDetectionResult) : MultiInterfaceDetectionStartResult()
    }

    data class MultiInterfaceDetectionResult(
        val result: Char,
        val error: Char? = null,
    ) {
        val payload: String = if (result == '0') "0${error ?: '3'}" else result.toString()

        companion object {
            fun enableMsrFail() = MultiInterfaceDetectionResult('0', '0')
            fun canceled() = MultiInterfaceDetectionResult('0', '3')
            fun msr() = MultiInterfaceDetectionResult('1')
            fun icc() = MultiInterfaceDetectionResult('2')
            fun pcd() = MultiInterfaceDetectionResult('3')
        }
    }

    data class DukptLoadResult(
        val success: Boolean,
        val errorCode: Char?,
    )

    data class DukptStatus(
        val status: Char,
        val kcv: String?,
    )

    data class MacResult(
        val success: Boolean,
        val mac: String,
    )

    private sealed class SessionPinKeyResult {
        data class Ready(val slot: Int) : SessionPinKeyResult()
        data class Error(val code: Char) : SessionPinKeyResult()
    }

    private data class MasterKeyAttribute(
        val usage: String,
        val mode: Char,
        val algorithm: Char,
    ) {
        fun workKeyType(): WorkKeyTypeEnum? {
            return when (usage) {
                "P0" -> WorkKeyTypeEnum.PINKEY
                "M3" -> WorkKeyTypeEnum.MACKEY
                "D0" -> WorkKeyTypeEnum.ENCRYPTIONKEY
                else -> null
            }
        }

        fun storageValue(): String = "$usage|$mode|$algorithm"

        fun protocolPayload(): String = "$usage$FS_CHAR$mode$FS_CHAR$algorithm"

        companion object {
            fun fromStorageValue(value: String): MasterKeyAttribute? {
                val parts = value.split('|')
                if (parts.size != 3) return null
                val usage = parts[0].takeIf { it.matches(KEY_USAGE_REGEX) } ?: return null
                val mode = parts[1].singleOrNull()?.takeIf { it in KEY_MODE_VALUES } ?: return null
                val algorithm = parts[2].singleOrNull()?.takeIf { it in KEY_ALGORITHM_VALUES } ?: return null
                return MasterKeyAttribute(usage, mode, algorithm)
            }
        }
    }

    private data class PinEntryRequest(
        val commandId: String,
        val scheme: PinKeyScheme,
        val account: String,
        val sessionKey: String,
        val minPin: Int,
        val maxPin: Int,
        val allowNullPin: Boolean,
        val timeoutSeconds: Int,
        val promptLines: List<String>,
        val completionPrompt: String,
        val secretKeySlot: Int? = null,
        val advanceDukptKsnOnSuccess: Boolean = true,
    )

    private enum class PinKeyScheme {
        DUKPT,
        MASTER_SESSION,
        SECRET_MASTER_SESSION,
    }

    companion object {
        private const val TAG = "PinpadDeviceCommands"
        private const val RANDOM_BLOCK_BYTES = 8
        private const val KCV_ZERO_BLOCK_BYTES = 8
        private const val KCV_BYTES = 3
        private const val CPU_ATR_BUFFER_BYTES = 64
        private const val CPU_APDU_MIN_HEX_CHARS = 8
        private const val CPU_APDU_MAX_HEX_CHARS = 524
        private const val MAX_SPEECH_CHARACTERS = 1_000
        private const val RHVOICE_ENGINE_PACKAGE = "com.github.olga_yakovleva.rhvoice.android"
        private const val ESPEAK_ENGINE_PACKAGE = "com.reecedunn.espeak"
        private const val RHVOICE_SPANISH_VOICE = "Mateo"
        private const val VOICE_PROVISIONING_TEXT = "."
        private const val TTS_APPLICATION_RETRY_MS = 30_000L
        private const val TTS_APPLICATION_INSTALL_RECHECK_MS = 300_000L
        private const val TTS_VOICE_READINESS_RECHECK_MS = 15_000L
        private const val TTS_VOICE_PROVISIONING_RETRY_MS = 60_000L
        private const val TTS_PARAM_LANGUAGE = "language"
        private const val TTS_PARAM_COUNTRY = "country"
        private const val TTS_PARAM_VARIANT = "variant"
        private const val TTS_MAX_OUTPUT_VOLUME = 1f
        private const val TTS_SPEECH_RATE = 0.85f
        private val TTS_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        private val RHVOICE_SPANISH_PROVISIONING_LOCALE = Locale.forLanguageTag("es-MX")
        private val RHVOICE_ENGLISH_PROVISIONING_LOCALE = Locale.US
        private val SPANISH_SPEECH_LANGUAGE_TAGS = setOf("es", "es-mx", "es-419", "es-la")
        private val REQUIRED_TEXT_TO_SPEECH_APPLICATIONS = listOf(
            ApplicationRequirementClient.CAPABILITY_ANDROID_TTS to RHVOICE_ENGINE_PACKAGE,
        )
        private val REQUIRED_TEXT_TO_SPEECH_CAPABILITIES =
            REQUIRED_TEXT_TO_SPEECH_APPLICATIONS.mapTo(mutableSetOf()) { it.first }
        private const val FS_CHAR = '\u001C'
        private const val DEFAULT_MASTER_KEY_ALGORITHM = 'T'
        private const val MIN_ACCOUNT_DIGITS = 8
        private const val MAX_ACCOUNT_DIGITS = 19
        private const val DEFAULT_MIN_PIN = 4
        private const val DEFAULT_MAX_PIN = 12
        private const val DEFAULT_PIN_TIMEOUT_SECONDS = 270
        private const val SDK_PIN_TIMEOUT_SECONDS = 60
        private const val PIN_TIMEOUT_UNIT_SECONDS = 30
        private const val PIN_CONTROL_CHARS = 5
        private const val PIN_BLOCK_HEX_CHARS = 16
        private const val DEFAULT_SESSION_KEY_HEX_CHARS = 16
        private const val DUKPT_IPEK_CHARS = 32
        private const val DUKPT_KSN_CHARS = 20
        private const val DUKPT_CLEAR_KEY_CHARS = DUKPT_IPEK_CHARS + DUKPT_KSN_CHARS
        private const val PIN_PROMPT_BEEP_MS = 120
        private const val CARD_PROMPT_BEEP_MS = 90
        private const val KEY_LOAD_BEEP_MS = 80
        private const val KEY_LOAD_MASTER_MESSAGE = "Master Key Loaded"
        private const val KEY_LOAD_IPEK_MESSAGE = "IPEK Loaded"
        private const val KEY_LOAD_SECRET_MASTER_MESSAGE = "Secret Master Key Loaded"
        private const val KEY_LOAD_SECRET_SESSION_MESSAGE = "Secret Session Key Loaded"
        private const val MAC_RESPONSE_HEX_CHARS = 16
        private const val MSR_SEARCH_TIMEOUT_SECONDS = 60
        private const val MULTI_INTERFACE_SEARCH_TIMEOUT_SECONDS = 60
        private const val MSR_TRACK2_ONLY = '0'
        private const val MSR_DISABLED = '1'
        private const val MSR_ALL_TRACKS = '2'
        private const val MSR_CLEAR_TEXT = '0'
        private const val MSR_CLEAR_TEXT_SENTINELS = '2'
        private val PUSN_SLOT_RANGE = 0..7
        private val DUKPT_KEY_SET_RANGE = 0..1
        // Protocol Secret SK ids remain 0-9; SDK slots are offset to avoid normal MK/SK slots.
        private const val SECRET_KEY_BASE_SLOT = 20
        private val SECRET_KEY_ID_RANGE = 0..9
        private val SESSION_KEY_HEX_LENGTHS = listOf(48, 32, 16)
        private val SECRET_KEY_HEX_LENGTHS = setOf(16, 32)
        private val SECRET_MASTER_KEY_OPTIONS = setOf('0', '1')
        private val KEY_USAGE_REGEX = Regex("[A-Z][0-9A-Z]")
        private val KEY_MODE_VALUES = setOf('B', 'C', 'D', 'E', 'G', 'N', 'S', 'V', 'X')
        private val KEY_ALGORITHM_VALUES = setOf('A', 'D', 'E', 'H', 'R', 'S', 'T')
        private val PUSN_REGEX = Regex("[0-9A-Z-]{11}")
    }
}

data class MsrTrackData(
    val track1: String,
    val track2: String,
    val track3: String,
    val trackMode: Char,
    val outputFormat: Char,
)
