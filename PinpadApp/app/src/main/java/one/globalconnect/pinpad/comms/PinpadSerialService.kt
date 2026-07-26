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

class PinpadSerialService : Service(), PINPADTransport.Listener {
    private lateinit var protocolHandler: PinpadProtocolHandler
    private var transport: PINPADTransport? = null
    private var appRef: PinpadApplication? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSerialPortChangeTask: Runnable? = null

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
        protocolHandler = PinpadProtocolFactory.create(app) { response ->
            PinpadTraceLog.service("TX async len=${response.size}")
            transport?.send(response)
        }
        restartTransport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD_SETTINGS -> restartTransport()
            ACTION_CANCEL_ACTIVE_OPERATION -> protocolHandler.cancelActiveOperation()
            ACTION_KEYPAD_KEY -> intent.getStringExtra(EXTRA_KEYPAD_KEY)
                ?.let { runCatching { PinpadKeypadKey.valueOf(it) }.getOrNull() }
                ?.let(protocolHandler::onKeypadKey)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pendingSerialPortChangeTask?.let(mainHandler::removeCallbacks)
        pendingSerialPortChangeTask = null
        transport?.stop()
        transport = null
        protocolHandler.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onBytesReceived(bytes: ByteArray) {
        PinpadTraceLog.service("RX dispatch len=${bytes.size}")
        val responses = protocolHandler.onBytesReceived(bytes)
        PinpadTraceLog.service("TX responses=${responses.size} totalBytes=${responses.sumOf { it.size }}")
        responses.forEach { response ->
            transport?.send(response)
        }
        protocolHandler.consumeCompletedSerialPortChange()?.let(::scheduleSerialPortChange)
    }

    override fun onTransportError(error: Throwable) {
        Log.w(TAG, "PINPAD transport error", error)
    }

    private fun restartTransport() {
        val app = appRef ?: return
        val settings = PinpadPreferences(app).serialSettings(app.deviceInfoProvider.modelName())
        transport?.stop()
        transport = createTransport(app)
        runCatching {
            transport?.start(this)
        }.onSuccess {
            Log.i(TAG, "PINPAD serial service started with $settings")
            PinpadTraceLog.service("started settings=$settings")
        }.onFailure { error ->
            Log.e(TAG, "PINPAD serial service failed to start with $settings", error)
            PinpadTraceLog.service("failed settings=$settings error=${error.message}")
            transport?.stop()
            transport = NoOpTransport(error)
            transport?.start(this)
        }
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
        val rs232 = NexgoRs232Transport(
            deviceEngine = app.deviceEngine,
            portNo = settings.rs232Port,
            baudRate = settings.baudRate,
            dataBits = settings.dataBits,
            stopBits = settings.stopBits,
            parity = settings.parity,
        )
        val usbCdc = NexgoUsbCdcTransport(
            deviceEngine = app.deviceEngine,
            portNo = DeviceModelConfig.getUsbCdcSerialPort(modelName),
            baudRate = settings.baudRate,
            dataBits = settings.dataBits,
            stopBits = settings.stopBits,
            parity = settings.parity,
        )
        return when (effectiveTransportMode(settings).uppercase()) {
            "RS232" -> rs232
            else -> usbCdc
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

    private fun buildNotification(): Notification {
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
            .setContentText(getString(R.string.serial_service_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_RELOAD_SETTINGS = "one.globalconnect.pinpad.action.RELOAD_PINPAD_SETTINGS"
        const val ACTION_CANCEL_ACTIVE_OPERATION = "one.globalconnect.pinpad.action.CANCEL_ACTIVE_OPERATION"
        const val ACTION_KEYPAD_KEY = "one.globalconnect.pinpad.action.KEYPAD_KEY"
        const val EXTRA_KEYPAD_KEY = "one.globalconnect.pinpad.extra.KEYPAD_KEY"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "pinpad_serial_service"
        private const val TAG = "PINPADSerialService"
        private const val SERIAL_PORT_CHANGE_DELAY_MS = 150L
    }
}
