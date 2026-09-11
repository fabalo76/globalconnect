package one.globalconnect.pinpad.protocol

import one.globalconnect.pinpad.device.PinpadDeviceCommands
import one.globalconnect.pinpad.device.PinpadDeviceInfoProvider
import one.globalconnect.pinpad.device.FirmwareVersion
import one.globalconnect.pinpad.device.MsrTrackData
import one.globalconnect.pinpad.device.PinpadContactEmvController
import one.globalconnect.pinpad.device.PinpadEmvDataObjects
import one.globalconnect.pinpad.device.PinpadEmvConfigStore
import one.globalconnect.pinpad.device.PinManagementPolicy
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.model.PinpadCommandRequest
import one.globalconnect.pinpad.model.PinpadCommandResponse
import one.globalconnect.pinpad.model.PinpadTransactionDisplay
import one.globalconnect.pinpad.ui.PinpadDisplayController
import one.globalconnect.pinpad.ui.TextEntryEchoMode
import one.globalconnect.pinpad.security.KeyLoadAuthorizer
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PINPADSessionController(
    private val deviceInfoProvider: PinpadDeviceInfoProvider,
    private val commandDevice: PinpadDeviceCommands? = null,
    private val keyLoadAuthorizer: KeyLoadAuthorizer? = null,
    private val codec: PINPADFrameCodec = PINPADFrameCodec(),
    private val asyncResponseSender: (ByteArray) -> Unit = {},
    private val includeFrameAckInResponses: Boolean = true,
) {
    @Volatile private var pendingFinalEot = false
    @Volatile private var pinManagementFlowActive = false
    @Volatile private var communicationTestAwaitingEcho = false
    @Volatile private var pendingSerialPortChange: SerialPortChange? = null
    @Volatile private var completedSerialPortChange: SerialPortChange? = null
    @Volatile private var fallbackMsrOutputFormat = '0'
    @Volatile private var dataEntryPromptReady = false
    @Volatile private var dataEntryPromptText = ""
    @Volatile private var cancelMessageDisplayEnabled = true
    @Volatile private var transactionDisplayContext: PinpadTransactionDisplay? = null
    private val dataEntryLock = Any()
    private val dataEntryScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pinpad-data-entry").apply { isDaemon = true }
    }
    private var activeDataEntry: ActiveDataEntry? = null
    private var dataEntryTimeout: ScheduledFuture<*>? = null
    private val packetTransferLock = Any()
    private val pendingPacketFrames = ArrayDeque<ByteArray>()
    @Volatile private var visualOperationInProgress = false
    @Volatile private var packetTransferAwaitingAck = false
    @Volatile private var packetTransferResponseCommand: String? = null

    fun onInbound(inbound: PINPADInbound): List<ByteArray> {
        val responses = when (inbound) {
            is PINPADInbound.Frame -> onFrame(inbound.frame)
            is PINPADInbound.Control -> {
                PinpadTraceLog.protocol("inbound control=${PinpadTraceLog.controlName(inbound.value)}")
                onControl(inbound.value)
            }
            PINPADInbound.InvalidFrame -> {
                PinpadTraceLog.protocol("inbound invalid frame; responding NAK")
                listOf(byteArrayOf(PINPADControl.NAK))
            }
            PINPADInbound.FrameTimeout -> {
                PinpadTraceLog.protocol("inbound partial frame timeout; responding EOT")
                pendingFinalEot = false
                communicationTestAwaitingEcho = false
                pendingSerialPortChange = null
                listOf(byteArrayOf(PINPADControl.EOT))
            }
        }
        PinpadTraceLog.protocol("outbound ${responses.responseSummary()} pendingFinalEot=$pendingFinalEot")
        return responses
    }

    fun abortPendingResponse() {
        pendingFinalEot = false
        pinManagementFlowActive = false
        communicationTestAwaitingEcho = false
        pendingSerialPortChange = null
        clearPacketTransfer()
        visualOperationInProgress = false
        PinpadDisplayController.dismissVisualOperation()
        PinpadTraceLog.protocol("pending response aborted")
    }

    fun suppressPendingFinalEot(reason: String) {
        if (pendingFinalEot) {
            PinpadTraceLog.protocol("pending final EOT suppressed: $reason")
        }
        pendingFinalEot = false
        pendingSerialPortChange = null
    }

    fun consumeCompletedSerialPortChange(): SerialPortChange? {
        val change = completedSerialPortChange
        completedSerialPortChange = null
        return change
    }

    fun cancelActiveOperation() {
        PinpadTraceLog.command("CANCEL", "physical cancel key")
        if (keyLoadAuthorizer?.cancel() == true) return
        if (PinpadDisplayController.completeSignatureCapture(SignatureCaptureResult.Cancelled)) return
        if (PinpadDisplayController.completePhotoCapture(PhotoCaptureResult.Cancelled)) return
        if (PinpadDisplayController.completeQrDisplay(QrDisplayResult.Cancelled)) return
        if (PinpadDisplayController.completeQrScan(QrScanResult.Cancelled)) return
        transactionDisplayContext = null
        if (cancelActiveDataEntry(showCancelMessage = cancelMessageDisplayEnabled)) return
        val canceled = commandDevice?.cancelUserOperation(showCancelMessage = cancelMessageDisplayEnabled) == true
        if (!canceled) {
            // A Z2/Z3 display message is passive: there is no device operation for
            // cancelUserOperation() to cancel. In that state the physical Cancel key
            // acts as the legacy return-to-idle action and restores the configured
            // idle prompt. Active entry, key injection, camera, signature, card and
            // PIN operations have already been handled by the guards above.
            PinpadTraceLog.command("CANCEL", "no active operation; restoring idle prompt")
            PinpadDisplayController.showIdle()
        }
        pendingFinalEot = false
        communicationTestAwaitingEcho = false
    }

    fun beginClearKeyInjectionMode() {
        if (keyLoadAuthorizer?.beginClearKeyInjectionMode() != true) {
            PinpadTraceLog.device("clear-key injection mode authorization request ignored")
        }
    }

    fun endClearKeyInjectionMode(reason: String) {
        keyLoadAuthorizer?.endClearKeyInjectionMode(reason)
    }

    fun onKeypadKey(key: PinpadKeypadKey) {
        if (keyLoadAuthorizer?.onKeypadKey(key) == true) return
        val entry = synchronized(dataEntryLock) { activeDataEntry }
        if (entry == null) {
            commandDevice?.handleKeypadKey(key)
            return
        }
        PinpadTraceLog.command(entry.commandId, "keypad key=$key")
        when (entry) {
            is ActiveDataEntry.ReadKeyCode -> handleReadKeyCodeKey(entry, key)
            is ActiveDataEntry.StringEntry -> handleStringEntryKey(entry, key)
        }
    }

    fun shutdown() {
        keyLoadAuthorizer?.endClearKeyInjectionMode("service_shutdown")
        visualOperationInProgress = false
        clearPacketTransfer()
        PinpadDisplayController.dismissVisualOperation()
        commandDevice?.shutdown()
        synchronized(dataEntryLock) {
            dataEntryTimeout?.cancel(false)
            dataEntryTimeout = null
            activeDataEntry = null
        }
        dataEntryScheduler.shutdownNow()
    }

    private fun onFrame(frame: PINPADFrame): List<ByteArray> {
        if (pendingSerialPortChange != null) {
            PinpadTraceLog.protocol("pending serial-port change canceled by new inbound frame")
            pendingSerialPortChange = null
        }
        PinpadTraceLog.command(
            frame.commandId,
            "received frameType=${frame.frameType.name} payloadLen=${frame.payload.size}",
        )
        val clearKeyInjectionModeActive =
            keyLoadAuthorizer?.isClearKeyInjectionModeActive() == true
        if (clearKeyInjectionModeActive && frame.commandId !in CLEAR_KEY_INJECTION_ALLOWED_COMMANDS) {
            PinpadTraceLog.command(
                frame.commandId,
                "dropped while clear-key injection mode is active; responding EOT",
            )
            return listOf(byteArrayOf(PINPADControl.EOT))
        }
        if (!clearKeyInjectionModeActive && frame.commandId in CLEAR_KEY_INJECTION_PROTECTED_COMMANDS) {
            PinpadTraceLog.command(
                frame.commandId,
                "clear-key injection mode is not authorized; dropping command and responding EOT",
            )
            return listOf(byteArrayOf(PINPADControl.EOT))
        }
        if (!frame.isSupportedCommand()) {
            PinpadTraceLog.command(frame.commandId, "unsupported message id frameType=${frame.frameType.name}; ignoring")
            return emptyList()
        }
        if (clearKeyInjectionModeActive) {
            keyLoadAuthorizer?.recordClearKeyInjectionActivity()
        }

        if (communicationTestAwaitingEcho) {
            PinpadTraceLog.command(frame.commandId, "processing communication test echo")
            return onCommunicationTestEcho(frame)
        }

        val request = PinpadCommandRequest(
            commandId = frame.commandId,
            frameType = frame.frameType.name,
            payloadAscii = frame.payloadAscii,
            rawHex = frame.payload.joinToString("") { "%02X".format(it) },
        )

        val responses = newFrameResponseList()
        when (request.commandId) {
            "02" -> {
                val keyId = request.payloadAscii.firstOrNull()
                val keyPayload = request.payloadAscii.drop(1)
                PinpadTraceLog.command(
                    "02",
                    "load key keyId=${keyId ?: "<missing>"} payload redacted length=${frame.payload.size}",
                )
                if (!isLoadKeyRequestFormatValid(keyId, keyPayload)) {
                    PinpadTraceLog.command("02", "load key invalid command format; responding EOT")
                    return newFrameResponseList(byteArrayOf(PINPADControl.EOT))
                }
                responses.sendAckBeforeSlowProcessing("02")
                responses += executeClearKeyLoad(frame, keyId, keyPayload)
            }
            "04" -> {
                val keyId = request.payloadAscii.firstOrNull()
                val getAttr = request.payloadAscii.getOrNull(1) == '1'
                val loadedKeyId = keyId?.takeIf { commandDevice?.masterKeyLoaded(it) == true }
                PinpadTraceLog.command(
                    "04",
                    "check master key keyId=${keyId ?: "<missing>"} getAttr=$getAttr loaded=${loadedKeyId != null}",
                )
                val payload = buildString {
                    append(if (loadedKeyId != null) 'F' else '0')
                    if (loadedKeyId != null && getAttr) {
                        append(commandDevice?.masterKeyAttributePayload(loadedKeyId) ?: defaultKeyAttribute(loadedKeyId))
                    }
                }
                responses += responseFrame("04", payload)
                pendingFinalEot = true
            }
            "05" -> {
                PinpadTraceLog.command("05", "load serial number dummy")
                val serial = request.payloadAscii.take(16)
                responses += codec.encode(PINPADFrame(PINPADFrameType.Administration, "05", serial.ascii()))
                pendingFinalEot = true
            }
            "06" -> {
                PinpadTraceLog.command("06", "get serial number")
                responses += responseFrame("06", deviceInfoProvider.serialNumber())
                pendingFinalEot = true
            }
            "08" -> {
                PinpadTraceLog.command("08", "select active master key")
                val keyId = request.payloadAscii.firstOrNull()
                val success = keyId != null && commandDevice?.selectActiveMasterKey(keyId) == true
                responses += responseFrame("08", if (success) "0" else "1")
                pendingFinalEot = true
            }
            "09" -> {
                PinpadTraceLog.command("09", "communication test start")
                responses += responseFrame("09", "${PINPADControl.SUB.toInt().toChar()}PROCESSING")
                communicationTestAwaitingEcho = true
            }
            "11" -> {
                PinpadTraceLog.command("11", "device connection test")
                pendingFinalEot = false
            }
            "14" -> {
                PinpadTraceLog.command("14", "acknowledge without final EOT")
                pendingFinalEot = false
            }
            "12" -> {
                val index = request.payloadAscii.firstOrNull()
                val status = index?.let { commandDevice?.setPromptLanguage(it) ?: '0' } ?: '0'
                PinpadTraceLog.command("12", "select prompt language index=${index ?: "<missing>"} status=$status")
                responses += responseFrame("12", status.toString())
                pendingFinalEot = true
            }
            "13" -> {
                val baudCode = request.payloadAscii.firstOrNull()
                val mode = request.payloadAscii.getOrNull(1)
                val change = if (request.payloadAscii.length in 1..2) {
                    SerialPortChange.fromCommand(baudCode, mode)
                } else {
                    null
                }
                val status = if (change == null) '1' else '0'
                pendingSerialPortChange = change
                PinpadTraceLog.command(
                    "13",
                    "adjust baud code=${baudCode ?: "<missing>"} mode=${mode ?: "<default>"} " +
                        "baud=${change?.baudRate ?: "<invalid>"} status=$status",
                )
                responses += responseFrame("13", status.toString())
                pendingFinalEot = true
            }
            "19" -> {
                PinpadTraceLog.command("19", "query firmware version")
                val part = request.payloadAscii.firstOrNull() ?: '0'
                val option = request.payloadAscii.getOrNull(1)
                val version = deviceInfoProvider.firmwareVersion(part, option)
                responses += responseFrame(
                    PinpadCommandResponse(
                        commandId = "19",
                        frameType = PINPADFrameType.Administration.name,
                        payloadAscii = formatFirmwareVersion(version),
                    ),
                )
                pendingFinalEot = true
            }
            "17" -> {
                PinpadTraceLog.command("17", "request random number")
                val random = commandDevice?.randomBlockHex() ?: "0000000000000000"
                responses += responseFrame("17", random)
                pendingFinalEot = true
            }
            "18" -> {
                PinpadTraceLog.command(
                    "18",
                    "set local time compatibility dummy payload=${request.payloadAscii}; responding success without changing device settings",
                )
                responses += responseFrame("18", "0")
                pendingFinalEot = true
            }
            "20" -> {
                PinpadTraceLog.command("20", "load secret master key payload redacted length=${frame.payload.size}")
                if (!isSecretMasterKeyRequestFormatValid(request.payloadAscii)) {
                    PinpadTraceLog.command("20", "load secret master key invalid command format; responding EOT")
                    return newFrameResponseList(byteArrayOf(PINPADControl.EOT))
                }
                responses.sendAckBeforeSlowProcessing("20")
                if (keyLoadAuthorizer?.requiresAuthorization("20") == true) {
                    val started = keyLoadAuthorizer.requestAuthorization("20") { authorized ->
                        completeSecretKeyLoad(frame, authorized, masterKey = true)
                    }
                    if (!started) asyncResponseSender(byteArrayOf(PINPADControl.EOT))
                    return responses
                }
                responses += executeSecretKeyLoad(frame, masterKey = true)
            }
            "21" -> {
                val keyId = request.payloadAscii.firstOrNull()
                PinpadTraceLog.command(
                    "21",
                    "load secret session key keyId=${keyId ?: "<missing>"} payload redacted length=${frame.payload.size}",
                )
                if (!isSecretSessionKeyRequestFormatValid(request.payloadAscii)) {
                    PinpadTraceLog.command("21", "load secret session key invalid command format; responding EOT")
                    return newFrameResponseList(byteArrayOf(PINPADControl.EOT))
                }
                responses.sendAckBeforeSlowProcessing("21")
                if (keyLoadAuthorizer?.requiresAuthorization("21") == true) {
                    val started = keyLoadAuthorizer.requestAuthorization("21") { authorized ->
                        completeSecretKeyLoad(frame, authorized, masterKey = false)
                    }
                    if (!started) asyncResponseSender(byteArrayOf(PINPADControl.EOT))
                    return responses
                }
                responses += executeSecretKeyLoad(frame, masterKey = false)
            }
            "22",
            "23",
            "24" -> {
                PinpadTraceLog.command(request.commandId, "start secret MK/SK PIN entry")
                startSecretPinEntry(
                    commandId = request.commandId,
                    requestPayload = request.payloadAscii,
                    responses = responses,
                )
            }
            "60" -> {
                PinpadTraceLog.command("60", "start DUKPT PIN entry")
                startPinEntry(
                    commandId = "60",
                    requestPayload = request.payloadAscii,
                    dukpt = true,
                    responses = responses,
                )
            }
            "62" -> {
                PinpadTraceLog.command("62", "DUKPT amount authorization auto-confirm")
                responses += responseFrame(PINPADFrameType.Transaction, "63", "0")
                pendingFinalEot = true
            }
            "63",
            "71",
            "91",
            "99",
            "Z65",
            "Z67" -> {
                PinpadTraceLog.command(request.commandId, "response command received from host; acknowledged as compatibility no-op")
                pendingFinalEot = false
            }
            "70" -> {
                val masterSession = request.payloadAscii.startsWith('.')
                PinpadTraceLog.command("70", "start ${if (masterSession) "MK/SK" else "DUKPT"} PIN entry")
                startPinEntry(
                    commandId = "70",
                    requestPayload = request.payloadAscii,
                    dukpt = !masterSession,
                    responses = responses,
                )
            }
            "7G" -> {
                PinpadTraceLog.command("7G", "start MK/SK new-PIN entry and confirmation")
                startNewPinEntry(
                    commandId = "7G",
                    requestPayload = request.payloadAscii,
                    dukpt = false,
                    responses = responses,
                )
            }
            "7H" -> {
                PinpadTraceLog.command("7H", "start DUKPT new-PIN entry and confirmation")
                startNewPinEntry(
                    commandId = "7H",
                    requestPayload = request.payloadAscii,
                    dukpt = true,
                    responses = responses,
                )
            }
            "7A" -> {
                val format = request.payloadAscii.firstOrNull()
                val success = format != null && commandDevice?.setDukptKsnOutputFormat(format) != false
                PinpadTraceLog.command("7A", "set DUKPT KSN format=${format ?: "<missing>"} success=$success")
                pendingFinalEot = false
            }
            "90" -> {
                PinpadTraceLog.command("90", "load first DUKPT initial key")
                val result = commandDevice?.loadDukptInitialKey(0, request.payloadAscii)
                    ?: PinpadDeviceCommands.DukptLoadResult(false, '4')
                responses += responseFrame(PINPADFrameType.Transaction, "91", formatDukptLoadResult(result))
                pendingFinalEot = true
            }
            "94" -> {
                PinpadTraceLog.command("94", "load second DUKPT initial key")
                val result = commandDevice?.loadDukptInitialKey(1, request.payloadAscii)
                    ?: PinpadDeviceCommands.DukptLoadResult(false, '4')
                responses += responseFrame(PINPADFrameType.Transaction, "91", formatDukptLoadResult(result))
                pendingFinalEot = true
            }
            "96" -> {
                val keySet = request.payloadAscii.firstOrNull()
                val success = keySet != null && commandDevice?.selectDukptKeySet(keySet) != false
                PinpadTraceLog.command("96", "select DUKPT keyset=${keySet ?: "<missing>"} success=$success")
                pendingFinalEot = false
            }
            "98" -> {
                val keySet = request.payloadAscii.firstOrNull() ?: '0'
                val includeInfo = request.payloadAscii.getOrNull(1) == '1'
                val status = commandDevice?.dukptKeySetStatus(keySet, includeInfo)
                    ?: PinpadDeviceCommands.DukptStatus('0', null)
                PinpadTraceLog.command("98", "query DUKPT keyset=$keySet includeInfo=$includeInfo status=${status.status}")
                responses += responseFrame(PINPADFrameType.Transaction, "99", formatDukptStatus(status, includeInfo))
                pendingFinalEot = true
            }
            "1C" -> {
                PinpadTraceLog.command("1C", "query hardware capability")
                val payload = deviceInfoProvider.hardwareCapabilities()
                    .joinToString(separator = "") { "${PINPADControl.FS.toInt().toChar()}$it" }
                responses += responseFrame("1C", payload)
                pendingFinalEot = true
            }
            "1F" -> {
                PinpadTraceLog.command("1F", "query usable prompt table")
                val fs = PINPADControl.FS.toInt().toChar()
                responses += responseFrame("1F", "es${fs}en${fs}es")
                pendingFinalEot = true
            }
            "1M" -> {
                PinpadTraceLog.command("1M", "setup keypad beeper")
                val option = request.payloadAscii.firstOrNull()
                val success = when (option) {
                    '0' -> commandDevice?.setKeypadBeeper(false) == true
                    '1' -> commandDevice?.setKeypadBeeper(true) == true
                    else -> false
                }
                responses += responseFrame("1M", if (success) option.toString() else "0")
                pendingFinalEot = true
            }
            "1P" -> {
                PinpadTraceLog.command("1P", "control beeper")
                val beeperCommand = parseBeeperControl(request.payloadAscii)
                val success = beeperCommand != null &&
                    commandDevice?.controlBeeper(
                        count = beeperCommand.count,
                        durationUnits = beeperCommand.durationUnits,
                        intervalUnits = beeperCommand.intervalUnits,
                    ) == true
                responses += responseFrame("1P", if (success) "0" else "1")
                pendingFinalEot = true
            }
            "M03" -> {
                PinpadTraceLog.command("M03", "load permanent unit serial number")
                val slot = request.payloadAscii.getOrNull(11)?.digitToIntOrNull() ?: 0
                val pusn = request.payloadAscii.take(11)
                val status = commandDevice?.loadPusn(pusn, slot) ?: '3'
                responses += responseFrame("M03", status.toString())
                pendingFinalEot = true
            }
            "M04" -> {
                PinpadTraceLog.command("M04", "query permanent unit serial number")
                val slot = request.payloadAscii.firstOrNull()?.digitToIntOrNull() ?: 0
                responses += responseFrame("M04", commandDevice?.readPusn(slot) ?: "1")
                pendingFinalEot = true
            }
            "Q1" -> {
                PinpadTraceLog.command("Q1", "start MSR reading")
                commandDevice?.startMsrRead(
                    transactionDisplay = transactionDisplayContext,
                    onRead = ::sendMsrDataAsync,
                    onCancel = ::sendOperationCancelledEotAsync,
                )
                pendingFinalEot = false
            }
            "Q2" -> {
                PinpadTraceLog.command("Q2", "transaction completed")
                commandDevice?.completeTransaction()
                pendingFinalEot = false
            }
            "Q3" -> {
                PinpadTraceLog.command("Q3", "ignore card swipe")
                commandDevice?.ignoreCardSwipe()
                pendingFinalEot = false
            }
            "Q4" -> {
                val flag = request.payloadAscii.firstOrNull()
                val success = flag != null && commandDevice?.setMsrTrackMode(flag) != false
                PinpadTraceLog.command("Q4", "set MSR track mode flag=${flag ?: "<missing>"} success=$success")
                pendingFinalEot = false
            }
            "Q5" -> {
                val retryCount = request.payloadAscii.firstOrNull()?.digitToIntOrNull()
                val success = retryCount != null && commandDevice?.setMsrRetryCount(retryCount) != false
                PinpadTraceLog.command("Q5", "set MSR retry count=${retryCount ?: -1} success=$success")
                pendingFinalEot = false
            }
            "Q6" -> {
                val formatFlag = parseMsrOutputFormat(request.payloadAscii)
                PinpadTraceLog.command("Q6", "set MSR output format flag=${formatFlag ?: "<invalid>"}")
                if (formatFlag != null && commandDevice?.setMsrOutputFormat(formatFlag) != false) {
                    fallbackMsrOutputFormat = formatFlag
                    responses += codec.encode(frame)
                    pendingFinalEot = true
                } else {
                    responses += byteArrayOf(PINPADControl.EOT)
                    pendingFinalEot = false
                }
            }
            "Q7" -> {
                val format = commandDevice?.msrOutputFormat() ?: fallbackMsrOutputFormat
                PinpadTraceLog.command("Q7", "query MSR mode format=$format")
                responses += responseFrame(PINPADFrameType.Transaction, "Q7", format.toString())
                pendingFinalEot = true
            }
            "Q8" -> {
                PinpadTraceLog.command("Q8", "start PCD track-equivalent reading")
                responses.sendAckBeforeSlowProcessing("Q8")
                commandDevice?.startPcdTrackRead(::sendPcdDataAsync, ::sendOperationCancelledEotAsync)
                pendingFinalEot = false
            }
            "Q9" -> {
                PinpadTraceLog.command("Q9", "start dual MSR/PCD track reading")
                responses.sendAckBeforeSlowProcessing("Q9")
                commandDevice?.startDualTrackRead(
                    ::sendMsrDataAsync,
                    ::sendPcdDataAsync,
                    ::sendOperationCancelledEotAsync,
                )
                pendingFinalEot = false
            }
            "QA" -> {
                val flag = request.payloadAscii.firstOrNull()
                val success = flag != null && commandDevice?.setPcdTrackMode(flag) != false
                PinpadTraceLog.command("QA", "set PCD track mode flag=${flag ?: "<missing>"} success=$success")
                pendingFinalEot = false
            }
            "QB" -> {
                val flag = request.payloadAscii.firstOrNull()
                val status = if (flag != null) {
                    commandDevice?.setMsrAutoArm(flag, ::sendMsrDataAsync) ?: if (flag in setOf('0', '1')) '0' else '1'
                } else {
                    '1'
                }
                PinpadTraceLog.command("QB", "set MSR auto arm flag=${flag ?: "<missing>"} status=$status")
                responses += responseFrame(PINPADFrameType.Transaction, "QB", status.toString())
                pendingFinalEot = true
            }
            "QC" -> {
                val flag = request.payloadAscii.firstOrNull()
                val status = if (flag != null) {
                    commandDevice?.setPcdAutoArm(flag) ?: if (flag in setOf('0', '1')) '0' else '1'
                } else {
                    '1'
                }
                PinpadTraceLog.command("QC", "set PCD auto arm flag=${flag ?: "<missing>"} status=$status")
                responses += responseFrame(PINPADFrameType.Transaction, "QC", status.toString())
                pendingFinalEot = true
            }
            "QD" -> {
                val formatFlag = parseMsrOutputFormat(request.payloadAscii)
                PinpadTraceLog.command("QD", "set PCD output format flag=${formatFlag ?: "<invalid>"}")
                if (formatFlag != null && commandDevice?.setPcdOutputFormat(formatFlag) != false) {
                    responses += codec.encode(frame)
                    pendingFinalEot = true
                } else {
                    responses += byteArrayOf(PINPADControl.EOT)
                    pendingFinalEot = false
                }
            }
            "QF" -> {
                PinpadTraceLog.command("QF", "start MSR reading with JPEG prompt")
                commandDevice?.startMsrRead(
                    onRead = ::sendMsrDataAsync,
                    onCancel = ::sendOperationCancelledEotAsync,
                )
                pendingFinalEot = false
            }
            "QG" -> {
                PinpadTraceLog.command("QG", "start PCD track-equivalent reading with JPEG prompt")
                responses.sendAckBeforeSlowProcessing("QG")
                commandDevice?.startPcdTrackRead(::sendPcdDataAsync, ::sendOperationCancelledEotAsync)
                pendingFinalEot = false
            }
            "QH" -> {
                PinpadTraceLog.command("QH", "start dual MSR/PCD track reading with JPEG prompt")
                responses.sendAckBeforeSlowProcessing("QH")
                commandDevice?.startDualTrackRead(
                    ::sendMsrDataAsync,
                    ::sendPcdDataAsync,
                    ::sendOperationCancelledEotAsync,
                )
                pendingFinalEot = false
            }
            "QI" -> {
                val dol = request.payloadAscii.dropReadyControl()
                val payload = packetOrEmptyStatus(
                    commandDevice?.queryEmvTransactionData(dol).orEmpty(),
                    separator = FS_CHAR,
                )
                PinpadTraceLog.command("QI", "query contactless tag data dolChars=${dol.length} payloadChars=${payload.length}")
                responses += responseFrame(PINPADFrameType.Transaction, "QJ", payload)
                pendingFinalEot = true
            }
            "QK" -> {
                PinpadTraceLog.command(
                    "QK",
                    "start multiple interface detection transactionDisplay=${transactionDisplayContext != null}",
                )
                val result = commandDevice?.startMultiInterfaceDetection(
                    transactionDisplay = transactionDisplayContext,
                    onResult = ::sendMultiInterfaceDetectionAsync,
                )
                    ?: PinpadDeviceCommands.MultiInterfaceDetectionStartResult.ImmediateResponse(
                        PinpadDeviceCommands.MultiInterfaceDetectionResult.enableMsrFail(),
                    )
                when (result) {
                    PinpadDeviceCommands.MultiInterfaceDetectionStartResult.Started -> pendingFinalEot = false
                    is PinpadDeviceCommands.MultiInterfaceDetectionStartResult.ImmediateResponse -> {
                        responses += responseFrame(PINPADFrameType.Transaction, "QK", result.result.payload)
                        pendingFinalEot = true
                    }
                }
            }
            "I00" -> {
                val present = commandDevice?.primarySmartCardPresent() == true
                PinpadTraceLog.command("I00", "query primary smart card present=$present")
                responses += responseFrame(PINPADFrameType.Transaction, "I00", if (present) "F" else "0")
                pendingFinalEot = true
            }
            "I01" -> {
                PinpadTraceLog.command("I01", "primary smart card cold reset")
                responses += cpuCardResponseFrame(commandDevice?.primarySmartCardColdReset())
                pendingFinalEot = true
            }
            "I04" -> {
                PinpadTraceLog.command("I04", "primary smart card deactivate")
                responses += cpuCardResponseFrame(commandDevice?.deactivatePrimarySmartCard())
                pendingFinalEot = true
            }
            "I06" -> {
                PinpadTraceLog.command("I06", "primary smart card APDU chars=${request.payloadAscii.length}")
                responses += cpuCardResponseFrame(commandDevice?.exchangePrimarySmartCardApdu(request.payloadAscii))
                pendingFinalEot = true
            }
            "I11" -> {
                PinpadTraceLog.command("I11", "SAM smart card cold reset")
                responses += cpuCardResponseFrame(commandDevice?.samCardColdReset())
                pendingFinalEot = true
            }
            "I14" -> {
                PinpadTraceLog.command("I14", "SAM smart card deactivate")
                responses += cpuCardResponseFrame(commandDevice?.deactivateSamCard())
                pendingFinalEot = true
            }
            "I15" -> {
                PinpadTraceLog.command("I15", "SAM select interface code=${request.payloadAscii.firstOrNull() ?: "<missing>"}")
                responses += cpuCardResponseFrame(commandDevice?.selectSamInterface(request.payloadAscii.firstOrNull()))
                pendingFinalEot = true
            }
            "I16" -> {
                PinpadTraceLog.command("I16", "SAM smart card APDU chars=${request.payloadAscii.length}")
                responses += cpuCardResponseFrame(commandDevice?.exchangeSamCardApdu(request.payloadAscii))
                pendingFinalEot = true
            }
            in CPU_CARD_UNSUPPORTED_COMMANDS -> {
                PinpadTraceLog.command(request.commandId, "CPU card command unsupported")
                responses += cpuCardResponseFrame(commandDevice?.unsupportedCpuCardCommand(request.commandId))
                pendingFinalEot = true
            }
            in MIFARE_COMMANDS -> {
                PinpadTraceLog.command(request.commandId, "MIFARE command payloadChars=${request.payloadAscii.length}")
                val payload = commandDevice?.processMifareCommand(request.commandId, request.payloadAscii) ?: "13"
                responses += responseFrame(PINPADFrameType.Transaction, request.commandId, payload)
                pendingFinalEot = true
            }
            "B1" -> {
                val option = request.payloadAscii.firstOrNull()
                val status = option?.let { commandDevice?.setDisplayFontSize(it) ?: if (it in '0'..'5') '0' else '1' } ?: '1'
                PinpadTraceLog.command("B1", "set display font size option=${option ?: "<missing>"} status=$status")
                responses += responseFrame(PINPADFrameType.Transaction, "B2", status.toString())
                pendingFinalEot = true
            }
            "B3" -> {
                val foreground = request.payloadAscii.take(6)
                val background = request.payloadAscii.drop(6).take(6)
                val status = commandDevice?.setDisplayFontColor(foreground, background)
                    ?: if (foreground.isRgbHex() && background.isRgbHex()) '0' else '1'
                PinpadTraceLog.command(
                    "B3",
                    "set display font color foreground=$foreground background=$background status=$status",
                )
                responses += responseFrame(PINPADFrameType.Transaction, "B4", status.toString())
                pendingFinalEot = true
            }
            "BB" -> {
                val waitingTime = request.payloadAscii.take(3).toIntOrNull()
                val saverType = request.payloadAscii.drop(3).firstOrNull()
                val status = if (waitingTime != null && waitingTime in 5..999 && saverType == '1') '0' else '1'
                PinpadTraceLog.command("BB", "screen saver setting waitingTime=${waitingTime ?: -1} type=${saverType ?: "<missing>"} status=$status")
                responses += responseFrame(PINPADFrameType.Transaction, "BC", status.toString())
                pendingFinalEot = true
            }
            "BD" -> {
                val op = request.payloadAscii.firstOrNull()
                val status = if (op in setOf('0', '1')) '0' else '1'
                PinpadTraceLog.command("BD", "screen saver enable op=${op ?: "<missing>"} status=$status")
                responses += responseFrame(PINPADFrameType.Transaction, "BE", status.toString())
                pendingFinalEot = true
            }
            "BF" -> {
                val op = request.payloadAscii.firstOrNull()
                PinpadTraceLog.command("BF", "screen saver preview op=${op ?: "<missing>"}")
                pendingFinalEot = false
            }
            "Z0" -> {
                val line = request.payloadAscii.take(2)
                PinpadTraceLog.command("Z0", "move display cursor line=$line")
                if (line.length != 2 || line.any { !it.isDigit() }) {
                    return newFrameResponseList(byteArrayOf(PINPADControl.EOT))
                }
                pendingFinalEot = false
            }
            "Z2" -> {
                val result = applyZ2Prompt(frame.payload)
                PinpadTraceLog.command("Z2", "display prompt status=${result.status ?: "none"} chars=${result.displayText.length}")
                result.responsePayload?.let { responses += responseFrame(PINPADFrameType.Transaction, "Z2", it) }
                pendingFinalEot = false
            }
            "Z3" -> {
                val result = applyZ3Prompt(frame.payload)
                PinpadTraceLog.command("Z3", "display line prompts status=${result.status ?: "none"} chars=${result.displayText.length}")
                result.responsePayload?.let { responses += responseFrame(PINPADFrameType.Transaction, "Z3", it) }
                pendingFinalEot = false
            }
            "ZA" -> {
                val display = PinpadTransactionDisplay.parseZaPayload(request.payloadAscii)
                if (display == null) {
                    PinpadTraceLog.command("ZA", "transaction display invalid payloadChars=${request.payloadAscii.length}")
                    responses += byteArrayOf(PINPADControl.EOT)
                    pendingFinalEot = false
                } else {
                    transactionDisplayContext = display
                    PinpadTraceLog.command(
                        "ZA",
                        "transaction display set type=${display.transactionTypeCode} " +
                            "currency=${display.currencyCode} amountMinor=${display.amountMinor} " +
                            "customSymbol=${!display.currencySymbol.isNullOrBlank()}",
                    )
                    pendingFinalEot = false
                }
            }
            "Z1",
            "72" -> {
                PinpadTraceLog.command(request.commandId, "reset/cancel display state")
                transactionDisplayContext = null
                cancelActiveDataEntry(showCancelMessage = false)
                dataEntryPromptReady = false
                dataEntryPromptText = ""
                commandDevice?.clearTransactionState()
                commandDevice?.ignoreCardSwipe()
                commandDevice?.cancelPinEntry()
                commandDevice?.cancelMultiInterfaceDetection()
                PinpadDisplayController.showIdle()
                pendingFinalEot = false
            }
            "Z7" -> {
                val option = request.payloadAscii.firstOrNull()
                if (option !in setOf('0', '1')) {
                    return newFrameResponseList(byteArrayOf(PINPADControl.EOT))
                }
                cancelMessageDisplayEnabled = option != '1'
                PinpadTraceLog.command("Z7", "cancel message display enabled=$cancelMessageDisplayEnabled")
                pendingFinalEot = false
            }
            "Z8" -> {
                val prompt = frame.payload.toString(StandardCharsets.UTF_8)
                PinpadTraceLog.command("Z8", "set idle prompt chars=${prompt.length}")
                commandDevice?.setIdlePrompt(prompt)
                pendingFinalEot = false
            }
            "Z42" -> {
                val timeout = request.payloadAscii.toIntOrNull()
                PinpadTraceLog.command("Z42", "read key code timeout=${timeout ?: -1} promptReady=$dataEntryPromptReady")
                if (!dataEntryPromptReady || timeout == null || timeout !in 1..255) {
                    PinpadDisplayController.showIdle()
                    return newFrameResponseList(byteArrayOf(PINPADControl.EOT))
                }
                startReadKeyCode(timeout)
                pendingFinalEot = false
            }
            "Z50" -> {
                val entryRequest = parseStringEntryRequest(frame.payload)
                PinpadTraceLog.command(
                    "Z50",
                    "string entry parsed=${entryRequest != null} promptReady=$dataEntryPromptReady",
                )
                if (!dataEntryPromptReady || entryRequest == null) {
                    PinpadDisplayController.showIdle()
                    dataEntryPromptReady = false
                    return newFrameResponseList(byteArrayOf(PINPADControl.EOT))
                }
                startStringEntry(entryRequest)
                pendingFinalEot = false
            }
            "Z64" -> {
                val keyId = request.payloadAscii.firstOrNull()
                val kcv = keyId?.let { commandDevice?.masterKeyKcv(it) }
                PinpadTraceLog.command(
                    "Z64",
                    "query master key KCV keyId=${keyId ?: "<missing>"} found=${kcv != null}",
                )
                responses += responseFrame(
                    frameType = PINPADFrameType.Transaction,
                    commandId = "Z65",
                    payloadAscii = "${keyId ?: ""}${kcv ?: "?"}",
                )
                pendingFinalEot = true
            }
            "Z60" -> {
                val masterSession = isMasterSessionPinRequest("Z60", request.payloadAscii)
                PinpadTraceLog.command("Z60", "start ${if (masterSession) "MK/SK" else "DUKPT"} external-prompt PIN entry")
                startPinEntry(
                    commandId = "Z60",
                    requestPayload = request.payloadAscii,
                    dukpt = !masterSession,
                    responses = responses,
                )
            }
            "Z62" -> {
                val masterSession = isMasterSessionPinRequest("Z62", request.payloadAscii)
                PinpadTraceLog.command("Z62", "start ${if (masterSession) "MK/SK" else "DUKPT"} custom-prompt PIN entry")
                startPinEntry(
                    commandId = "Z62",
                    requestPayload = request.payloadAscii,
                    dukpt = !masterSession,
                    responses = responses,
                )
            }
            "Z66" -> {
                PinpadTraceLog.command("Z66", "calculate MAC")
                val result = commandDevice?.generateMac(request.payloadAscii)
                    ?: PinpadDeviceCommands.MacResult(false, "")
                responses += responseFrame(
                    frameType = PINPADFrameType.Transaction,
                    commandId = "Z67",
                    payloadAscii = if (result.success) "0${result.mac}" else "2",
                )
                pendingFinalEot = true
            }
            "J0" -> {
                PinpadTraceLog.command("J0", "initialize JPEG file table")
                val status = if (commandDevice?.initializeJpegTable() == true) "0" else "1"
                responses += responseFrame(PINPADFrameType.Transaction, "J0", status)
                pendingFinalEot = true
            }
            "J1" -> {
                val entries = commandDevice?.jpegTable().orEmpty()
                PinpadTraceLog.command("J1", "query JPEG file table count=${entries.size}")
                val payload = "1" + entries.joinToString(separator = FS_CHAR.toString()) {
                    "${if (it.selected) '1' else '0'}${it.name}"
                }
                responses += responseFrame(PINPADFrameType.Transaction, "J1", payload)
                pendingFinalEot = true
            }
            "J2" -> {
                val control = request.payloadAscii.firstOrNull() ?: ' '
                val names = splitJpegNames(request.payloadAscii.drop(1))
                val statuses = commandDevice?.selectJpegs(control, names) ?: listOf('1')
                PinpadTraceLog.command("J2", "select JPEG control=$control names=${names.size} statuses=$statuses")
                responses += responseFrame(PINPADFrameType.Transaction, "J2", statuses.joinToString(FS_CHAR.toString()))
                pendingFinalEot = true
            }
            "J3" -> {
                val names = splitJpegNames(request.payloadAscii)
                val statuses = commandDevice?.deleteJpegs(names) ?: listOf('1')
                PinpadTraceLog.command("J3", "delete JPEG names=${names.size} statuses=$statuses")
                responses += responseFrame(PINPADFrameType.Transaction, "J3", statuses.joinToString(FS_CHAR.toString()))
                pendingFinalEot = true
            }
            "J4" -> {
                val status = commandDevice?.downloadJpegPacket(request.payloadAscii) ?: 'D'
                PinpadTraceLog.command("J4", "download JPEG packet status=$status payloadChars=${request.payloadAscii.length}")
                responses += responseFrame(PINPADFrameType.Transaction, "J4", status.toString())
                pendingFinalEot = true
            }
            "J5" -> {
                val control = request.payloadAscii.firstOrNull()
                val packet = when (control) {
                    '0' -> commandDevice?.startJpegUpload(request.payloadAscii.drop(1).trimStart(FS_CHAR))
                    '1' -> commandDevice?.nextJpegUploadPacket()
                    else -> null
                }
                PinpadTraceLog.command("J5", "upload JPEG control=${control ?: "<missing>"} type=${packet?.type}")
                responses += responseFrame(PINPADFrameType.Transaction, "J5", packet?.payload() ?: "5000000")
                pendingFinalEot = true
            }
            "J6" -> {
                val started = commandDevice?.playSelectedJpegs() == true
                PinpadTraceLog.command("J6", "play selected JPEGs started=$started")
                pendingFinalEot = false
            }
            "J7" -> {
                PinpadTraceLog.command("J7", "set idle JPEG accepted without display name=${request.payloadAscii}")
                responses += responseFrame(PINPADFrameType.Transaction, "J7", "0")
                pendingFinalEot = true
            }
            "J8" -> {
                val op = request.payloadAscii.firstOrNull() ?: ' '
                PinpadTraceLog.command("J8", "idle JPEG enable accepted without display op=$op")
                responses += responseFrame(PINPADFrameType.Transaction, "J8", "0")
                pendingFinalEot = true
            }
            "J9" -> {
                val status = commandDevice?.showJpeg(request.payloadAscii) ?: '2'
                PinpadTraceLog.command("J9", "show JPEG status=$status")
                responses += responseFrame(PINPADFrameType.Transaction, "J9", status.toString())
                pendingFinalEot = true
            }
            "JA" -> {
                val status = commandDevice?.downloadBootLogoPacket(request.payloadAscii) ?: '6'
                PinpadTraceLog.command("JA", "download boot logo packet status=$status")
                responses += responseFrame(PINPADFrameType.Transaction, "JA", status.toString())
                pendingFinalEot = true
            }
            "M10" -> {
                PinpadTraceLog.command("M10", "initialize media file table")
                val status = if (commandDevice?.initializeMediaTable() == true) "0" else "1"
                responses += responseFrame(PINPADFrameType.Transaction, "M10", status)
                pendingFinalEot = true
            }
            "M11" -> {
                val entries = commandDevice?.mediaTable().orEmpty()
                val payload = buildString {
                    append('0')
                    entries.forEach { entry ->
                        append(FS_CHAR)
                        append(entry.type.protocolCode)
                        append("%010d".format(entry.sizeBytes))
                        append(entry.name)
                    }
                }
                PinpadTraceLog.command("M11", "query media file table count=${entries.size}")
                responses += responseFrame(PINPADFrameType.Transaction, "M11", payload)
                pendingFinalEot = true
            }
            "M12" -> {
                val status = commandDevice?.downloadMediaPacket(request.payloadAscii) ?: '6'
                PinpadTraceLog.command("M12", "download media packet status=$status payloadChars=${request.payloadAscii.length}")
                responses += responseFrame(PINPADFrameType.Transaction, "M12", status.toString())
                pendingFinalEot = true
            }
            "M13" -> {
                val control = request.payloadAscii.firstOrNull()
                val packet = when (control) {
                    '0' -> commandDevice?.startMediaUpload(request.payloadAscii.drop(1).trimStart(FS_CHAR))
                    '1' -> commandDevice?.nextMediaUploadPacket()
                    else -> null
                }
                PinpadTraceLog.command("M13", "upload media control=${control ?: "<missing>"} type=${packet?.type}")
                responses += responseFrame(PINPADFrameType.Transaction, "M13", packet?.payload() ?: "5000000000")
                pendingFinalEot = true
            }
            "M14" -> {
                val status = commandDevice?.playMedia(request.payloadAscii) ?: '2'
                PinpadTraceLog.command("M14", "play media status=$status name=${request.payloadAscii}")
                responses += responseFrame(PINPADFrameType.Transaction, "M14", status.toString())
                pendingFinalEot = true
            }
            "M15" -> {
                val status = commandDevice?.setMediaVolume(request.payloadAscii) ?: '2'
                PinpadTraceLog.command("M15", "set media volume status=$status value=${request.payloadAscii}")
                responses += responseFrame(PINPADFrameType.Transaction, "M15", status.toString())
                pendingFinalEot = true
            }
            "M16" -> {
                val names = request.payloadAscii
                    .split(FS_CHAR)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                val statuses = if (names.isEmpty()) {
                    listOf('1')
                } else {
                    commandDevice?.deleteMedia(names) ?: List(names.size) { '3' }
                }
                PinpadTraceLog.command("M16", "delete media names=${names.size} statuses=$statuses")
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "M16",
                    statuses.joinToString(FS_CHAR.toString()),
                )
                pendingFinalEot = true
            }
            "M17" -> {
                val fields = request.payloadAscii.split(FS_CHAR, limit = 2)
                val language = fields.firstOrNull().orEmpty()
                val text = fields.getOrNull(1)?.let { encoded ->
                    runCatching {
                        Base64.getDecoder().decode(encoded).toString(StandardCharsets.UTF_8)
                    }.getOrNull()
                }
                val status = if (language.isBlank() || text.isNullOrBlank()) {
                    '1'
                } else {
                    commandDevice?.speakText(language, text) ?: '2'
                }
                PinpadTraceLog.command(
                    "M17",
                    "text-to-speech language=$language chars=${text?.length ?: 0} status=$status",
                )
                responses += responseFrame(PINPADFrameType.Transaction, "M17", status.toString())
                pendingFinalEot = true
            }
            SignatureCaptureProtocol.REQUEST_COMMAND -> {
                val signatureRequest = SignatureCaptureProtocol.parseRequest(request.payloadAscii)
                PinpadTraceLog.command(
                    SignatureCaptureProtocol.REQUEST_COMMAND,
                    "capture request timeout=${signatureRequest?.timeoutSeconds} " +
                        "orientation=${signatureRequest?.orientation} format=${signatureRequest?.imageFormat}",
                )
                responses.sendAckBeforeSlowProcessing(SignatureCaptureProtocol.REQUEST_COMMAND)
                pendingFinalEot = false
                if (signatureRequest == null || visualOperationInProgress || packetTransferAwaitingAck) {
                    sendSignatureCaptureResult(SignatureCaptureResult.Error)
                } else {
                    visualOperationInProgress = true
                    val started = PinpadDisplayController.showSignatureCapture(
                        timeoutSeconds = signatureRequest.timeoutSeconds,
                        orientation = signatureRequest.orientation,
                        imageFormat = signatureRequest.imageFormat,
                    ) { result ->
                        visualOperationInProgress = false
                        sendSignatureCaptureResult(result)
                    }
                    if (!started) {
                        visualOperationInProgress = false
                        sendSignatureCaptureResult(SignatureCaptureResult.Error)
                    }
                }
            }
            CameraQrProtocol.PHOTO_REQUEST_COMMAND -> {
                val photoRequest = CameraQrProtocol.parsePhotoRequest(request.payloadAscii)
                PinpadTraceLog.command(
                    CameraQrProtocol.PHOTO_REQUEST_COMMAND,
                    "capture request timeout=${photoRequest?.timeoutSeconds} " +
                        "facing=${photoRequest?.facing} quality=${photoRequest?.jpegQuality}",
                )
                responses.sendAckBeforeSlowProcessing(CameraQrProtocol.PHOTO_REQUEST_COMMAND)
                pendingFinalEot = false
                if (photoRequest == null || visualOperationInProgress || packetTransferAwaitingAck) {
                    sendPhotoCaptureResult(PhotoCaptureResult.Error)
                } else {
                    visualOperationInProgress = true
                    val started = PinpadDisplayController.showPhotoCapture(
                        timeoutSeconds = photoRequest.timeoutSeconds,
                        facing = photoRequest.facing,
                        jpegQuality = photoRequest.jpegQuality,
                    ) { result ->
                        visualOperationInProgress = false
                        sendPhotoCaptureResult(result)
                    }
                    if (!started) {
                        visualOperationInProgress = false
                        sendPhotoCaptureResult(PhotoCaptureResult.Error)
                    }
                }
            }
            CameraQrProtocol.QR_DISPLAY_REQUEST_COMMAND -> {
                val qrRequest = CameraQrProtocol.parseQrDisplayRequest(request.payloadAscii)
                PinpadTraceLog.command(
                    CameraQrProtocol.QR_DISPLAY_REQUEST_COMMAND,
                    "display request timeout=${qrRequest?.timeoutSeconds} chars=${qrRequest?.value?.length ?: 0}",
                )
                responses.sendAckBeforeSlowProcessing(CameraQrProtocol.QR_DISPLAY_REQUEST_COMMAND)
                pendingFinalEot = false
                if (qrRequest == null || visualOperationInProgress || packetTransferAwaitingAck) {
                    sendQrDisplayResult(QrDisplayResult.Error)
                } else {
                    visualOperationInProgress = true
                    val started = PinpadDisplayController.showQrDisplay(
                        timeoutSeconds = qrRequest.timeoutSeconds,
                        value = qrRequest.value,
                    ) { result ->
                        visualOperationInProgress = false
                        sendQrDisplayResult(result)
                    }
                    if (!started) {
                        visualOperationInProgress = false
                        sendQrDisplayResult(QrDisplayResult.Error)
                    }
                }
            }
            CameraQrProtocol.QR_SCAN_REQUEST_COMMAND -> {
                val qrRequest = CameraQrProtocol.parseQrScanRequest(request.payloadAscii)
                PinpadTraceLog.command(
                    CameraQrProtocol.QR_SCAN_REQUEST_COMMAND,
                    "scan request timeout=${qrRequest?.timeoutSeconds} facing=${qrRequest?.facing}",
                )
                responses.sendAckBeforeSlowProcessing(CameraQrProtocol.QR_SCAN_REQUEST_COMMAND)
                pendingFinalEot = false
                if (qrRequest == null || visualOperationInProgress || packetTransferAwaitingAck) {
                    sendQrScanResult(QrScanResult.Error)
                } else {
                    visualOperationInProgress = true
                    val started = PinpadDisplayController.showQrScan(
                        timeoutSeconds = qrRequest.timeoutSeconds,
                        facing = qrRequest.facing,
                    ) { result ->
                        visualOperationInProgress = false
                        sendQrScanResult(result)
                    }
                    if (!started) {
                        visualOperationInProgress = false
                        sendQrScanResult(QrScanResult.Error)
                    }
                }
            }
            "T01" -> {
                PinpadTraceLog.command("T01", "load EMV terminal configuration")
                val result = commandDevice?.loadEmvTerminalConfiguration(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T02", emvSetupPayload(result))
                pendingFinalEot = true
            }
            "T03" -> {
                PinpadTraceLog.command("T03", "load EMV CAPK")
                val result = commandDevice?.loadEmvCapk(request.payloadAscii)
                val sequence = result?.sequence ?: request.payloadAscii.firstOrNull() ?: '0'
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "T04",
                    sequence.toString() + emvSetupPayload(result?.commandResult),
                )
                pendingFinalEot = true
            }
            "T05" -> {
                PinpadTraceLog.command("T05", "load EMV application configuration")
                val result = commandDevice?.loadEmvApplicationConfiguration(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T06", emvSetupPayload(result))
                pendingFinalEot = true
            }
            "T07" -> {
                PinpadTraceLog.command("T07", "load EMV data format table")
                val result = commandDevice?.loadEmvDataFormatTable(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T08", emvBasicPayload(result))
                pendingFinalEot = true
            }
            "T09" -> {
                val configType = request.payloadAscii.firstOrNull() ?: '0'
                val query = commandDevice?.queryEmvConfigIds(configType)
                PinpadTraceLog.command("T09", "query EMV config ids type=$configType count=${query?.ids?.size ?: 0}")
                responses += responseFrame(PINPADFrameType.Transaction, "T0A", formatConfigQuery(configType, query))
                pendingFinalEot = true
            }
            "T0B" -> {
                PinpadTraceLog.command("T0B", "delete EMV config")
                val result = commandDevice?.deleteEmvConfig(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T0C", formatConfigDelete(result))
                pendingFinalEot = true
            }
            "T51" -> {
                PinpadTraceLog.command("T51", "load PCD terminal configuration")
                val result = commandDevice?.loadEmvTerminalConfiguration(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T52", emvSetupPayload(result))
                pendingFinalEot = true
            }
            "T53" -> {
                PinpadTraceLog.command("T53", "load PCD CAPK")
                val result = commandDevice?.loadPcdCapk(request.payloadAscii)
                val sequence = result?.sequence ?: request.payloadAscii.firstOrNull() ?: '0'
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "T54",
                    sequence.toString() + emvSetupPayload(result?.commandResult),
                )
                pendingFinalEot = true
            }
            "T55" -> {
                PinpadTraceLog.command("T55", "load PCD application configuration")
                val result = commandDevice?.loadPcdApplicationConfiguration(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T56", emvSetupPayload(result))
                pendingFinalEot = true
            }
            "T59" -> {
                val configType = request.payloadAscii.firstOrNull() ?: '0'
                val query = commandDevice?.queryPcdConfigIds(configType)
                PinpadTraceLog.command("T59", "query PCD config ids type=$configType count=${query?.ids?.size ?: 0}")
                responses += responseFrame(PINPADFrameType.Transaction, "T5A", formatConfigQuery(configType, query))
                pendingFinalEot = true
            }
            "T5B" -> {
                PinpadTraceLog.command("T5B", "delete PCD config")
                val result = commandDevice?.deletePcdConfig(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T5C", formatConfigDelete(result))
                pendingFinalEot = true
            }
            "T5D" -> {
                PinpadTraceLog.command("T5D", "PCD housekeeping")
                val result = commandDevice?.housekeepPcdConfig(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T5E", formatPcdHousekeeping(result))
                pendingFinalEot = true
            }
            "T5F" -> {
                PinpadTraceLog.command("T5F", "load PCD DRL configuration")
                val result = commandDevice?.loadPcdDrlConfiguration(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T5G", emvBasicPayload(result))
                pendingFinalEot = true
            }
            "T5H" -> {
                PinpadTraceLog.command("T5H", "delete PCD DRL configuration")
                val result = commandDevice?.deletePcdDrlConfiguration()
                responses += responseFrame(PINPADFrameType.Transaction, "T5I", emvBasicPayload(result))
                pendingFinalEot = true
            }
            "T11",
            "T13" -> {
                PinpadTraceLog.command(
                    request.commandId,
                    "start contact application selection transactionDisplay=${transactionDisplayContext != null}",
                )
                val started = commandDevice?.startContactApplicationSelect(
                    transactionDisplay = transactionDisplayContext,
                    onResult = ::sendApplicationSelectAsync,
                ) ?: false
                if (!started) {
                    responses += responseFrame(PINPADFrameType.Transaction, "T12", "11")
                    pendingFinalEot = true
                } else {
                    pendingFinalEot = false
                }
            }
            "T15" -> {
                PinpadTraceLog.command("T15", "start contact transaction")
                val started = commandDevice?.startContactTransaction(
                    payload = request.payloadAscii,
                    sourceCommand = "T15",
                    onResult = ::sendContactTransactionAsync,
                ) ?: false
                if (!started) {
                    responses += responseFrame(PINPADFrameType.Transaction, "T16", formatTransactionResult(null))
                    pendingFinalEot = true
                } else {
                    pendingFinalEot = false
                }
            }
            "T19" -> {
                PinpadTraceLog.command("T19", "issuer script command")
                val result = commandDevice?.addIssuerScript(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T20", emvBasicPayload(result))
                pendingFinalEot = true
            }
            "T1C" -> {
                PinpadTraceLog.command("T1C", "cancel contact transaction")
                transactionDisplayContext = null
                pinManagementFlowActive = false
                commandDevice?.cancelContactTransaction()
                pendingFinalEot = false
            }
            "T1D" -> {
                PinpadTraceLog.command("T1D", "overwrite EMV runtime data")
                val result = commandDevice?.overwriteEmvTransactionData(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T1E", emvSetupPayload(result))
                pendingFinalEot = true
            }
            "T21" -> {
                PinpadTraceLog.command("T21", "query EMV transaction data")
                val payload = commandDevice?.queryEmvTransactionData(request.payloadAscii).orEmpty()
                responses += responseFrame(PINPADFrameType.Transaction, "T22", payload)
                pendingFinalEot = true
            }
            "T17" -> {
                PinpadTraceLog.command("T17", "complete contact online authorization")
                val result = commandDevice?.completeContactOnlineAuthorization(request.payloadAscii)
                if (result?.success == true) {
                    pendingFinalEot = false
                } else {
                    val responseCommand = if (pinManagementFlowActive) "T38" else "T16"
                    val responsePayload = if (pinManagementFlowActive) {
                        formatPinManagementResult(result)
                    } else {
                        formatTransactionResult(result)
                    }
                    pinManagementFlowActive = false
                    responses += responseFrame(PINPADFrameType.Transaction, responseCommand, responsePayload)
                    pendingFinalEot = true
                }
            }
            "T23" -> {
                PinpadTraceLog.command("T23", "clear EMV transaction log")
                commandDevice?.clearEmvTransactionLog()
                pendingFinalEot = false
            }
            "T25" -> {
                PinpadTraceLog.command("T25", "get EMV batch data")
                val payload = commandDevice?.nextEmvBatchData().orEmpty().ifEmpty { "0" }
                responses += responseFrame(PINPADFrameType.Transaction, "T26", payload)
                pendingFinalEot = true
            }
            "T27" -> {
                PinpadTraceLog.command("T27", "get online authorization data")
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "T28",
                    formatT28OnlineAuthorizationData(commandDevice?.onlineAuthorizationData().orEmpty()),
                )
                pendingFinalEot = true
            }
            "T2B" -> {
                PinpadTraceLog.command("T2B", "get online authorization data single packet")
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "T2C",
                    singleFinalPacket(formatOnlineAuthorizationData(commandDevice?.onlineAuthorizationData().orEmpty())),
                )
                pendingFinalEot = true
            }
            "T29" -> {
                PinpadTraceLog.command("T29", "get reversal data")
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "T2A",
                    commandDevice?.reversalData().orEmpty(),
                )
                pendingFinalEot = true
            }
            "T2H" -> {
                val requestType = request.payloadAscii.firstOrNull() ?: '1'
                val payload = when (requestType) {
                    '1' -> formatOnlineAuthorizationData(commandDevice?.onlineAuthorizationData().orEmpty())
                    '3' -> commandDevice?.nextEmvBatchData().orEmpty()
                    '4' -> commandDevice?.reversalData().orEmpty()
                    else -> ""
                }
                PinpadTraceLog.command("T2H", "get transaction data multipacket type=$requestType payloadChars=${payload.length}")
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "T2I",
                    if (requestType in setOf('1', '3', '4')) singleFinalPacket(payload) else "3",
                )
                pendingFinalEot = true
            }
            "T31" -> {
                PinpadTraceLog.command("T31", "extract contact card data")
                val started = commandDevice?.startContactCardDataRead(::sendApplicationSelectAsync) ?: false
                if (!started) {
                    responses += responseFrame(PINPADFrameType.Transaction, "T12", "11")
                    pendingFinalEot = true
                } else {
                    pendingFinalEot = false
                }
            }
            "T33" -> {
                PinpadTraceLog.command("T33", "start quick chip contact transaction")
                val started = commandDevice?.startContactTransaction(
                    request.payloadAscii,
                    "T33",
                ) { sendContactTransactionAsync(it, responseCommandId = "T34", quickChip = true) } ?: false
                if (!started) {
                    responses += responseFrame(PINPADFrameType.Transaction, "T34", "11")
                    pendingFinalEot = true
                } else {
                    pendingFinalEot = false
                }
            }
            "T34" -> {
                PinpadTraceLog.command("T34", "quick chip packet acknowledgement")
                pendingFinalEot = false
            }
            "T35" -> {
                PinpadTraceLog.command("T35", "start non-EMV contact transaction")
                val started = commandDevice?.startContactTransaction(
                    payload = request.payloadAscii,
                    sourceCommand = "T35",
                    onResult = ::sendContactTransactionAsync,
                ) ?: false
                if (!started) {
                    responses += responseFrame(PINPADFrameType.Transaction, "T16", formatTransactionResult(null))
                    pendingFinalEot = true
                } else {
                    pendingFinalEot = false
                }
            }
            "T37" -> {
                val operation = PinManagementPolicy.parseOperation(request.payloadAscii)
                when (operation) {
                    null -> {
                        PinpadTraceLog.command("T37", "invalid PIN management command format")
                        responses += responseFrame(PINPADFrameType.Transaction, "T38", "12")
                        pendingFinalEot = true
                    }
                    else -> {
                        PinpadTraceLog.command("T37", "start contact PIN management operation=$operation")
                        pinManagementFlowActive = true
                        val started = commandDevice?.startContactPinManagement(operation, ::sendPinManagementAsync) ?: false
                        if (!started) {
                            pinManagementFlowActive = false
                            val failure = PinpadContactEmvController.EmvCommandResult.failure('1', "00000000")
                            responses += responseFrame(
                                PINPADFrameType.Transaction,
                                "T38",
                                formatPinManagementResult(failure),
                            )
                            pendingFinalEot = true
                        } else {
                            pendingFinalEot = false
                        }
                    }
                }
            }
            "T38" -> {
                PinpadTraceLog.command("T38", "PIN management response command received from host")
                responses += responseFrame(PINPADFrameType.Transaction, "T38", "12")
                pendingFinalEot = true
            }
            "T3C" -> {
                PinpadTraceLog.command("T3C", "force complete non-EMV contact transaction")
                val result = commandDevice?.forceCompleteNonEmvTransaction()
                responses += responseFrame(PINPADFrameType.Transaction, "T3C", formatForceCompleteResult(result))
                pendingFinalEot = true
            }
            "T61" -> {
                PinpadTraceLog.command(
                    "T61",
                    "start contactless transaction transactionDisplay=${transactionDisplayContext != null}",
                )
                val started = commandDevice?.startContactlessTransaction(
                    payload = request.payloadAscii,
                    sourceCommand = "T61",
                    transactionDisplay = transactionDisplayContext,
                    onResult = ::sendContactlessTransactionAsync,
                ) ?: false
                if (!started) {
                    responses += responseFrame(PINPADFrameType.Transaction, "T62", formatContactlessTransactionResult(null))
                    pendingFinalEot = true
                } else {
                    pendingFinalEot = false
                }
            }
            "T63" -> {
                PinpadTraceLog.command("T63", "query contactless transaction data")
                val dol = request.payloadAscii.dropReadyControl()
                val payload = packetOrEmptyStatus(
                    commandDevice?.queryEmvTransactionData(dol).orEmpty(),
                    separator = SUB_CHAR,
                )
                responses += responseFrame(PINPADFrameType.Transaction, "T64", payload)
                pendingFinalEot = true
            }
            "T65" -> {
                PinpadTraceLog.command("T65", "query contactless online authorization data")
                val payload = packetOrEmptyStatus(
                    commandDevice?.onlineAuthorizationData().orEmpty(),
                    separator = FS_CHAR,
                )
                responses += responseFrame(PINPADFrameType.Transaction, "T66", payload)
                pendingFinalEot = true
            }
            "T67" -> {
                PinpadTraceLog.command("T67", "query contactless payment scheme")
                val aidData = commandDevice?.queryEmvTransactionData("4F").orEmpty()
                responses += responseFrame(PINPADFrameType.Transaction, "T68", paymentSchemeFromAidData(aidData))
                pendingFinalEot = true
            }
            "T6C" -> {
                PinpadTraceLog.command("T6C", "cancel contactless transaction")
                transactionDisplayContext = null
                commandDevice?.cancelContactTransaction()
                pendingFinalEot = false
            }
            "T71" -> {
                PinpadTraceLog.command("T71", "complete contactless online authorization")
                val result = commandDevice?.completeContactOnlineAuthorization(request.payloadAscii)
                if (result?.success == true) {
                    pendingFinalEot = false
                } else {
                    responses += responseFrame(PINPADFrameType.Transaction, "T62", formatContactlessTransactionResult(result))
                    pendingFinalEot = true
                }
            }
            "T72" -> {
                PinpadTraceLog.command("T72", "contactless compatibility acknowledgement")
                pendingFinalEot = false
            }
            "T73" -> {
                PinpadTraceLog.command("T73", "contactless issuer script command")
                val result = commandDevice?.addIssuerScript(request.payloadAscii)
                responses += responseFrame(PINPADFrameType.Transaction, "T74", emvBasicPayload(result))
                pendingFinalEot = true
            }
            "T75" -> {
                PinpadTraceLog.command("T75", "contactless revocation list setup")
                responses += responseFrame(PINPADFrameType.Transaction, "T76", setupListStatus(request.payloadAscii))
                pendingFinalEot = true
            }
            "T77" -> {
                PinpadTraceLog.command("T77", "contactless exception list setup")
                responses += responseFrame(PINPADFrameType.Transaction, "T78", setupListStatus(request.payloadAscii))
                pendingFinalEot = true
            }
            "T81" -> {
                PinpadTraceLog.command("T81", "start contactless EMV transaction")
                val started = commandDevice?.startContactlessTransaction(
                    payload = request.payloadAscii,
                    sourceCommand = "T81",
                    onResult = ::sendContactlessTransactionAsync,
                ) ?: false
                if (!started) {
                    responses += responseFrame(PINPADFrameType.Transaction, "T62", formatContactlessTransactionResult(null))
                    pendingFinalEot = true
                } else {
                    pendingFinalEot = false
                }
            }
            "T90" -> {
                PinpadTraceLog.command("T90", "apply A10 demo EMV configuration file")
                val fields = request.payloadAscii.split(FS_CHAR, limit = 3)
                val type = fields.getOrNull(0)?.singleOrNull()
                val fileName = fields.getOrNull(1)?.decodeBase64Utf8OrNull()
                val contents = fields.getOrNull(2)?.decodeBase64Utf8OrNull()
                val result = if (
                    type != null &&
                    !fileName.isNullOrBlank() &&
                    contents != null &&
                    contents.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_A10_DEMO_CONFIG_BYTES
                ) {
                    commandDevice?.applyA10DemoEmvConfiguration(type, fileName, contents)
                } else {
                    PinpadContactEmvController.EmvCommandResult.failure('2')
                }
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "T91",
                    emvSetupPayload(result) + FS_CHAR + fileName.orEmpty(),
                )
                pendingFinalEot = true
            }
            "T92" -> {
                val snapshot = commandDevice?.queryCurrentEmvConfiguration()
                PinpadTraceLog.command(
                    "T92",
                    "query all EMV configuration present=${snapshot != null}",
                )
                responses += responseFrame(
                    PINPADFrameType.Transaction,
                    "T93",
                    formatA10DemoConfigurationSnapshot(snapshot),
                )
                pendingFinalEot = true
            }
            "T94" -> {
                val result = if (request.payloadAscii == "ALL") {
                    commandDevice?.clearAllEmvConfiguration()
                } else {
                    PinpadContactEmvController.EmvCommandResult.failure('2')
                }
                PinpadTraceLog.command("T94", "clear all EMV configuration success=${result?.success == true}")
                responses += responseFrame(PINPADFrameType.Transaction, "T95", emvBasicPayload(result))
                pendingFinalEot = true
            }
            else -> {
                PinpadTraceLog.command(request.commandId, "unsupported message id; ignoring")
                responses.clear()
            }
        }
        applyFinalEotPolicy(request, responses)
        PinpadTraceLog.command(request.commandId, "responses=${responses.responseSummary()}")
        return responses
    }

    private fun executeClearKeyLoad(
        frame: PINPADFrame,
        keyId: Char?,
        keyPayload: String,
    ): ByteArray {
        val result = if (keyId != null && commandDevice != null) {
            commandDevice.loadMasterKey(keyId, keyPayload)
        } else {
            PinpadDeviceCommands.KeyLoadResult.Error('A')
        }
        return when (result) {
            PinpadDeviceCommands.KeyLoadResult.Success -> {
                PinpadTraceLog.command("02", "load key result=SUCCESS response=echo")
                codec.encode(frame)
            }
            is PinpadDeviceCommands.KeyLoadResult.Error -> {
                PinpadTraceLog.command("02", "load key result=ERROR code=${result.code}")
                responseFrame("02", "?${result.code}")
            }
        }
    }

    private fun executeSecretKeyLoad(frame: PINPADFrame, masterKey: Boolean): List<ByteArray> {
        val commandId = if (masterKey) "20" else "21"
        val result = if (masterKey) {
            commandDevice?.loadSecretMasterKey(frame.payloadAscii)
        } else {
            commandDevice?.loadSecretSessionKey(frame.payloadAscii)
        } ?: PinpadDeviceCommands.KeyLoadResult.Error('A')

        pendingFinalEot = false
        return when (result) {
            PinpadDeviceCommands.KeyLoadResult.Success -> {
                PinpadTraceLog.command(commandId, "load secret key result=SUCCESS response=echo")
                listOf(codec.encode(frame), byteArrayOf(PINPADControl.EOT))
            }
            is PinpadDeviceCommands.KeyLoadResult.Error -> {
                PinpadTraceLog.command(commandId, "load secret key result=ERROR code=${result.code}")
                listOf(byteArrayOf(PINPADControl.EOT))
            }
        }
    }

    private fun completeSecretKeyLoad(frame: PINPADFrame, authorized: Boolean, masterKey: Boolean) {
        val commandId = if (masterKey) "20" else "21"
        if (!authorized) {
            PinpadTraceLog.command(commandId, "load secret key authorization rejected")
            asyncResponseSender(byteArrayOf(PINPADControl.EOT))
            return
        }
        executeSecretKeyLoad(frame, masterKey).forEach(asyncResponseSender)
    }

    private fun onCommunicationTestEcho(frame: PINPADFrame): List<ByteArray> {
        val expectedPayload = byteArrayOf(PINPADControl.SUB) + "PROCESSING".ascii()
        val validEcho = frame.commandId == "09" && frame.payload.contentEquals(expectedPayload)
        communicationTestAwaitingEcho = false
        PinpadTraceLog.command("09", "communication test echo valid=$validEcho")
        return if (validEcho) {
            pendingFinalEot = shouldSendFinalEotAfterFrame(PINPADFrameType.Administration, "09")
            newFrameResponseList(responseFrame("09", "0"))
        } else {
            newFrameResponseList(byteArrayOf(PINPADControl.EOT))
        }
    }

    private fun applyFinalEotPolicy(request: PinpadCommandRequest, responses: List<ByteArray>) {
        if (responses.any { it.isControl(PINPADControl.EOT) }) {
            pendingFinalEot = false
            return
        }
        if (request.commandId == "09" && communicationTestAwaitingEcho) {
            pendingFinalEot = false
            return
        }
        if (responses.any { it.size > 1 }) {
            pendingFinalEot = shouldSendFinalEotAfterFrame(
                PINPADFrameType.valueOf(request.frameType),
                request.commandId,
            )
        }
    }

    private fun shouldSendFinalEotAfterFrame(frameType: PINPADFrameType, commandId: String): Boolean {
        return frameType == PINPADFrameType.Administration && commandId !in ADMINISTRATION_NO_FINAL_EOT_COMMANDS
    }

    private fun newFrameResponseList(vararg responses: ByteArray): MutableList<ByteArray> {
        return buildList {
            if (includeFrameAckInResponses) add(byteArrayOf(PINPADControl.ACK))
            addAll(responses)
        }.toMutableList()
    }

    private fun MutableList<ByteArray>.sendAckBeforeSlowProcessing(commandId: String) {
        val first = firstOrNull()
        if (first?.size != 1 || first[0] != PINPADControl.ACK) return
        removeAt(0)
        PinpadTraceLog.command(commandId, "sending ACK after command format validation")
        asyncResponseSender(byteArrayOf(PINPADControl.ACK))
    }

    private fun isLoadKeyRequestFormatValid(keyId: Char?, keyPayload: String): Boolean {
        if (keyId == null || !keyId.isMasterKeyId()) return false
        if (keyPayload.isBlank()) return false
        val material = keyPayload.substringBefore(FS_CHAR)
        if (material.isBlank()) return false
        val attribute = keyPayload.split(FS_CHAR).getOrNull(1) ?: return true
        return attribute.isEmpty() || isLoadKeyAttributeFormatValid(attribute)
    }

    private fun isSecretMasterKeyRequestFormatValid(payload: String): Boolean {
        val option = payload.firstOrNull() ?: return false
        val key = payload.drop(1)
        return option in SECRET_MASTER_KEY_OPTIONS &&
            key.length in SECRET_KEY_HEX_LENGTHS &&
            key.all { it.digitToIntOrNull(16) != null }
    }

    private fun isSecretSessionKeyRequestFormatValid(payload: String): Boolean {
        val keyId = payload.firstOrNull()?.digitToIntOrNull() ?: return false
        val key = payload.drop(1)
        return keyId in SECRET_KEY_ID_RANGE &&
            key.length in SECRET_KEY_HEX_LENGTHS &&
            key.all { it.digitToIntOrNull(16) != null }
    }

    private fun isLoadKeyAttributeFormatValid(attribute: String): Boolean {
        if (attribute.length < 3) return false
        val usage = attribute.take(2).uppercase()
        val mode = attribute[2].uppercaseChar()
        val algorithm = attribute.getOrNull(3)?.uppercaseChar() ?: DEFAULT_KEY_ALGORITHM
        return usage.matches(KEY_USAGE_REGEX) && mode in KEY_MODE_VALUES && algorithm in KEY_ALGORITHM_VALUES
    }

    private fun onControl(control: Byte): List<ByteArray> {
        if (control == PINPADControl.ACK && packetTransferAwaitingAck) {
            val responseCommand = packetTransferResponseCommand.orEmpty()
            val next = synchronized(packetTransferLock) {
                if (pendingPacketFrames.isEmpty()) {
                    packetTransferAwaitingAck = false
                    packetTransferResponseCommand = null
                    null
                } else {
                    pendingPacketFrames.removeFirst()
                }
            }
            return if (next == null) {
                PinpadTraceLog.command(responseCommand, "all packets acknowledged; sending EOT")
                listOf(byteArrayOf(PINPADControl.EOT))
            } else {
                PinpadTraceLog.command(
                    responseCommand,
                    "sending next packet remaining=${synchronized(packetTransferLock) { pendingPacketFrames.size }}",
                )
                listOf(next)
            }
        }
        if (control == PINPADControl.ACK && pendingFinalEot) {
            pendingFinalEot = false
            completedSerialPortChange = pendingSerialPortChange
            pendingSerialPortChange = null
            PinpadTraceLog.protocol("final EOT released after ACK")
            return listOf(byteArrayOf(PINPADControl.EOT))
        }
        if (control == PINPADControl.EOT) {
            pendingFinalEot = false
            communicationTestAwaitingEcho = false
            pendingSerialPortChange = null
            visualOperationInProgress = false
            clearPacketTransfer()
            PinpadDisplayController.dismissVisualOperation()
        }
        return emptyList()
    }

    private fun sendSignatureCaptureResult(result: SignatureCaptureResult) {
        val payloads = runCatching { SignatureCaptureProtocol.responsePayloads(result) }
            .getOrElse { error ->
                PinpadTraceLog.command(
                    SignatureCaptureProtocol.RESPONSE_COMMAND,
                    "packet creation failed=${error.message}",
                )
                SignatureCaptureProtocol.responsePayloads(SignatureCaptureResult.Error)
            }
        PinpadTraceLog.command(
            SignatureCaptureProtocol.RESPONSE_COMMAND,
            "capture result=${result::class.simpleName} packets=${payloads.size}",
        )
        sendPacketTransfer(SignatureCaptureProtocol.RESPONSE_COMMAND, payloads)
    }

    private fun sendPhotoCaptureResult(result: PhotoCaptureResult) {
        val payloads = runCatching { CameraQrProtocol.photoResponsePayloads(result) }
            .getOrElse { error ->
                PinpadTraceLog.command(
                    CameraQrProtocol.PHOTO_RESPONSE_COMMAND,
                    "packet creation failed=${error.message}",
                )
                CameraQrProtocol.photoResponsePayloads(PhotoCaptureResult.Error)
            }
        PinpadTraceLog.command(
            CameraQrProtocol.PHOTO_RESPONSE_COMMAND,
            "capture result=${result::class.simpleName} packets=${payloads.size}",
        )
        sendPacketTransfer(CameraQrProtocol.PHOTO_RESPONSE_COMMAND, payloads)
    }

    private fun sendQrDisplayResult(result: QrDisplayResult) {
        val payload = CameraQrProtocol.qrDisplayResponsePayload(result)
        PinpadTraceLog.command(
            CameraQrProtocol.QR_DISPLAY_RESPONSE_COMMAND,
            "display result=${result::class.simpleName}",
        )
        sendAsyncSingleResponse(CameraQrProtocol.QR_DISPLAY_RESPONSE_COMMAND, payload)
    }

    private fun sendQrScanResult(result: QrScanResult) {
        val payload = CameraQrProtocol.qrScanResponsePayload(result)
        PinpadTraceLog.command(
            CameraQrProtocol.QR_SCAN_RESPONSE_COMMAND,
            "scan result=${result::class.simpleName} payloadChars=${payload.length}",
        )
        sendAsyncSingleResponse(CameraQrProtocol.QR_SCAN_RESPONSE_COMMAND, payload)
    }

    private fun sendPacketTransfer(responseCommand: String, payloads: List<String>) {
        val frames = payloads.map { payload ->
            responseFrame(PINPADFrameType.Transaction, responseCommand, payload)
        }
        val first = synchronized(packetTransferLock) {
            pendingPacketFrames.clear()
            pendingPacketFrames.addAll(frames)
            packetTransferResponseCommand = responseCommand
            packetTransferAwaitingAck = pendingPacketFrames.isNotEmpty()
            if (pendingPacketFrames.isEmpty()) null else pendingPacketFrames.removeFirst()
        }
        first?.let(asyncResponseSender)
    }

    private fun sendAsyncSingleResponse(responseCommand: String, payload: String) {
        pendingFinalEot = true
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, responseCommand, payload))
    }

    private fun clearPacketTransfer() {
        synchronized(packetTransferLock) {
            pendingPacketFrames.clear()
            packetTransferAwaitingAck = false
            packetTransferResponseCommand = null
        }
    }

    private fun responseFrame(commandId: String, payloadAscii: String): ByteArray {
        return responseFrame(PINPADFrameType.Administration, commandId, payloadAscii)
    }

    private fun responseFrame(frameType: PINPADFrameType, commandId: String, payloadAscii: String): ByteArray {
        return responseFrame(
            PinpadCommandResponse(
                commandId = commandId,
                frameType = frameType.name,
                payloadAscii = payloadAscii,
            ),
        )
    }

    private fun cpuCardResponseFrame(result: PinpadDeviceCommands.CpuCardResult?): ByteArray {
        return when (result) {
            is PinpadDeviceCommands.CpuCardResult.Response -> responseFrame(
                PINPADFrameType.Transaction,
                result.commandId,
                result.payload,
            )
            is PinpadDeviceCommands.CpuCardResult.Error -> responseFrame(
                PINPADFrameType.Transaction,
                "I0F",
                result.code,
            )
            null -> responseFrame(PINPADFrameType.Transaction, "I0F", "0A")
        }
    }

    private fun sendMsrDataAsync(data: MsrTrackData) {
        val payload = formatMsrData(data)
        PinpadTraceLog.command(
            "81",
            "send MSR data trackMode=${data.trackMode} format=${data.outputFormat} " +
                "t1=${data.track1.length} t2=${data.track2.length} t3=${data.track3.length}",
        )
        pendingFinalEot = false
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, "81", payload))
    }

    private fun sendPcdDataAsync(data: MsrTrackData) {
        val payload = formatMsrData(data)
        PinpadTraceLog.command(
            "83",
            "send PCD track-equivalent data trackMode=${data.trackMode} format=${data.outputFormat} " +
                "t1=${data.track1.length} t2=${data.track2.length} t3=${data.track3.length}",
        )
        pendingFinalEot = false
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, "83", payload))
    }

    private fun sendMultiInterfaceDetectionAsync(result: PinpadDeviceCommands.MultiInterfaceDetectionResult) {
        PinpadTraceLog.command("QK", "send multiple interface detection result=${result.payload}")
        pendingFinalEot = false
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, "QK", result.payload))
    }

    private fun sendOperationCancelledEotAsync() {
        PinpadTraceLog.command("CANCEL", "send operation cancelled EOT")
        pendingFinalEot = false
        asyncResponseSender(byteArrayOf(PINPADControl.EOT))
    }

    private fun sendApplicationSelectAsync(payload: String) {
        PinpadTraceLog.command("T12", "send application select result payloadChars=${payload.length}")
        pendingFinalEot = false
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, "T12", payload))
    }

    private fun sendContactTransactionAsync(
        result: PinpadContactEmvController.EmvCommandResult,
        responseCommandId: String = "T16",
        quickChip: Boolean = false,
    ) {
        val payload = if (quickChip) {
            formatQuickChipResult(result, commandDevice?.onlineAuthorizationData().orEmpty())
        } else {
            formatTransactionResult(result)
        }
        PinpadTraceLog.command(responseCommandId, "send contact transaction result payloadChars=${payload.length}")
        pendingFinalEot = false
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, responseCommandId, payload))
    }

    private fun sendContactlessTransactionAsync(result: PinpadContactEmvController.EmvCommandResult) {
        val payload = formatContactlessTransactionResult(result)
        PinpadTraceLog.command("T62", "send contactless transaction result payloadChars=${payload.length}")
        pendingFinalEot = false
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, "T62", payload))
    }

    /**
     * Sends an asynchronous T38 result for the active T37 change-PIN lifecycle.
     *
     * @param result intermediate A1 or final V0/V1 PIN-management result.
     */
    private fun sendPinManagementAsync(result: PinpadContactEmvController.EmvCommandResult) {
        val payload = formatPinManagementResult(result)
        if (!result.success || result.transactionCode != PIN_MANAGEMENT_ONLINE_REQUEST) {
            pinManagementFlowActive = false
        }
        PinpadTraceLog.command("T38", "send PIN management result payloadChars=${payload.length}")
        pendingFinalEot = false
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, "T38", payload))
    }

    private fun sendPinEntryAsync(result: PinpadDeviceCommands.PinEntryResult) {
        when (result) {
            PinpadDeviceCommands.PinEntryResult.Eot -> {
                PinpadTraceLog.command("71", "send PIN entry EOT")
                pendingFinalEot = false
                asyncResponseSender(byteArrayOf(PINPADControl.EOT))
            }
            is PinpadDeviceCommands.PinEntryResult.Response -> {
                PinpadTraceLog.command("71", "send PIN entry response payloadChars=${result.payload.length}")
                pendingFinalEot = false
                asyncResponseSender(responseFrame(PINPADFrameType.Transaction, "71", result.payload))
            }
        }
    }

    private fun startPinEntry(
        commandId: String,
        requestPayload: String,
        dukpt: Boolean,
        responses: MutableList<ByteArray>,
    ) {
        val result = if (commandDevice == null) {
            PinpadDeviceCommands.PinEntryStartResult.ImmediateResponse(if (dukpt) "A" else "9")
        } else if (dukpt) {
            commandDevice.startDukptPinEntry(commandId, requestPayload, ::sendPinEntryAsync)
        } else {
            commandDevice.startMasterSessionPinEntry(commandId, requestPayload, ::sendPinEntryAsync)
        }
        when (result) {
            PinpadDeviceCommands.PinEntryStartResult.Started -> pendingFinalEot = false
            is PinpadDeviceCommands.PinEntryStartResult.ImmediateResponse -> {
                responses += responseFrame(PINPADFrameType.Transaction, "71", result.payload)
                pendingFinalEot = true
            }
        }
    }

    /**
     * Starts the two-step secure new-PIN capture for MK/SK or DUKPT protocol commands.
     *
     * @param commandId source request command (`7G` or `7H`).
     * @param requestPayload raw ASCII command payload.
     * @param dukpt selects DUKPT when true and MK/SK when false.
     * @param responses receives an immediate framed error when capture cannot start.
     */
    private fun startNewPinEntry(
        commandId: String,
        requestPayload: String,
        dukpt: Boolean,
        responses: MutableList<ByteArray>,
    ) {
        val result = if (commandDevice == null) {
            PinpadDeviceCommands.PinEntryStartResult.ImmediateResponse(if (dukpt) "A" else "9")
        } else if (dukpt) {
            commandDevice.startDukptNewPinEntry(commandId, requestPayload, ::sendPinEntryAsync)
        } else {
            commandDevice.startMasterSessionNewPinEntry(commandId, requestPayload, ::sendPinEntryAsync)
        }
        when (result) {
            PinpadDeviceCommands.PinEntryStartResult.Started -> pendingFinalEot = false
            is PinpadDeviceCommands.PinEntryStartResult.ImmediateResponse -> {
                responses += responseFrame(PINPADFrameType.Transaction, "71", result.payload)
                pendingFinalEot = true
            }
        }
    }

    private fun startSecretPinEntry(
        commandId: String,
        requestPayload: String,
        responses: MutableList<ByteArray>,
    ) {
        val result = commandDevice?.startSecretPinEntry(commandId, requestPayload, ::sendPinEntryAsync)
            ?: PinpadDeviceCommands.PinEntryStartResult.ImmediateResponse("9")
        when (result) {
            PinpadDeviceCommands.PinEntryStartResult.Started -> pendingFinalEot = false
            is PinpadDeviceCommands.PinEntryStartResult.ImmediateResponse -> {
                responses += responseFrame(PINPADFrameType.Transaction, "71", result.payload)
                pendingFinalEot = false
            }
        }
    }

    private fun applyZ2Prompt(payload: ByteArray): PromptDisplayResult {
        if (payload.firstOrNull() == PINPADControl.FS) {
            dataEntryPromptReady = false
            PinpadDisplayController.showIdle()
            return PromptDisplayResult(status = '4', responsePayload = "4")
        }
        val op = payload.firstOrNull()
        if (op == PINPADControl.GS || op == RS_BYTE) {
            val id = payload.copyOfRange(1, payload.size).toAscii().filter(Char::isDigit).take(3)
            val status = if (id.length == 3) '0' else '1'
            val text = if (status == '0') fixedPromptText(id, pinPrompt = op == RS_BYTE) else ""
            applyPromptDisplay(text, ready = status == '0')
            return PromptDisplayResult(status = status, displayText = text, responsePayload = status.toString())
        }
        val text = decodePromptText(payload)
        applyPromptDisplay(text, ready = text.isNotBlank())
        return PromptDisplayResult(displayText = text)
    }

    private fun applyZ3Prompt(payload: ByteArray): PromptDisplayResult {
        if (payload.firstOrNull() == PINPADControl.FS) {
            dataEntryPromptReady = false
            PinpadDisplayController.showIdle()
            return PromptDisplayResult(status = '4', responsePayload = "4")
        }
        val op = payload.firstOrNull()
        if (op == PINPADControl.GS || op == RS_BYTE) {
            val ids = payload.copyOfRange(1, payload.size)
                .split(PINPADControl.FS)
                .map { it.toAscii().filter(Char::isDigit).take(3) }
                .filter { it.length == 3 }
            val status = if (ids.isNotEmpty()) '0' else '1'
            val text = ids.joinToString(separator = "\n") { fixedPromptText(it, pinPrompt = op == RS_BYTE) }
            applyPromptDisplay(text, ready = status == '0')
            return PromptDisplayResult(status = status, displayText = text, responsePayload = status.toString())
        }
        val count = payload.firstOrNull()?.toInt()?.toChar()?.digitToIntOrNull()
        if (count == null || count !in 1..7) {
            dataEntryPromptReady = false
            PinpadDisplayController.showIdle()
            return PromptDisplayResult(status = '4', responsePayload = "4")
        }
        val offset = if (payload.getOrNull(1) == PINPADControl.SUB) 2 else 1
        val prompts = payload.copyOfRange(offset, payload.size)
            .split(PINPADControl.FS)
            .take(count)
            .map(::decodePromptText)
            .filter { it.isNotBlank() }
        val text = prompts.joinToString(separator = "\n")
        applyPromptDisplay(text, ready = text.isNotBlank())
        return PromptDisplayResult(displayText = text)
    }

    private fun applyPromptDisplay(text: String, ready: Boolean) {
        dataEntryPromptText = text
        dataEntryPromptReady = ready
        if (text.isBlank()) {
            PinpadDisplayController.showIdle()
        } else {
            PinpadDisplayController.showMessage(text)
        }
    }

    private fun startReadKeyCode(timeoutSeconds: Int) {
        synchronized(dataEntryLock) {
            clearActiveDataEntryLocked()
            activeDataEntry = ActiveDataEntry.ReadKeyCode(timeoutSeconds, allowDigits = dataEntryPromptReady)
            scheduleDataEntryTimeoutLocked(timeoutSeconds)
        }
    }

    private fun startStringEntry(request: StringEntryRequest) {
        synchronized(dataEntryLock) {
            clearActiveDataEntryLocked()
            activeDataEntry = ActiveDataEntry.StringEntry(
                timeoutSeconds = request.timeoutSeconds,
                maxEntry = request.maxEntry,
                echoMode = request.echoMode,
                prompt = dataEntryPromptText,
            )
            scheduleDataEntryTimeoutLocked(request.timeoutSeconds)
        }
        PinpadDisplayController.showTextEntry(dataEntryPromptText, "", request.echoMode)
    }

    private fun handleReadKeyCodeKey(entry: ActiveDataEntry.ReadKeyCode, key: PinpadKeypadKey) {
        val code = key.z43Code(entry.allowDigits) ?: return
        completeDataEntryResponse("Z43", code.toString(), showIdle = true)
    }

    private fun handleStringEntryKey(entry: ActiveDataEntry.StringEntry, key: PinpadKeypadKey) {
        when (key) {
            PinpadKeypadKey.Enter -> completeDataEntryResponse("Z51", entry.buffer.toString(), showIdle = true)
            PinpadKeypadKey.Clear -> {
                synchronized(dataEntryLock) {
                    val current = activeDataEntry
                    if (current === entry) {
                        entry.buffer.clear()
                        scheduleDataEntryTimeoutLocked(entry.timeoutSeconds)
                    }
                }
                PinpadDisplayController.updateTextEntry("")
            }
            PinpadKeypadKey.Cancel -> cancelActiveDataEntry(showCancelMessage = cancelMessageDisplayEnabled)
            else -> {
                val char = key.z50Char() ?: return
                var accepted = false
                synchronized(dataEntryLock) {
                    val current = activeDataEntry
                    if (current === entry && entry.buffer.length < entry.maxEntry) {
                        entry.buffer.append(char)
                        accepted = true
                        scheduleDataEntryTimeoutLocked(entry.timeoutSeconds)
                    }
                }
                if (accepted) {
                    PinpadDisplayController.updateTextEntry(entry.buffer.toString())
                } else {
                    commandDevice?.controlBeeper(count = 1, durationUnits = 8, intervalUnits = 0)
                }
            }
        }
    }

    private fun cancelActiveDataEntry(showCancelMessage: Boolean): Boolean {
        val hadEntry = synchronized(dataEntryLock) {
            val present = activeDataEntry != null
            clearActiveDataEntryLocked()
            present
        }
        if (!hadEntry) return false
        dataEntryPromptReady = false
        dataEntryPromptText = ""
        if (showCancelMessage) {
            PinpadDisplayController.showOperationCancelledThenIdle()
        } else {
            PinpadDisplayController.showIdle()
        }
        asyncResponseSender(byteArrayOf(PINPADControl.EOT))
        return true
    }

    private fun completeDataEntryResponse(commandId: String, payload: String, showIdle: Boolean) {
        synchronized(dataEntryLock) { clearActiveDataEntryLocked() }
        dataEntryPromptReady = false
        dataEntryPromptText = ""
        pendingFinalEot = false
        if (showIdle) PinpadDisplayController.showIdle()
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, commandId, payload))
    }

    private fun scheduleDataEntryTimeoutLocked(timeoutSeconds: Int) {
        dataEntryTimeout?.cancel(false)
        dataEntryTimeout = dataEntryScheduler.schedule(
            ::onDataEntryTimeout,
            timeoutSeconds.coerceIn(1, 255).toLong(),
            TimeUnit.SECONDS,
        )
    }

    private fun onDataEntryTimeout() {
        val responseCommand = synchronized(dataEntryLock) {
            val command = when (activeDataEntry) {
                is ActiveDataEntry.ReadKeyCode -> "Z43"
                is ActiveDataEntry.StringEntry -> "Z51"
                null -> null
            }
            clearActiveDataEntryLocked()
            command
        } ?: return
        dataEntryPromptReady = false
        dataEntryPromptText = ""
        PinpadDisplayController.showIdle()
        pendingFinalEot = false
        asyncResponseSender(responseFrame(PINPADFrameType.Transaction, responseCommand, "?"))
    }

    private fun clearActiveDataEntryLocked() {
        dataEntryTimeout?.cancel(false)
        dataEntryTimeout = null
        activeDataEntry = null
    }

    private fun parseStringEntryRequest(payload: ByteArray): StringEntryRequest? {
        if (payload.size < 4) return null
        val echo = when (payload[0].toInt().toChar()) {
            '0' -> TextEntryEchoMode.Masked
            '1' -> TextEntryEchoMode.Plain
            '2' -> TextEntryEchoMode.Hidden
            else -> return null
        }
        val body = payload.toAscii()
        val timeout = body.drop(1).take(3).toIntOrNull()?.takeIf { it in 1..255 } ?: return null
        val afterTimeout = payload.copyOfRange(4, payload.size)
        val maxEntryChars = afterTimeout.takeWhile { it.isAsciiDigit() }.toByteArray().toAscii()
        val maxEntry = if (maxEntryChars.isBlank()) 49 else maxEntryChars.toIntOrNull() ?: return null
        if (maxEntry !in 0..49) return null
        return StringEntryRequest(echo, timeout, maxEntry)
    }

    private fun decodePromptText(payload: ByteArray): String {
        val out = mutableListOf<Byte>()
        var index = 0
        while (index < payload.size) {
            when (payload[index]) {
                PINPADControl.SUB -> index += 1
                VT_BYTE -> index += 3
                INV_ON_BYTE,
                INV_OFF_BYTE -> index += 1
                US_BYTE -> index += 2
                else -> {
                    out += payload[index]
                    index += 1
                }
            }
        }
        return out.toByteArray().toString(StandardCharsets.UTF_8).trim()
    }

    private fun fixedPromptText(id: String, pinPrompt: Boolean): String {
        return if (pinPrompt) {
            PIN_PROMPTS[id] ?: "ENTER PIN"
        } else {
            DATA_ENTRY_PROMPTS[id] ?: "PROMPT $id"
        }
    }

    private fun PinpadKeypadKey.z43Code(allowDigits: Boolean): Char? {
        return when (this) {
            PinpadKeypadKey.Digit0 -> if (allowDigits) '0' else null
            PinpadKeypadKey.Digit1 -> if (allowDigits) '1' else null
            PinpadKeypadKey.Digit2 -> if (allowDigits) '2' else null
            PinpadKeypadKey.Digit3 -> if (allowDigits) '3' else null
            PinpadKeypadKey.Digit4 -> if (allowDigits) '4' else null
            PinpadKeypadKey.Digit5 -> if (allowDigits) '5' else null
            PinpadKeypadKey.Digit6 -> if (allowDigits) '6' else null
            PinpadKeypadKey.Digit7 -> if (allowDigits) '7' else null
            PinpadKeypadKey.Digit8 -> if (allowDigits) '8' else null
            PinpadKeypadKey.Digit9 -> if (allowDigits) '9' else null
            PinpadKeypadKey.Function1 -> 'A'
            PinpadKeypadKey.Function2 -> 'B'
            PinpadKeypadKey.Function3 -> 'C'
            PinpadKeypadKey.Cancel -> '*'
            PinpadKeypadKey.Clear -> '/'
            PinpadKeypadKey.Enter -> '#'
            else -> null
        }
    }

    private fun PinpadKeypadKey.z50Char(): Char? {
        return when (this) {
            PinpadKeypadKey.Digit0 -> '0'
            PinpadKeypadKey.Digit1 -> '1'
            PinpadKeypadKey.Digit2 -> '2'
            PinpadKeypadKey.Digit3 -> '3'
            PinpadKeypadKey.Digit4 -> '4'
            PinpadKeypadKey.Digit5 -> '5'
            PinpadKeypadKey.Digit6 -> '6'
            PinpadKeypadKey.Digit7 -> '7'
            PinpadKeypadKey.Digit8 -> '8'
            PinpadKeypadKey.Digit9 -> '9'
            PinpadKeypadKey.Period -> '.'
            PinpadKeypadKey.Space -> ' '
            else -> null
        }
    }

    private fun ByteArray.split(separator: Byte): List<ByteArray> {
        val fields = mutableListOf<ByteArray>()
        var start = 0
        for (index in indices) {
            if (this[index] == separator) {
                fields += copyOfRange(start, index)
                start = index + 1
            }
        }
        fields += copyOfRange(start, size)
        return fields
    }

    private fun ByteArray.toAscii(): String = toString(StandardCharsets.US_ASCII)

    private fun Byte.isAsciiDigit(): Boolean = toInt().toChar() in '0'..'9'

    private fun formatMsrData(data: MsrTrackData): String {
        val sentinelMode = data.outputFormat == '2'
        val track2 = data.track2.formatTrack(2, sentinelMode)
        if (data.trackMode == '0') return ".$track2"
        val fs = PINPADControl.FS.toInt().toChar()
        val track1 = data.track1.formatTrack(1, sentinelMode)
        val track3 = data.track3.formatTrack(3, sentinelMode)
        return ".$track1$fs$track2$fs$track3"
    }

    private fun String.formatTrack(track: Int, sentinelMode: Boolean): String {
        val trimmed = trim()
        if (trimmed.isEmpty()) return ""
        return if (sentinelMode) {
            trimmed.ensureSentinels(track)
        } else {
            trimmed.stripSentinels()
        }
    }

    private fun String.ensureSentinels(track: Int): String {
        val start = if (track == 1) '%' else ';'
        val withStart = if (startsWith(start)) this else "$start$this"
        return if (withStart.endsWith("?")) withStart else "$withStart?"
    }

    private fun String.stripSentinels(): String {
        return trimStart('%', ';').trimEnd('?')
    }

    private fun String.isRgbHex(): Boolean {
        return length == 6 && all { it.digitToIntOrNull(16) != null }
    }

    private fun parseMsrOutputFormat(payloadAscii: String): Char? {
        if (payloadAscii.length != 2 || payloadAscii.first() != '.') return null
        return payloadAscii[1].takeIf { it == '0' || it == '2' }
    }

    private fun responseFrame(response: PinpadCommandResponse): ByteArray {
        return codec.encode(
            PINPADFrame(
                frameType = PINPADFrameType.valueOf(response.frameType),
                commandId = response.commandId,
                payload = response.payloadAscii.ascii(),
            ),
        )
    }

    private fun emvSetupPayload(result: PinpadContactEmvController.EmvCommandResult?): String {
        return if (result?.success == true) {
            "0"
        } else {
            "1${result?.reason ?: '1'}${result?.errorMessage.orEmpty()}"
        }
    }

    private fun emvBasicPayload(result: PinpadContactEmvController.EmvCommandResult?): String {
        return if (result?.success == true) "0" else "1${result?.reason ?: '1'}${result?.errorMessage.orEmpty()}"
    }

    private fun formatConfigQuery(
        requestedType: Char,
        query: PinpadContactEmvController.EmvConfigQuery?,
    ): String {
        val configType = query?.configType ?: requestedType
        val status = query?.status ?: '1'
        val ids = query?.ids.orEmpty()
        if (status != '0' || ids.isEmpty()) return "$configType$status"
        return "$configType$status${PINPADControl.SUB.toInt().toChar()}" +
            ids.joinToString(separator = PINPADControl.FS.toInt().toChar().toString())
    }

    private fun formatConfigDelete(result: PinpadContactEmvController.EmvConfigDelete?): String {
        val configType = result?.configType ?: '0'
        val status = result?.status ?: '1'
        val deleted = result?.deleted.orEmpty()
        if (status != '0' || deleted.isEmpty()) return "$configType$status"
        return "$configType$status${PINPADControl.SUB.toInt().toChar()}" +
            deleted.joinToString(separator = PINPADControl.FS.toInt().toChar().toString()) { if (it) "0" else "1" }
    }

    private fun formatA10DemoConfigurationSnapshot(
        snapshot: PinpadEmvConfigStore.EmvConfigurationSnapshot?,
    ): String {
        if (snapshot == null) return "1"
        val json = JSONObject()
            .put("terminalConfigurationPresent", snapshot.terminalConfigurationPresent)
            .put("terminalConfigurationBytes", snapshot.terminalConfigurationBytes)
            .put("dataFormatTags", JSONArray(snapshot.dataFormatTags))
            .put("capkIds", JSONArray(snapshot.capkIds))
            .put("contactAidIds", JSONArray(snapshot.contactAidIds))
            .put("contactlessAidIds", JSONArray(snapshot.contactlessAidIds))
            .put("contactlessDrlIds", JSONArray(snapshot.contactlessDrlIds))
            .toString()
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        return "0$FS_CHAR$encoded"
    }

    private fun formatTransactionResult(result: PinpadContactEmvController.EmvCommandResult?): String {
        return if (result?.success == true) {
            "0${result.transactionCode}${result.reversalNeed}"
        } else {
            "1${result?.reason ?: '1'}${result?.errorMessage.orEmpty()}"
        }
    }

    private fun formatForceCompleteResult(result: PinpadContactEmvController.EmvCommandResult?): String {
        return if (result?.success == true) {
            "0${result.transactionCode}${result.financialNeed}"
        } else {
            "1${result?.reason ?: '1'}${result?.errorMessage.orEmpty()}"
        }
    }

    private fun formatOnlineAuthorizationData(tlvHex: String): String {
        return PinpadEmvDataObjects.parseTlvRecords(tlvHex)
            .joinToString(separator = PINPADControl.SUB.toInt().toChar().toString()) { record ->
                PinpadEmvDataObjects.encodeTlv(
                    record.tag,
                    PinpadEmvDataObjects.run { record.value.toHex() },
                ).orEmpty()
            }
    }

    private fun formatT28OnlineAuthorizationData(tlvHex: String): String {
        val records = PinpadEmvDataObjects.parseTlvRecords(tlvHex)
        val maxHexChars = T28_MAX_ONLINE_AUTH_BYTES * 2
        val payload = StringBuilder()
        for ((index, record) in records.withIndex()) {
            val encoded = PinpadEmvDataObjects.encodeTlv(
                record.tag,
                PinpadEmvDataObjects.run { record.value.toHex() },
            ).orEmpty()
            if (encoded.isEmpty()) continue
            if (payload.length + encoded.length > maxHexChars) {
                PinpadTraceLog.command(
                    "T28",
                    "online authorization data truncated payloadBytes=${payload.length / 2} " +
                        "maxBytes=$T28_MAX_ONLINE_AUTH_BYTES skippedRecords=${records.size - index}",
                )
                break
            }
            payload.append(encoded)
        }
        return payload.toString()
    }

    private fun formatContactlessTransactionResult(result: PinpadContactEmvController.EmvCommandResult?): String {
        return if (result?.success == true) {
            "0${result.transactionCode}"
        } else {
            "1${result?.reason ?: '1'}${result?.errorMessage.orEmpty()}"
        }
    }

    private fun formatQuickChipResult(
        result: PinpadContactEmvController.EmvCommandResult?,
        onlineAuthorizationTlvHex: String = "",
    ): String {
        if (result?.success != true) return "1${result?.reason ?: '1'}${result?.errorMessage.orEmpty()}"
        val arqData = if (result.transactionCode == "A1") {
            formatOnlineAuthorizationData(onlineAuthorizationTlvHex)
        } else {
            ""
        }
        return "0${result.transactionCode}${PINPADControl.SUB.toInt().toChar()}10$arqData"
    }

    private fun formatPcdHousekeeping(result: PinpadContactEmvController.EmvCommandResult?): String {
        val sub = PINPADControl.SUB.toInt().toChar()
        return if (result?.success == true) "${sub}10" else "${sub}1F"
    }

    private fun formatDukptLoadResult(result: PinpadDeviceCommands.DukptLoadResult): String {
        return if (result.success) "0" else "1${result.errorCode ?: '4'}"
    }

    private fun formatDukptStatus(status: PinpadDeviceCommands.DukptStatus, includeInfo: Boolean): String {
        if (!includeInfo || status.status != 'F') return status.status.toString()
        val fs = PINPADControl.FS.toInt().toChar()
        return "${status.status}B1${fs}X${fs}T$fs${status.kcv.orEmpty().padEnd(6, '0').take(6)}"
    }

    private fun formatPinManagementResult(result: PinpadContactEmvController.EmvCommandResult?): String {
        return if (result?.success == true) {
            "0${result.transactionCode}"
        } else {
            "1${result?.reason ?: '1'}${result?.errorMessage.orEmpty()}"
        }
    }

    private fun packetOrEmptyStatus(payload: String, separator: Char): String {
        return if (payload.isBlank()) "F" else "11$separator$payload"
    }

    private fun singleFinalPacket(payload: String): String {
        return "10$SUB_CHAR$payload"
    }

    private fun String.dropReadyControl(): String {
        return if (firstOrNull() == '0') drop(1) else this
    }

    private fun String.decodeBase64Utf8OrNull(): String? {
        return runCatching {
            String(Base64.getDecoder().decode(this), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun paymentSchemeFromAidData(aidData: String): String {
        val aid = aidData.substringAfter(PINPADControl.FS.toInt().toChar(), "")
            .substringAfter(PINPADControl.FS.toInt().toChar(), "")
            .uppercase()
        return when {
            aid.startsWith("A000000004") -> "22"
            aid.startsWith("A000000003") -> "32"
            aid.startsWith("A000000025") -> "42"
            aid.startsWith("A000000065") -> "53"
            aid.startsWith("A000000152") -> "62"
            aid.startsWith("A000000333") || aid.startsWith("A000000324") -> "72"
            else -> "F"
        }
    }

    private fun setupListStatus(payload: String): String {
        val normalized = payload.trimStart(PINPADControl.SUB.toInt().toChar()).replace(" ", "")
        return if (normalized.isNotBlank()) "0" else "12"
    }

    private fun defaultKeyAttribute(keyId: Char): String {
        val fs = PINPADControl.FS.toInt().toChar()
        val usage = when (keyId.uppercaseChar()) {
            in '0'..'9' -> "P0"
            in 'B'..'E' -> "M3"
            'F' -> "K1"
            'G' -> "D0"
            else -> "K0"
        }
        val mode = when (usage) {
            "P0", "D0" -> "E"
            "M3" -> "G"
            else -> "D"
        }
        return "$usage$fs$mode${fs}T"
    }

    private fun parseBeeperControl(payloadAscii: String): BeeperCommand? {
        if (payloadAscii.firstOrNull()?.code?.toByte() != PINPADControl.SUB) return null
        val fields = payloadAscii.drop(1).split(PINPADControl.FS.toInt().toChar())
        if (fields.size != 3) return null
        return BeeperCommand(
            count = fields[0].toIntOrNull() ?: return null,
            durationUnits = fields[1].toIntOrNull() ?: return null,
            intervalUnits = fields[2].toIntOrNull() ?: return null,
        )
    }

    private fun splitJpegNames(value: String): List<String> {
        return value.trimStart(FS_CHAR)
            .split(FS_CHAR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun isMasterSessionPinRequest(commandId: String, payload: String): Boolean {
        if (commandId == "70" || commandId == "7G") return payload.startsWith('.')
        val fields = payload.trimStart('.').split(FS_CHAR)
        val control = fields.getOrNull(1).orEmpty()
        return when (commandId) {
            "Z60" -> control.length in SESSION_KEY_HEX_LENGTHS && control.all { it.digitToIntOrNull(16) != null }
            "Z62" -> SESSION_KEY_HEX_LENGTHS.any { length ->
                control.length >= length &&
                    control.take(length).all { it.digitToIntOrNull(16) != null } &&
                    control.drop(length).length >= PIN_CONTROL_CHARS
            }
            else -> false
        }
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun ByteArray.isControl(control: Byte): Boolean = size == 1 && first() == control

    private fun PINPADFrame.isSupportedCommand(): Boolean {
        return when (frameType) {
            PINPADFrameType.Administration -> commandId in ADMINISTRATION_COMMANDS
            PINPADFrameType.Transaction -> commandId in TRANSACTION_COMMANDS
        }
    }

    private fun Char.isMasterKeyId(): Boolean {
        val value = uppercaseChar()
        return value in '0'..'9' || value in 'A'..'G'
    }

    private fun formatFirmwareVersion(version: FirmwareVersion): String {
        val fields = mutableListOf(version.version.a10FirmwareVersion())
        if (version.subVersion != DEFAULT_FIRMWARE_SUB_VERSION || version.hash != FirmwareVersion.DEFAULT_HASH) {
            fields += version.subVersion
        }
        if (version.hash != FirmwareVersion.DEFAULT_HASH) {
            fields += version.hash
        }
        return fields.joinToString(separator = ".", prefix = ".")
    }

    private fun String.a10FirmwareVersion(): String {
        val value = trim()
        val model = deviceInfoProvider.modelName().filter(Char::isLetterOrDigit).uppercase()
        val prefix = A10_FIRMWARE_PREFIX + model
        val withoutA10 = value.removePrefixIgnoreCase(A10_FIRMWARE_PREFIX)
        val withoutModel = withoutA10.removePrefixIgnoreCase(model)
        val prefixed = prefix + withoutModel
        return prefixed.take(MAX_FIRMWARE_VERSION_LENGTH)
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String {
        if (prefix.isEmpty()) return this
        return if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this
    }

    private fun List<ByteArray>.responseSummary(): String {
        if (isEmpty()) return "responses=0"
        return joinToString(prefix = "responses=$size [", postfix = "]") { response ->
            if (response.size == 1) {
                PinpadTraceLog.controlName(response[0])
            } else {
                "FRAME(${response.size} bytes)"
            }
        }
    }

    private data class BeeperCommand(
        val count: Int,
        val durationUnits: Int,
        val intervalUnits: Int,
    )

    private data class PromptDisplayResult(
        val status: Char? = null,
        val displayText: String = "",
        val responsePayload: String? = null,
    )

    private data class StringEntryRequest(
        val echoMode: TextEntryEchoMode,
        val timeoutSeconds: Int,
        val maxEntry: Int,
    )

    private sealed class ActiveDataEntry(
        open val commandId: String,
        open val timeoutSeconds: Int,
    ) {
        data class ReadKeyCode(
            override val timeoutSeconds: Int,
            val allowDigits: Boolean,
        ) : ActiveDataEntry("Z42", timeoutSeconds)

        data class StringEntry(
            override val timeoutSeconds: Int,
            val maxEntry: Int,
            val echoMode: TextEntryEchoMode,
            val prompt: String,
            val buffer: StringBuilder = StringBuilder(),
        ) : ActiveDataEntry("Z50", timeoutSeconds)
    }

    private companion object {
        private const val CLEAR_KEY_COMMAND_ID = "02"
        private const val A10_FIRMWARE_PREFIX = "A10"
        private const val DEFAULT_FIRMWARE_SUB_VERSION = "00"
        private const val MAX_FIRMWARE_VERSION_LENGTH = 48
        private const val T28_MAX_ONLINE_AUTH_BYTES = 256
        private const val MAX_A10_DEMO_CONFIG_BYTES = 256 * 1024
        private const val FS_CHAR = '\u001C'
        private const val SUB_CHAR = '\u001A'
        private const val RS_BYTE: Byte = 0x1E
        private const val VT_BYTE: Byte = 0x0B
        private const val INV_ON_BYTE: Byte = 0x11
        private const val INV_OFF_BYTE: Byte = 0x12
        private const val US_BYTE: Byte = 0x1F
        private const val DEFAULT_KEY_ALGORITHM = 'T'
        private const val PIN_CONTROL_CHARS = 5
        private const val PIN_MANAGEMENT_ONLINE_REQUEST = "A1"
        private val SESSION_KEY_HEX_LENGTHS = setOf(16, 32, 48)
        private val SECRET_KEY_HEX_LENGTHS = setOf(16, 32)
        private val SECRET_KEY_ID_RANGE = 0..9
        private val SECRET_MASTER_KEY_OPTIONS = setOf('0', '1')
        private val KEY_USAGE_REGEX = Regex("[A-Z][0-9A-Z]")
        private val KEY_MODE_VALUES = setOf('B', 'C', 'D', 'E', 'G', 'N', 'S', 'V', 'X')
        private val KEY_ALGORITHM_VALUES = setOf('A', 'D', 'E', 'H', 'R', 'S', 'T')
        private val CLEAR_KEY_INJECTION_PROTECTED_COMMANDS = setOf("02", "90", "94")
        private val CLEAR_KEY_INJECTION_ALLOWED_COMMANDS = setOf("02", "04", "06", "08", "90", "94", "98")
        private val ADMINISTRATION_NO_FINAL_EOT_COMMANDS = setOf("11", "14")
        private val CPU_CARD_COMMANDS = (0..15).map { "I0%X".format(it) }.toSet() +
            setOf("I11", "I12", "I14", "I15", "I16", "I17")
        private val CPU_CARD_UNSUPPORTED_COMMANDS = CPU_CARD_COMMANDS -
            setOf("I00", "I01", "I04", "I06", "I11", "I14", "I15", "I16")
        private val MIFARE_COMMANDS = (1..20).map { "P%02d".format(it) }.toSet()
        private val DATA_ENTRY_PROMPTS = mapOf(
            "001" to "ACCOUNT NUMBER",
            "003" to "ENTER CUST ID",
            "004" to "ENTER AMOUNT",
            "015" to "ENTER CASH BACK",
            "024" to "ENTER",
            "100" to "ACCOUNT NUMBER",
            "114" to "CUSTOMER REF",
            "115" to "CUSTOMER REF NO.",
            "126" to "ENTER BADGE #",
            "129" to "ENTER CASH BACK",
            "134" to "ENTER CUST REF",
            "200" to "ENTER OTP",
            "201" to "ENTER VERIFICATION CODE",
            "202" to "ENTER CVV2",
            "203" to "ENTER SMS OTP",
            "204" to "ENTER AUTHENTICATOR CODE",
            "205" to "ENTER TEMPORARY PASSWORD",
        )
        private val PIN_PROMPTS = mapOf(
            "001" to "ENTER PIN",
            "002" to "REENTER PIN",
            "006" to "ENTER NEW PIN",
            "007" to "CONFIRM NEW PIN",
            "024" to "ENTER",
        )

        val ADMINISTRATION_COMMANDS = setOf(
            "02",
            "04",
            "05",
            "06",
            "08",
            "09",
            "11",
            "12",
            "13",
            "14",
            "17",
            "18",
            "19",
            "1C",
            "1F",
            "1M",
            "1P",
            "M03",
            "M04",
        )

        val TRANSACTION_COMMANDS = setOf(
            "Q1",
            "Q2",
            "Q3",
            "Q4",
            "Q5",
            "Q6",
            "Q7",
            "Q8",
            "Q9",
            "QA",
            "QB",
            "QC",
            "QD",
            "QF",
            "QG",
            "QH",
            "QI",
            "QK",
            "I00",
            "I01",
            "I02",
            "I03",
            "I04",
            "I05",
            "I06",
            "I07",
            "I08",
            "I09",
            "I0A",
            "I0B",
            "I0C",
            "I0D",
            "I0E",
            "I0F",
            "I11",
            "I12",
            "I14",
            "I15",
            "I16",
            "I17",
            "P01",
            "P02",
            "P03",
            "P04",
            "P05",
            "P06",
            "P07",
            "P08",
            "P09",
            "P10",
            "P11",
            "P12",
            "P13",
            "P14",
            "P15",
            "P16",
            "P17",
            "P18",
            "P19",
            "P20",
            "20",
            "21",
            "22",
            "23",
            "24",
            "B1",
            "B3",
            "BB",
            "BD",
            "BF",
            "Z0",
            "Z1",
            "Z2",
            "Z3",
            "72",
            "Z7",
            "Z8",
            "ZA",
            "Z42",
            "Z50",
            "Z60",
            "Z62",
            "Z64",
            "Z65",
            "Z66",
            "Z67",
            "J0",
            "J1",
            "J2",
            "J3",
            "J4",
            "J5",
            "J6",
            "J7",
            "J8",
            "J9",
            "JA",
            "M10",
            "M11",
            "M12",
            "M13",
            "M14",
            "M15",
            "M16",
            "M17",
            "S1",
            "PH1",
            "QR1",
            "QR3",
            "T01",
            "T03",
            "T05",
            "T07",
            "T09",
            "T0B",
            "T51",
            "T53",
            "T55",
            "T59",
            "T5B",
            "T5D",
            "T5F",
            "T5H",
            "T11",
            "T13",
            "T15",
            "T17",
            "T19",
            "T1C",
            "T1D",
            "T21",
            "T23",
            "T25",
            "T27",
            "T29",
            "T2B",
            "T2H",
            "T31",
            "T33",
            "T34",
            "T35",
            "T37",
            "T38",
            "T3C",
            "T61",
            "T63",
            "T65",
            "T67",
            "T6C",
            "T71",
            "T72",
            "T73",
            "T75",
            "T77",
            "T81",
            "T90",
            "T92",
            "T94",
            "60",
            "62",
            "63",
            "70",
            "71",
            "7G",
            "7H",
            "7A",
            "90",
            "91",
            "94",
            "96",
            "98",
            "99",
        )
    }
}
