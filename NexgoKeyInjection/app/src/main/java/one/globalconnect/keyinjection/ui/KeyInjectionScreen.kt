package one.globalconnect.keyinjection.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexgo.oaf.apiv3.device.led.LightModeEnum
import one.globalconnect.keyinjection.App
import one.globalconnect.keyinjection.R
import one.globalconnect.keyinjection.comm.SerialPacketManager
import one.globalconnect.keyinjection.comm.UartManager
import one.globalconnect.keyinjection.protocol.EPedKeyType
import one.globalconnect.keyinjection.protocol.FuturexProtocol
import one.globalconnect.keyinjection.protocol.KeyInjectionCommand
import one.globalconnect.keyinjection.util.FontSize
import one.globalconnect.keyinjection.util.Logger
import one.globalconnect.keyinjection.util.PedProxy
import one.globalconnect.keyinjection.util.Printer
import one.globalconnect.keyinjection.util.UsbPermissionHelper
import one.globalconnect.keyinjection.util.UsbSerialCableDetector
import one.globalconnect.keyinjection.util.hexToByte
import one.globalconnect.keyinjection.util.hexToByteArray
import one.globalconnect.keyinjection.util.hexToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * Screen that drives the key injection session and displays log messages
 * as commands are received. Each entry is color coded to reflect success,
 * errors or informational events.
 *
 * @param protocol protocol used to read incoming key injection messages
 * @param onFinish invoked when the session ends either manually or by timeout
 */
