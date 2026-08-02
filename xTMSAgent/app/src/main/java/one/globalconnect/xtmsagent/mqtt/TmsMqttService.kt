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
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.R
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver
import one.globalconnect.xtmsagent.diagnostics.NexgoDiagnosticsManager
import one.globalconnect.xtmsagent.mqtt.downloads.DeviceOwnerPackageInstaller
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsHkScheduler
import one.globalconnect.xtmsagent.mqtt.persistence.TmsCredentialStore
import one.globalconnect.xtmsagent.mqtt.status.TmsLocationTracker
import one.globalconnect.xtmsagent.mqtt.status.TmsStatusWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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
        try {

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
        TmsTaskStatus.connecting(termId)
        TmsStatusWorker.schedule(this)
        TmsLocationTracker.start(this)
        TmsHkScheduler.scheduleIfNeeded(this)
        TmsMqttManager.connect()

        Log.i(TAG, "MQTT service started for $termId @ $brokerHost")

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { sc ->
            sc.launch {
                combine(TmsTaskStatus.taskOverride, TmsTaskStatus.connection) { task, connection ->
                    task ?: connection.text
                }.collect { statusText ->
                    try {
                        updateNotification(statusText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Unable to update MQTT notification", e)
                    }
                }
            }
        }
        } catch (e: Exception) {
            Log.e(TAG, "MQTT service initialization failed; service is stopping", e)
            serviceScope?.cancel()
            serviceScope = null
            stopSelf()
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
        runCatching { TmsLocationTracker.stop() }
            .onFailure { Log.w(TAG, "Location tracker cleanup failed", it) }
        runCatching { TmsMqttManager.disconnect() }
            .onFailure { Log.w(TAG, "MQTT cleanup failed", it) }
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
        fun start(context: Context): Boolean {
            val intent = Intent(context, TmsMqttService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start MQTT foreground service", e)
                false
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
            val wasUpdated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
            Log.i(
                "BootReceiver",
                "${if (wasUpdated) "Package replaced" else "Boot completed"} — " +
                    "restoring device policy and starting TmsMqttService",
            )

            TmsDeviceAdminReceiver.applyKioskRestrictions(context)

            if (wasUpdated) {
                DeviceOwnerPackageInstaller.reconcileSelfUpdate(context)
                TmsMqttManager.queueFullStatusReport(
                    context,
                    "xTMSAgent package replaced",
                )
            }

            if (!TmsMqttService.start(context)) {
                Log.e("BootReceiver", "MQTT service start was rejected by Android")
            }

            if (wasUpdated) {
                relaunchHomeAfterSelfUpdate(context)
            }
        }
    }

    /**
     * The package installer terminates the old xTMSAgent process while replacing it.
     * The replacement broadcast starts a fresh process, but Android does not recreate
     * the launcher Activity automatically. Launch it explicitly so a self-update does
     * not leave the operator on the previous launcher or a blank Home screen.
     */
    private fun relaunchHomeAfterSelfUpdate(context: Context) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
            )
            Log.i("BootReceiver", "xTMSAgent launcher restarted after self-update")
            NexgoDiagnosticsManager.record(
                context,
                "selfUpdateRestart success=true",
            )
        } catch (e: Exception) {
            Log.e("BootReceiver", "Unable to restart launcher after self-update", e)
            NexgoDiagnosticsManager.record(
                context,
                "selfUpdateRestart success=false error=${e.message}",
            )
        }
    }
}
