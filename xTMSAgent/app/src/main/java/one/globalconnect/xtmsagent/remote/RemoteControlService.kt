package one.globalconnect.xtmsagent.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.R

private const val TAG = "RemoteControlService"
private const val NOTIF_ID = 8501
private const val CHANNEL_ID = "remote_control"

class RemoteControlService : Service() {

    companion object {
        const val ACTION_START = "one.globalconnect.xtmsagent.remote.START"
        const val ACTION_STOP = "one.globalconnect.xtmsagent.remote.STOP"

        const val EXTRA_CONFIG_JSON = "remoteControlConfigJson"
        const val EXTRA_TERMINAL_ID = "terminalId"
        const val EXTRA_PROJECTION_RESULT = "projectionResult"
        const val EXTRA_PROJECTION_DATA = "projectionData"
    }

    private var manager: RemoteControlManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (one.globalconnect.xtmsagent.recovery.StartupRecoveryGuard.inRecovery) { stopSelf(); return START_NOT_STICKY }
        try {
            when (intent?.action) {
                ACTION_START -> startRemoteSession(intent)
                ACTION_STOP -> {
                    Log.i(TAG, "Received ACTION_STOP")
                    manager?.stop()
                    manager = null
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remote control command failed; session is stopping", e)
            runCatching { manager?.stop() }
            manager = null
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { manager?.stop() }
            .onFailure { Log.w(TAG, "Remote control cleanup failed", it) }
        manager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRemoteSession(intent: Intent) {
        startForeground(NOTIF_ID, buildNotification())

        val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: run {
            Log.e(TAG, "ACTION_START missing remoteControlConfigJson")
            stopSelf()
            return
        }
        val terminalId = intent.getStringExtra(EXTRA_TERMINAL_ID) ?: run {
            Log.e(TAG, "ACTION_START missing terminalId")
            stopSelf()
            return
        }
        @Suppress("DEPRECATION")
        val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA) ?: run {
            Log.e(TAG, "ACTION_START missing projectionData")
            stopSelf()
            return
        }

        val config = try {
            RemoteControlConfig.fromJson(configJson, terminalId)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid remote control config: ${e.message}")
            stopSelf()
            return
        }

        manager = RemoteControlManager(
            context = this,
            config = config,
            projectionData = projectionData,
            onSessionEnded = { stopSelf() },
        )
        try {
            manager?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start remote control manager", e)
            runCatching { manager?.stop() }
            manager = null
            stopSelf()
            return
        }
        Log.i(TAG, "Kinesis remote control session started for $terminalId")
    }

    private fun buildNotification() = run {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Remote Control", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Active Global Connect remote control session" },
            )
        }

        val stopIntent = Intent(this, RemoteControlService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Screen sharing active")
            .setContentText("Global Connect remote control session in progress")
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }
}