@Composable
fun KeyInjectionScreen(
    protocol: FuturexProtocol = FuturexProtocol(),
    onFinish: () -> Unit
) {
    val tAG = "KeyInjectionScreen"
    val context = LocalContext.current

    /** Representation of a row in the on-screen activity log. */
    data class LogEntry(val text: String, val color: Color)
    val successColor = Color(0xFF006400)
    val logs = remember { mutableStateListOf<LogEntry>() }
    val keyInjected = remember { mutableStateOf(false) }
    val showFinalButtons = remember { mutableStateOf(false) }
    val sessionEnded = remember { mutableStateOf(false) }
    val initializing = remember { mutableStateOf(true) }
    val showEraseDialog = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * Launch and manage the key injection session, updating the UI as messages
     * arrive.
     */
    suspend fun startSession() {
        val timeout = 35_000L
        Logger.d(tAG, "Starting key injection session")
        val model = App.model
        val serialNumber = App.serialNumber

        // Request USB permission if a USB serial cable is detected
        val connectedCable = UsbSerialCableDetector.detectConnectedCable(context)
        if (connectedCable != null) {
            Logger.d(tAG, "USB serial cable detected: $connectedCable, requesting permission")
            val (vendorId, productId) = when (connectedCable) {
                one.globalconnect.keyinjection.util.UsbSerialCableType.FTDI ->
                    UartManager.FTDI_USB_VENDOR_ID to UartManager.FTDI_USB_PRODUCT_ID
                one.globalconnect.keyinjection.util.UsbSerialCableType.PL2303 ->
                    UartManager.PL2303_USB_VENDOR_ID to UartManager.PL2303_USB_PRODUCT_ID
            }

            when (UsbPermissionHelper.ensurePermission(context, vendorId, productId)) {
                UsbPermissionHelper.Result.GRANTED ->
                    Logger.i(tAG, "USB serial permission granted for $connectedCable")
                UsbPermissionHelper.Result.DEVICE_NOT_FOUND -> {
                    Logger.e(tAG, "USB serial device not found")
                    val entry = LogEntry(
                        App.instance.getString(R.string.com_port_open_failed),
                        Color.Red
                    )
                    logs.add(entry)
                    initializing.value = false
                    return
                }
                UsbPermissionHelper.Result.DENIED -> {
                    Logger.e(tAG, "USB serial permission denied by user")
                    val entry = LogEntry("USB permission denied", Color.Red)
                    logs.add(entry)
                    initializing.value = false
                    return
                }
            }
        }

        val opened = withContext(Dispatchers.IO) {
            try {
                protocol.open()
            } catch (e: Exception) {
                Logger.e(tAG, "COM port open failed", e)
                false
            }
        }

        if (!opened) {
            val entry = LogEntry(App.instance.getString(R.string.com_port_open_failed), Color.Red)
            logs.add(entry)
            Logger.e(tAG, entry.text)
            initializing.value = false
            return
        }
        val ledJob: Job? =
                scope.launch(Dispatchers.IO) {
                    val lightSequence = listOf(
                        LightModeEnum.RED,
                        LightModeEnum.GREEN,
                        LightModeEnum.YELLOW,
                        LightModeEnum.BLUE
                    )
                    if (model.equals("N96", ignoreCase = true)) {
                        App.deviceEngine.ledDriver.setLed(LightModeEnum.DECORATIVE_LIGHT, true)
                    }
                    var currentIndex = 0
                    try {
                        while (true) {
                            val activeIndex = currentIndex
                            lightSequence.forEachIndexed { index, mode ->
                                App.deviceEngine.ledDriver.setLed(mode, index == activeIndex)
                            }
                            delay(500)
                            currentIndex = (currentIndex + 1) % lightSequence.size
                        }
                    } finally {
                        lightSequence.forEach { mode ->
                            App.deviceEngine.ledDriver.setLed(mode, false)
                        }
                        if (model.equals("N96", ignoreCase = true)) {
                            App.deviceEngine.ledDriver.setLed(LightModeEnum.DECORATIVE_LIGHT, false)
                        }
                    }
                }

        try {
            when (UartManager.instance.connectionType) {
                UartManager.ConnectionType.USB_SERIAL -> {
                    val entry = LogEntry(App.instance.getString(R.string.using_XDG_loadcable_port), Color.Black)
                    logs.add(entry)
                    Logger.i(tAG, entry.text)
                }
                UartManager.ConnectionType.USB_CDC -> {
                    val entry = LogEntry(App.instance.getString(R.string.using_usb_cdc_port), Color.Black)
                    logs.add(entry)
                    Logger.i(tAG, entry.text)
                }
                UartManager.ConnectionType.INTERNAL_SERIAL -> {
                    val entry = LogEntry(App.instance.getString(R.string.using_internal_serial_port), Color.Black)
                    logs.add(entry)
                    Logger.i(tAG, entry.text)
                }
                null -> Unit
            }
            val tlkKCV = PedProxy.checkKey(EPedKeyType.TLK, 0x01)

            logs.add(LogEntry(App.instance.getString(R.string.key_injection_started), Color.Black))
            logs.add(LogEntry(App.instance.getString(R.string.model_info, model), Color.Black))
            logs.add(LogEntry(App.instance.getString(R.string.serial_number_info, serialNumber), Color.Black))
            if (tlkKCV != null)
                logs.add(LogEntry(App.instance.getString(R.string.tlk_kcv, tlkKCV), Color.Black))
            initializing.value = false

            var commError = false
            while (!sessionEnded.value && !commError) {
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        protocol.receive(timeout)
                    } catch (e: Exception) {
                        Logger.e(tAG, "Receive failed", e)
                        commError = true
                        null
                    }
                } ?: break
                Logger.d(tAG, "Received Futurex command packet (${bytes.size} bytes)")
                val command = protocol.getCommand(bytes)
                when (command) {
                    KeyInjectionCommand.ERASE_KEYS -> {
                        val request = protocol.parseEraseKeysRequest(bytes)
                        if (request != null) {
                            var entry = LogEntry(App.instance.getString(R.string.erase_all_keys_request), Color.Black)
                            Logger.i(tAG, entry.text)
                            logs.add(entry)
                            var responseCode = "01"
                            if (PedProxy.Erase()) {
                                responseCode = "00"
                               // tlkKCV = null
                            }
                            val responseBytes = protocol.buildEraseAllKeys(responseCode)
                            val response = protocol.send(responseBytes)
                            Logger.d(tAG, "protocol.send Response: ${response}")

                            entry = LogEntry(App.instance.getString(R.string.all_keys_erased), successColor)
                            Logger.i(tAG, entry.text)
                            logs.add(entry)
                        }
                        else
                        {
                            val entry = LogEntry(App.instance.getString(R.string.invalid_erase_keys_request_command), Color.Red)
                            Logger.w(tAG, entry.text)
                            logs.add(entry)
                        }
                    }

                    KeyInjectionCommand.DUKPT_INJECT -> {
                        val request = protocol.parseDUKPTInject(bytes)
                        if (request != null) {
                            var entry = LogEntry(App.instance.getString(R.string.dukpt_inject_request), Color.Black)
                            Logger.i(tAG, entry.text)
                            logs.add(entry)
                            var ksnInjectionResponseCode = "FF"
                            var ipekInjectionResponseCode = "FF"
                            var responseKCV = "0000"
                            if (tlkKCV == null) {
                                // Convert hex strings to byte arrays/byte values
                                val keyBytes: ByteArray = request.iPek.hexToByteArray()
                                val ksnBytes: ByteArray = request.iKsn.hexToByteArray()
                                val kcvBytes: ByteArray = request.calculatedKCV.hexToByteArray()
                                val keySlot: Byte = request.keySlot.hexToByte()
                                //Now Inject the Clear Text Key
                                var keyloaded = false
                                try {
                                    val ret = PedProxy.writeKey(
                                        EPedKeyType.None,
                                        0,
                                        EPedKeyType.TIK,
                                        keySlot.toInt(),
                                        keyBytes,
                                        ksnBytes,
                                        kcvBytes
                                    )
                                    ksnInjectionResponseCode = ret.responseCode
                                    ipekInjectionResponseCode = ret.responseCode

                                    if (ret.success) {
                                        keyloaded = true
                                        responseKCV = request.calculatedKCV
                                    }
                                } catch (e: Exception) {
                                    Logger.e(tAG, "dukptKeyInject failed", e)
                                    //e.stackTraceToString();
                                }
                                if (keyloaded) {
                                    entry = LogEntry(
                                        App.instance.getString(
                                            R.string.key_loaded,
                                            "DUKPT",
                                            keySlot,
                                            request.calculatedKCV
                                        ), successColor
                                    )
                                    Logger.i(tAG, entry.text)
                                    logs.add(entry)
                                    keyInjected.value = true
                                } else {
                                    entry = LogEntry(
                                        App.instance.getString(
                                            R.string.key_load_fail,
                                            "DUKPT",
                                            keySlot
                                        ), Color.Red
                                    )
                                    Logger.w(tAG, entry.text)
                                    logs.add(entry)
                                }
                            }
                            else
                            {
                                entry = LogEntry(App.instance.getString(R.string.clear_key_load_not_supported_tlk_loaded), Color.Red)
                                Logger.i(tAG, entry.text)
                                logs.add(entry)
                                ksnInjectionResponseCode = "02"
                                ipekInjectionResponseCode = "02"

                            }
                            val responseBytes = protocol.buildDUKPTInjectResponse(
                                ksnInjectionResponseCode,
                                ipekInjectionResponseCode,
                                responseKCV
                            )
                            val response = protocol.send(responseBytes)
                            Logger.d(tAG, "protocol.send Response: ${'$'}{response}")
                            if (response == SerialPacketManager.AckCode.ACK) {
                                entry = LogEntry(
                                    App.instance.getString(R.string.dukpt_key_load_response_sent),
                                    successColor
                                )
                                Logger.i(tAG, entry.text)
                                //logs.add(entry)
                            } else {
                                entry = LogEntry(
                                    App.instance.getString(R.string.error_sending_dukpt_key_load_response),
                                    Color.Red
                                )
                                Logger.i(tAG, entry.text)
                                logs.add(entry)
                            }

                        }
                        else
                        {
                            val entry = LogEntry(App.instance.getString(R.string.invalid_dukpt_inject_request_command), Color.Red)
                            Logger.w(tAG, entry.text)
                            logs.add(entry)
                            protocol.sendEOT()
                        }
                    }
                    KeyInjectionCommand.MK_INJECT -> {
                        val request = protocol.parseMKInject(bytes)
                        if (request != null) {
                            var entry = LogEntry(App.instance.getString(R.string.mk_inject_request), Color.Black)
                            Logger.i(tAG, entry.text)
                            logs.add(entry)
                            var keyInjectionResponseCode = "FF"
                            var responseKCV = "0000"

                            if (tlkKCV == null) {
                                //Here Convert the Hex String to a byte array.
                                val keyBytes: ByteArray = request.key.hexToByteArray()
                                val kcvBytes: ByteArray = request.calculatedKCV.hexToByteArray()
                                val keySlot: Byte = request.keySlot.hexToByte()


                                //Now Inject the Clear Text Key
                                var keyLoaded = false
                                try {
                                    val ret = PedProxy.writeKey(
                                        EPedKeyType.None,
                                        0,
                                        EPedKeyType.TMK,
                                        keySlot.toInt(),
                                        keyBytes,
                                        ByteArray(0),
                                        kcvBytes
                                    )
                                    keyInjectionResponseCode = ret.responseCode
                                    if (ret.success) {
                                        keyLoaded = true
                                        responseKCV = request.calculatedKCV
                                    }
                                } catch (e: Exception) {
                                    Logger.e(tAG, "writeMKey failed", e)
                                    //e.stackTraceToString();
                                }
                                if (keyLoaded) {
                                    entry = LogEntry(
                                        App.instance.getString(
                                            R.string.key_loaded,
                                            "TMK",
                                            keySlot,
                                            request.calculatedKCV
                                        ),
                                        successColor
                                    )
                                    Logger.i(tAG, entry.text)
                                    logs.add(entry)
                                    keyInjected.value = true
                                } else {
                                    entry = LogEntry(
                                        App.instance.getString(
                                            R.string.key_load_fail,
                                            "TMK",
                                            keySlot
                                        ), Color.Red
                                    )
                                    Logger.w(tAG, entry.text)
                                    logs.add(entry)
                                }
                            }
                            else
                            {
                                entry = LogEntry(App.instance.getString(R.string.clear_key_load_not_supported_tlk_loaded), Color.Red)
                                Logger.i(tAG, entry.text)
                                logs.add(entry)
                                keyInjectionResponseCode = "05"
                            }
                            val responseBytes =
                                protocol.buildMKInjectResponse(keyInjectionResponseCode, responseKCV)
                            val response = protocol.send(responseBytes)
                            Logger.d(tAG, "protocol.send Response: ${response}")
                            if (response == SerialPacketManager.AckCode.ACK) {
                                entry = LogEntry(
                                    App.instance.getString(R.string.mk_key_load_response_sent),
                                    successColor
                                )
                                Logger.i(tAG, entry.text)
                                //logs.add(entry)
                            } else {
                                entry = LogEntry(
                                    App.instance.getString(R.string.error_sending_mk_key_load_response),
                                    Color.Red
                                )
                                Logger.i(tAG, entry.text)
                                logs.add(entry)
                            }

                        }
                        else
                        {
                            val entry = LogEntry(App.instance.getString(R.string.invalid_mk_inject_request), Color.Red)
                            Logger.w(tAG, entry.text)
                            logs.add(entry)
                            protocol.sendEOT()
                        }
                    }
                    KeyInjectionCommand.SERIAL_NUM_REQUEST -> {
                        val request = protocol.parseSerialNumRequest(bytes)
                        if (request != null) {
                            var entry = LogEntry(App.instance.getString(R.string.serial_number_request), Color.Black)
                            Logger.i(tAG, entry.text)
                            logs.add(entry)

                            val responseBytes = protocol.buildSerialNumReadResponse("00", serialNumber)
                            val response = protocol.send(responseBytes)
                            Logger.d(tAG, "protocol.send Response: ${response}")

                            entry = LogEntry(App.instance.getString(R.string.serial_number_read_success), successColor)
                            Logger.i(tAG, entry.text)
                            logs.add(entry)

                        }
                        else
                        {
                            val entry = LogEntry(App.instance.getString(R.string.invalid_serial_number_request_command), Color.Red)
                            Logger.w(tAG, entry.text)
                            logs.add(entry)
                        }
                    }
                    KeyInjectionCommand.SERIAL_NUM_WRITE -> {
                        val request = protocol.parseSerialNumWrite(bytes)
                        if (request != null) {
                            var entry = LogEntry(App.instance.getString(R.string.serial_number_write_request_received), Color.Black)
                            Logger.i(tAG, entry.text)
                            logs.add(entry)

                            entry = LogEntry(App.instance.getString(R.string.serial_number_written), successColor)
                            Logger.i(tAG, entry.text)
                            logs.add(entry)
                        }
                        else
                        {
                            val entry = LogEntry(App.instance.getString(R.string.invalid_serial_number_write_request_command), Color.Red)
                            Logger.w(tAG, entry.text)
                            logs.add(entry)
                        }
                    }
                    KeyInjectionCommand.KTK_ENCRYPTED_KEY_INJECT -> {
                        val request = protocol.parseKTKEncryptedKeyInject(bytes)
                        if (request != null) {
                            val requestMessage = when (request.keyEncryption) {
                                "00" -> R.string.command02_clear_key_load_request_received
                                "01" -> R.string.command02_preloaded_ktk_key_load_request_received
                                else -> R.string.command02_supplied_ktk_key_load_request_received
                            }
                            var entry = LogEntry(
                                App.instance.getString(requestMessage),
                                Color.Black
                            )
                            Logger.i(tAG, entry.text)
                            logs.add(entry)
                            var responseCode: String = "FF"
                            var responseKeyKCV: String = "0000"

                            var ktkValid = true
                            var srcKeyType : EPedKeyType = EPedKeyType.TLK
                            var srcKeyBytes : ByteArray? = null
                            var srcKeySlot : Int = request.ktkSlot.hexToInt()
                            var keySlot: Int = request.keySlot.hexToInt()
                            if (request.keyEncryption == "01") { //Key encrypted under a preloaded KTK(“01”)
                                var kcv: String?
                                if (request.ktkSlot == "00") // If Key Slot is 00 use TLK instead of Indexed TMK
                                {
                                    srcKeyType = EPedKeyType.TLK
                                    srcKeySlot = 0x00;
                                    kcv = PedProxy.checkKey(EPedKeyType.TLK, srcKeySlot.toInt())
                                }
                                else {
                                    srcKeyType = EPedKeyType.TMK
                                    kcv = PedProxy.checkKey(EPedKeyType.TMK, request.ktkSlot.hexToInt())
                                }
                                ktkValid = kcv?.startsWith(request.ktkChecksum.uppercase()) == true
                                if (!ktkValid) {
                                    entry = LogEntry(
                                        App.instance.getString(R.string.ktk_kcv_does_not_match),
                                        Color.Red
                                    )
                                    Logger.w(tAG, entry.text)
                                    logs.add(entry)
                                    responseCode = "02"
                                }
                            }
                            else if (request.keyEncryption == "02")
                            {
                                if (request.ktkSlot == "00") // If Key Slot is 00 use TLK instead of Indexed TMK
                                {
                                    srcKeyType = EPedKeyType.TLK
                                    srcKeySlot = 0x00;
                                }
                                else {
                                    srcKeyType = EPedKeyType.TMK
                                }
                                val pedKTKKCV = PedProxy.checkKey(srcKeyType, srcKeySlot)

                                if ((request.ktkPayload.length == 32) || (request.ktkPayload.length == 48))
                                {
                                    srcKeyBytes = request.ktkPayload.hexToByteArray()
                                    val srcKeyKCV = protocol.calculateKCV(request.ktkPayload);
                                    if (pedKTKKCV != null) // KTK Already Loaded, verify KCV Matches
                                    {
                                        if (srcKeyKCV.startsWith(pedKTKKCV.toString())) {
                                            Logger.i(tAG, "KTK Already Loaded, KCV Matches")
                                        }
                                        else
                                        {
                                            ktkValid = false
                                            entry = LogEntry(
                                                App.instance.getString(R.string.ktk_alreadyloaded_kcv_does_not_match),
                                                Color.Red
                                            )
                                            Logger.w(tAG, entry.text)
                                            logs.add(entry)
                                            responseCode = "02"
                                        }

                                    }
                                    else
                                    {
                                        Logger.i(tAG, "KTK Present and Not Loaded... Loading KTK")
                                        //Now Inject the KTK Clear Text Key
                                        var keyLoaded = false
                                        try {
                                            val ktkDestinationType = if (request.ktkSlot == "00") {
                                                EPedKeyType.TLK
                                            } else {
                                                EPedKeyType.TMK
                                            }
                                            val ret = PedProxy.writeKey(
                                                EPedKeyType.None,
                                                0,
                                                ktkDestinationType,
                                                srcKeySlot,
                                                srcKeyBytes,
                                                ByteArray(0),
                                                srcKeyKCV.hexToByteArray()
                                            )
                                            if (ret.success)
                                                keyLoaded = true
                                            else
                                                Logger.e(tAG, "inject KTK failed: ${ret.responseCode}")
                                        } catch (e: Exception) {
                                            Logger.e(tAG, "inject KTK failed", e)
                                            //e.stackTraceToString();
                                        }
                                        if (keyLoaded) {
                                            entry = LogEntry(
                                                App.instance.getString(R.string.ktk_key_loaded, srcKeyType, srcKeySlot, srcKeyKCV),
                                                successColor
                                            )
                                            Logger.i(tAG, entry.text)
                                            logs.add(entry)
                                            keyInjected.value = true
                                        }
                                        else
                                        {
                                            responseCode = "02"
                                            entry = LogEntry(App.instance.getString(R.string.ktk_key_load_failed, srcKeyType, srcKeySlot, responseCode), Color.Red)
                                            Logger.w(tAG, entry.text)
                                            logs.add(entry)
                                            ktkValid = false
                                        }
                                    }
                                }
                                else
                                {
                                    val entry = LogEntry(
                                        App.instance.getString(R.string.invalid_ktk_key),
                                        Color.Red
                                    )
                                    Logger.w(tAG, entry.text)
                                    logs.add(entry)
                                    ktkValid = false
                                    responseCode = "02"
                                }
                            }
                            else if (request.keyEncryption == "00")
                            {
                                srcKeyType = EPedKeyType.None
                                srcKeySlot = 0x00;
                            }
                            if (ktkValid) {
                                val keyBytes: ByteArray = request.keyPayload.hexToByteArray()
                                val kcvBytes: ByteArray = request.keyChecksum.hexToByteArray()
                                val ksnBytes: ByteArray = request.ksn.hexToByteArray()

                                when (request.keyType) {
                                    "01", // Master Session Key
                                    "04", // MAC Key
                                    "05", // PIN Encryption Key
                                    "06", // Key Exchange Key
                                    "09", // Default KTK
                                    "02", // DUKPT Initial Key
                                    "03", // DUKPT BDK Key
                                    "08"  // DUKPT Keys
                                        ->
                                    {
                                        //Now Inject the Master Key
                                        var keyLoaded = false
                                        val keyType = request.keyType()
                                        val keyTypeName = request.keyTypeName()
                                        val ret = PedProxy.writeKey(
                                            srcKeyType //Source Key Type
                                            ,srcKeySlot //
                                            ,keyType // Destin Key Type
                                            ,keySlot //
                                            ,keyBytes
                                            ,ksnBytes
                                            ,kcvBytes
                                        )
                                        responseCode = ret.responseCode
                                        if (ret.success) {
                                            keyLoaded = true
                                            responseKeyKCV = request.keyChecksum
                                            entry = LogEntry(
                                                App.instance.getString(R.string.key_loaded, keyTypeName, keySlot, request.keyChecksum),
                                                successColor
                                            )
                                            Logger.i(tAG, entry.text)
                                            logs.add(entry)
                                            keyInjected.value = true
                                        }
                                        else
                                        {
                                            entry = LogEntry(App.instance.getString(R.string.key_load_fail, keyTypeName , keySlot), Color.Red)
                                            Logger.w(tAG, entry.text)
                                            logs.add(entry)
                                        }
                                    }
                                    "07" -> // Host Verification KTK
                                    {
                                        responseCode = "F1"
                                        entry = LogEntry(App.instance.getString(R.string.unsupported_key_type), Color.Red)
                                        Logger.w(tAG, entry.text)
                                        logs.add(entry)
                                    }
                                    "0A" -> // Balance decryption Key
                                    {
                                        responseCode = "F2"
                                        entry = LogEntry(App.instance.getString(R.string.unsupported_key_type), Color.Red)
                                        Logger.w(tAG, entry.text)
                                        logs.add(entry)
                                    }
                                    "0B" -> // TDR DUKPT BDK
                                    {
                                        responseCode = "F3"
                                        entry = LogEntry(App.instance.getString(R.string.unsupported_key_type), Color.Red)
                                        Logger.w(tAG, entry.text)
                                        logs.add(entry)
                                    }
                                    else -> {
                                        entry = LogEntry(
                                            App.instance.getString(R.string.invalid_encrypted_key_load_request_command),
                                            Color.Red
                                        )
                                        Logger.w(tAG, entry.text)
                                        logs.add(entry)
                                    }
                                }
                                val responseBytes =
                                    protocol.buildKTKEncryptedKeyInjectResponse(responseCode, responseKeyKCV)
                                val response = protocol.send(responseBytes)
                                Logger.d(tAG, "protocol.send Response: $response")
                            } else {
                                val responseBytes =
                                    protocol.buildKTKEncryptedKeyInjectResponse(responseCode, responseKeyKCV)
                                val response = protocol.send(responseBytes)
                                Logger.d(tAG, "protocol.send Response: $response")

                            }
                        }
                    }
                    null -> {
                        Logger.w(tAG, "Unknown command NULL")
                    }
                }
            }
            withContext(Dispatchers.IO) {
                try {
                    protocol.close()
                } catch (e: Exception) {
                    Logger.e(tAG, "COM port close failed", e)
                }
            }
            if (commError) {
                val entry = LogEntry(App.instance.getString(R.string.communication_error), Color.Red)
                Logger.e(tAG, entry.text)
                logs.add(entry)
            } else if (!sessionEnded.value) {
                Logger.w(tAG, "Session timed out")
                logs.add(LogEntry(App.instance.getString(R.string.session_timed_out), Color.Black))
            }

        } finally {
            withContext(NonCancellable) {
                ledJob?.cancelAndJoin()
                if (model.equals("N96", ignoreCase = true)) {
                    App.deviceEngine.ledDriver.setLed(LightModeEnum.DECORATIVE_LIGHT, false)
                }
            }
        }


        if (!keyInjected.value) {
            onFinish()
        } else {
            showFinalButtons.value = true
        }
    }

    LaunchedEffect(Unit) {
        if (!PedProxy.Init())
        {
            val entry = LogEntry(App.instance.getString(R.string.pinpad_init_failed), Color.Red)
            logs.add(entry)
            Logger.i(tAG, entry.text)
            initializing.value = false
            return@LaunchedEffect
        }

        val hasKeys = withContext(Dispatchers.IO) { PedProxy.hasExistingKeys() }
        if (hasKeys) {
            initializing.value = false
            showEraseDialog.value = true
        } else {
            startSession()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBanner()
        if (initializing.value) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.please_wait), fontSize = 20.sp)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
            Box(
                Modifier
                    .weight(1f)
                    .border(1.dp, Color.Black)
                    .padding(8.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { entry ->
                        Text(entry.text, color = entry.color)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (showFinalButtons.value) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    GlobalConnectButton(
                        onClick = onFinish,
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                    ) {
                        Text(stringResource(R.string.close), fontSize = 20.sp)
                    }
                    if (!App.model.equals("N6", ignoreCase = true)) {
                        GlobalConnectButton(
                            onClick = {
                                scope.launch {
                                    // Do any blocking/setup work on IO
                                    withContext(Dispatchers.IO) {
                                        Printer.init()
                                        val toPrint = logs.map { it.text }
                                        Printer.printLines(toPrint, FontSize.MEDIUM)
                                    }
                                    // Now call the suspend function from the coroutine
                                    val status = Printer.start()
                                    Logger.i(tAG, "Print status: $status")
                                }                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                        ) {
                            Text(stringResource(R.string.print), fontSize = 20.sp)
                        }
                    }
                }
            } else {
                GlobalConnectButton(
                    onClick = {
                        sessionEnded.value = true
                        if (keyInjected.value) {
                            showFinalButtons.value = true
                        } else {
                            onFinish()
                        }
                        Thread {
                            try {
                                protocol.close()
                            } catch (e: Exception) {
                                Logger.e(tAG, "COM port close failed", e)
                            }
                        }.start()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                ) {
                    Text(stringResource(R.string.finish), fontSize = 20.sp)
                }
            }
            }
        }
    }

    if (showEraseDialog.value) {
        AlertDialog(
            onDismissRequest = {},
            text = { Text(stringResource(R.string.erase_keys_warning), fontSize = 20.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEraseDialog.value = false
                        initializing.value = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    if (PedProxy.Erase() == true)
                                    {
                                        Logger.i(tAG, "Erase successful")
                                    }
                                    else
                                    {
                                        Logger.e(tAG, "Erase failed")
                                    }
                                } catch (e: Exception) {
                                    Logger.e(tAG, "Erase failed", e)
                                }
                            }
                            startSession()
                    }
                },
                    colors = ButtonDefaults.textButtonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White)
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEraseDialog.value = false
                        onFinish()
                    },
                    colors = ButtonDefaults.textButtonColors(containerColor = Color(0xFFF44336), contentColor = Color.White)
                ) { Text(stringResource(R.string.close)) }
            }
        )
    }
}
