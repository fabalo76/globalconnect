package one.globalconnect.xtmsagent.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import one.globalconnect.xtmsagent.R
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsHkScheduler
import one.globalconnect.xtmsagent.mqtt.persistence.TmsCredentialStore
import one.globalconnect.xtmsagent.mqtt.status.TmsLocationTracker
import one.globalconnect.xtmsagent.mqtt.status.TmsStatusWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TAG              = "TmsMqttService"
private const val NOTIFICATION_ID  = 1001
private const val CHANNEL_ID       = "tms_mqtt_channel"
private const val CHANNEL_NAME     = "TMS Connection"

/**
 * Foreground service that owns the TMS MQTT connection for the lifetime of
 * the terminal application.
 *
 * Lifecycle:
 *   - Started from XtmsAgentApplication.onCreate() (and BootReceiver after reboot).
 *   - START_STICKY: Android restarts the service after process death. On restart
 *     the service re-reads credentials from EncryptedSharedPreferences and
 *     reconnects without waiting for a UI activity.
 *   - Stopped only on application uninstall or explicit stopService() call.
 *
 * The service does not bind to Activities — it is fully independent.
 */
class TmsMqttService : Service() {

    private var serviceScope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to TMS…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val store    = TmsCredentialStore(this)
        val termId   = store.loadTermId()
        val brokerHost = store.loadBrokerHost()

        if (termId.isNullOrBlank() || brokerHost.isNullOrBlank()) {
            Log.e(TAG, "TermID or broker host not provisioned — service stopping. " +
                "Ensure XtmsAgentApplication.onCreate() runs before the service starts.")
            stopSelf()
            return
        }

        TmsMqttManager.initialize(this, termId, brokerHost)
        TmsStatusWorker.schedule(this)
        TmsLocationTracker.start(this)
        TmsHkScheduler.scheduleIfNeeded(this)
        TmsMqttManager.connect()

        val connectedText = "TMS connected ($termId)"
        updateNotification(connectedText)
        Log.i(TAG, "MQTT service started for $termId @ $brokerHost")

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { sc ->
            sc.launch {
                TmsTaskStatus.taskOverride.collect { override ->
                    updateNotification(override ?: connectedText)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if killed, restart with null intent — service reads credentials
        // from SharedPreferences and reconnects without needing Activity context.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        serviceScope?.cancel()
        TmsLocationTracker.stop()
        TmsMqttManager.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null  // not a bound service

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains connection to the TMS server"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TMS")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_tms)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    companion object {
        /**
         * Starts the MQTT foreground service.
         * Safe to call multiple times — Android delivers onStartCommand to an
         * already-running service without creating a duplicate instance.
         *
         * On Android 8.0+ MUST use startForegroundService(); the service has 5 seconds
         * to call startForeground() or the system throws ForegroundServiceTimeoutException.
         */
        fun start(context: Context) {
            val intent = Intent(context, TmsMqttService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the service. Normally not called — the service should run indefinitely. */
        fun stop(context: Context) {
            context.stopService(Intent(context, TmsMqttService::class.java))
        }
    }
}

// ── BootReceiver ──────────────────────────────────────────────────────────────

/**
 * Receives BOOT_COMPLETED and MY_PACKAGE_REPLACED to restart the MQTT service
 * after a device reboot or application self-update.
 *
 * Declared in AndroidManifest.xml with exported=true and both intent-filter actions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Boot/package-replaced — starting TmsMqttService")
            TmsMqttService.start(context)
        }
    }
}
