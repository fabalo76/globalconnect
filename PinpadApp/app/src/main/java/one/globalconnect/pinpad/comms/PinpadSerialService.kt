package one.globalconnect.pinpad.comms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.nexgo.oaf.apiv3.SdkResult
import one.globalconnect.pinpad.BuildConfig
import one.globalconnect.pinpad.MainActivity
import one.globalconnect.pinpad.PinpadApplication
import one.globalconnect.pinpad.R
import one.globalconnect.pinpad.config.DeviceModelConfig
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.licensing.PinpadLicenseManager
import one.globalconnect.pinpad.protocol.PinpadProtocolFactory
import one.globalconnect.pinpad.protocol.PinpadProtocolHandler
import one.globalconnect.pinpad.protocol.PinpadKeypadKey
import one.globalconnect.pinpad.protocol.SerialPortChange
import one.globalconnect.pinpad.storage.PinpadPreferences
import one.globalconnect.pinpad.storage.SerialSettings
import one.globalconnect.pinpad.transport.PINPADTransport
import one.globalconnect.pinpad.transport.NexgoRs232Transport
import one.globalconnect.pinpad.transport.NexgoUsbCdcTransport
import one.globalconnect.pinpad.transport.NoOpTransport
import one.globalconnect.pinpad.transport.TcpPinpadTransport

