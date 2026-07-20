package one.globalconnect.pinpad

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import one.globalconnect.pinpad.comms.PinpadSerialService
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.licensing.PinpadLicenseManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext as PinpadApplication
            if (!PinpadLicenseManager.isAuthorized(context, app.deviceInfoProvider.serialNumber())) {
                launchMainActivity(context)
                return
            }
            val serviceIntent = Intent(context, PinpadSerialService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }.onSuccess {
                PinpadTraceLog.service("boot receiver started serial service")
            }.onFailure {
                Log.w(TAG, "Unable to start PINPAD serial service after boot", it)
                PinpadTraceLog.service("boot receiver service start failed=${it.message}")
            }
            launchMainActivity(context)
            scheduleMainActivityLaunchRetry(context)
        }
    }

    private fun launchMainActivity(context: Context) {
        val launchIntent = mainActivityIntent(context)
        runCatching {
            context.startActivity(launchIntent)
        }.onSuccess {
            PinpadTraceLog.service("boot receiver launched main activity")
        }.onFailure {
            Log.w(TAG, "Unable to launch PINPAD main activity after boot", it)
            PinpadTraceLog.service("boot receiver main launch failed=${it.message}")
        }
    }

    private fun scheduleMainActivityLaunchRetry(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context,
            BOOT_LAUNCH_REQUEST_CODE,
            mainActivityIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + BOOT_LAUNCH_RETRY_MS,
                pendingIntent,
            )
        }.onSuccess {
            PinpadTraceLog.service("boot receiver scheduled main activity retry")
        }.onFailure {
            Log.w(TAG, "Unable to schedule PINPAD main activity launch retry", it)
            PinpadTraceLog.service("boot receiver main retry failed=${it.message}")
        }
    }

    private fun mainActivityIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
    }

    private companion object {
        private const val TAG = "PinpadBootReceiver"
        private const val BOOT_LAUNCH_REQUEST_CODE = 2001
        private const val BOOT_LAUNCH_RETRY_MS = 2_000L
    }
}