class PinpadSerialService : Service(), PINPADTransport.Listener {
    private lateinit var protocolHandler: PinpadProtocolHandler
    private var transport: PINPADTransport? = null
    private var appRef: PinpadApplication? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSerialPortChangeTask: Runnable? = null
    private var currentEndpoint = ""

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        val app = application as PinpadApplication
        if (!PinpadLicenseManager.isAuthorized(this, app.deviceInfoProvider.serialNumber())) {
            Log.w(TAG, "PINPAD service refused: application license is not valid")
            stopSelf()
            return
        }
        appRef = app
        app.audioForeground = { recording ->
            val notification = buildNotification(recording)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    if (recording && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        protocolHandler = PinpadProtocolFactory.create(app) { response ->
            PinpadTraceLog.service("TX async len=${response.size}")
            sendTransport(response)
        }
        restartTransport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD_SETTINGS -> restartTransport()
            ACTION_CANCEL_ACTIVE_OPERATION -> protocolHandler.cancelActiveOperation()
            ACTION_BEGIN_CLEAR_KEY_INJECTION_MODE -> protocolHandler.beginClearKeyInjectionMode()
            ACTION_END_CLEAR_KEY_INJECTION_MODE ->
                protocolHandler.endClearKeyInjectionMode(
                    intent.getStringExtra(EXTRA_KEY_INJECTION_END_REASON) ?: "requested",
                )
            ACTION_KEYPAD_KEY -> intent.getStringExtra(EXTRA_KEYPAD_KEY)
                ?.let { runCatching { PinpadKeypadKey.valueOf(it) }.getOrNull() }
                ?.let(protocolHandler::onKeypadKey)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        appRef?.audioRecordings?.reset()
        appRef?.audioForeground = null
        pendingSerialPortChangeTask?.let(mainHandler::removeCallbacks)
        pendingSerialPortChangeTask = null
        transport?.stop()
        transport = null
        PinpadSerialDiagnostics.stopped()
        protocolHandler.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onBytesReceived(bytes: ByteArray) {
        PinpadSerialDiagnostics.received(bytes)
        PinpadTraceLog.service("RX dispatch len=${bytes.size}")
        val responses = protocolHandler.onBytesReceived(bytes)
        PinpadTraceLog.service("TX responses=${responses.size} totalBytes=${responses.sumOf { it.size }}")
        responses.forEach(::sendTransport)
        protocolHandler.consumeCompletedSerialPortChange()?.let { change ->
            if (effectiveTransportMode(PinpadPreferences(this).serialSettings()) != "IP") {
                scheduleSerialPortChange(change)
            }
        }
    }

    override fun onTransportError(error: Throwable) {
        Log.w(TAG, "PINPAD transport error", error)
        PinpadSerialDiagnostics.failed(currentEndpoint, error)
    }

    private fun restartTransport() {
        val app = appRef ?: return
        val settings = PinpadPreferences(app).serialSettings(app.deviceInfoProvider.modelName())
        val mode = effectiveTransportMode(settings).uppercase()
        currentEndpoint = serialEndpointDescription(app, settings)
        PinpadSerialDiagnostics.opening(currentEndpoint)
        transport?.stop()
        disableUsbCdcWhenUnused(app, mode)
        runCatching {
            createTransport(app).also { next ->
                transport = next
                next.start(this)
            }
        }.onSuccess {
            Log.i(TAG, "PINPAD serial service started with $settings")
            PinpadTraceLog.service("started settings=$settings")
            PinpadSerialDiagnostics.opened(currentEndpoint)
        }.onFailure { error ->
            Log.e(TAG, "PINPAD serial service failed to start with $settings", error)
            PinpadTraceLog.service("failed settings=$settings error=${error.message}")
            PinpadSerialDiagnostics.failed(currentEndpoint, error)
            transport?.stop()
            transport = NoOpTransport(error)
            transport?.start(this)
        }
    }

    private fun disableUsbCdcWhenUnused(app: PinpadApplication, mode: String) {
        if (!shouldDisableUsbCdc(mode, app.deviceInfoProvider.modelName())) return

        val platform = app.deviceEngine.platform
        val enabled = runCatching { platform.usbCdcStatus }
            .onFailure { error ->
                Log.w(TAG, "Unable to read USB CDC status before switching to $mode", error)
                PinpadTraceLog.transport(
                    "USB_CDC status read failed before mode=$mode error=${error.message}",
                )
            }
            .getOrDefault(false)
        if (!enabled) {
            PinpadTraceLog.transport("USB_CDC already disabled for mode=$mode")
            return
        }

        val result = runCatching { platform.disableUsbCdc() }
            .onFailure { error ->
                Log.w(TAG, "USB CDC disable failed while switching to $mode", error)
                PinpadTraceLog.transport(
                    "USB_CDC disable failed mode=$mode error=${error.message}",
                )
            }
            .getOrNull()
            ?: return
        Log.i(TAG, "USB CDC disable result=$result for mode=$mode")
        PinpadTraceLog.transport("USB_CDC disable result=$result mode=$mode")
        if (result != SdkResult.Success) {
            Log.w(TAG, "USB CDC remained enabled while switching to $mode: result=$result")
        }
    }

    private fun sendTransport(bytes: ByteArray) {
        PinpadSerialDiagnostics.transmitted(bytes)
        transport?.send(bytes)
    }

    private fun serialEndpointDescription(app: PinpadApplication, settings: SerialSettings): String {
        val mode = effectiveTransportMode(settings).uppercase()
        if (mode == "IP") {
            return "IP • TCP ${settings.tcpPort} • discovery UDP ${TcpPinpadTransport.DISCOVERY_PORT}"
        }
        val port = selectedSerialPort(settings, app.deviceInfoProvider.modelName())
        val label = if (mode == "RS232") "RS232" else if (DeviceModelConfig.getDeviceSpec(app.deviceInfoProvider.modelName()).usbBaseSerialSupported) "USB" else "USB Serial"
        return "$label • port $port • ${settings.baudRate} ${settings.dataBits}${settings.parity}${settings.stopBits}"
    }

    private fun scheduleSerialPortChange(change: SerialPortChange) {
        pendingSerialPortChangeTask?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            pendingSerialPortChangeTask = null
            val app = appRef ?: return@Runnable
            val preferences = PinpadPreferences(app)
            val current = preferences.serialSettings(app.deviceInfoProvider.modelName())
            val updated = current.copy(
                baudRate = change.baudRate,
                dataBits = change.dataBits,
                stopBits = change.stopBits,
                parity = change.parity,
            )
            preferences.setSerialSettings(updated)
            PinpadTraceLog.service(
                "applying command 13 serial settings baud=${updated.baudRate} " +
                    "dataBits=${updated.dataBits} parity=${updated.parity} stopBits=${updated.stopBits}",
            )
            restartTransport()
        }
        pendingSerialPortChangeTask = task
        mainHandler.postDelayed(task, SERIAL_PORT_CHANGE_DELAY_MS)
    }

    private fun createTransport(app: PinpadApplication): PINPADTransport {
        val modelName = app.deviceInfoProvider.modelName()
        val settings = PinpadPreferences(app).serialSettings(modelName)
        val mode = effectiveTransportMode(settings).uppercase()
        // Construct only the selected transport: IP must not acquire serial drivers.
        if (mode == "IP") return TcpPinpadTransport(
            context = app,
            tcpPort = settings.tcpPort,
            serialNumber = app.deviceInfoProvider.serialNumber(),
            modelName = modelName,
        )
        val port = selectedSerialPort(settings, modelName)
        return if (usesUartTransport(mode, modelName)) {
            NexgoRs232Transport(
                deviceEngine = app.deviceEngine, portNo = port,
                baudRate = settings.baudRate, dataBits = settings.dataBits,
                stopBits = settings.stopBits, parity = settings.parity,
                transportLabel = if (mode == "RS232") "RS232" else "USB_BASE_PL2303GC",
            )
        } else {
            NexgoUsbCdcTransport(
                deviceEngine = app.deviceEngine, portNo = port,
                baudRate = settings.baudRate, dataBits = settings.dataBits,
                stopBits = settings.stopBits, parity = settings.parity,
            )
        }
    }

    private fun effectiveTransportMode(settings: SerialSettings): String {
        return settings.transportMode.ifBlank { BuildConfig.SERIAL_TRANSPORT }
    }

    private fun startForegroundServiceNotification() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        PinpadTraceLog.service("foreground service notification started")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.serial_service_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.serial_service_notification_text)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(recording: Boolean = false): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.serial_service_notification_title))
            .setContentText(if (recording) getString(R.string.audio_recording_notification) else getString(R.string.serial_service_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_RELOAD_SETTINGS = "one.globalconnect.pinpad.action.RELOAD_PINPAD_SETTINGS"
        const val ACTION_CANCEL_ACTIVE_OPERATION = "one.globalconnect.pinpad.action.CANCEL_ACTIVE_OPERATION"
        const val ACTION_BEGIN_CLEAR_KEY_INJECTION_MODE =
            "one.globalconnect.pinpad.action.BEGIN_CLEAR_KEY_INJECTION_MODE"
        const val ACTION_END_CLEAR_KEY_INJECTION_MODE =
            "one.globalconnect.pinpad.action.END_CLEAR_KEY_INJECTION_MODE"
        const val ACTION_KEYPAD_KEY = "one.globalconnect.pinpad.action.KEYPAD_KEY"
        const val EXTRA_KEYPAD_KEY = "one.globalconnect.pinpad.extra.KEYPAD_KEY"
        const val EXTRA_KEY_INJECTION_END_REASON =
            "one.globalconnect.pinpad.extra.KEY_INJECTION_END_REASON"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "pinpad_serial_service"
        private const val TAG = "PINPADSerialService"
        private const val SERIAL_PORT_CHANGE_DELAY_MS = 150L
    }
}

internal fun shouldDisableUsbCdc(transportMode: String, modelName: String? = null): Boolean =
    DeviceModelConfig.supportsUsbCdc(modelName) && transportMode.uppercase() != "SERIAL"

internal fun usesUartTransport(mode: String, modelName: String?): Boolean =
    mode.uppercase() == "RS232" ||
        (mode.uppercase() == "SERIAL" && DeviceModelConfig.getDeviceSpec(modelName).usbBaseSerialSupported)

internal fun selectedSerialPort(settings: SerialSettings, modelName: String?): Int = when {
    settings.transportMode.uppercase() == "RS232" -> settings.rs232Port
    DeviceModelConfig.getDeviceSpec(modelName).usbBaseSerialSupported ->
        DeviceModelConfig.getDeviceSpec(modelName).fixedUsbBasePort ?: settings.usbBasePort
    else -> DeviceModelConfig.getUsbCdcSerialPort(modelName)
}
