package one.globalconnect.paymentapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

private const val TAG = "TmsParamsUpdateNotifier"
// Channel importance is immutable after Android creates the channel. Use a new ID when
// migrating the original default-importance channel to a heads-up-capable channel.
private const val CHANNEL_ID = "tms_parameter_update_alerts"
private const val LEGACY_CHANNEL_ID = "tms_parameter_updates"
private const val UPDATED_NOTIFICATION_ID = 1002
private const val PENDING_SETTLEMENT_NOTIFICATION_ID = 1003

internal fun shouldPostParamsUpdateNotification(mainActivityVisible: Boolean): Boolean =
    !mainActivityVisible

/**
 * Shows the parameter-update result as a temporary heads-up banner and in the status bar
 * when the payment UI is not visible.
 * Foreground updates continue to use [GlobalConnectPaymentApplication.paramsUpdateEvent]
 * and the Compose confirmation dialog in [MainActivity].
 */
object TmsParamsUpdateNotifier {

    @Volatile
    private var mainActivityVisible = false

    fun setMainActivityVisible(visible: Boolean) {
        mainActivityVisible = visible
    }

    fun notifyWhenMainActivityClosed(context: Context): Boolean {
        if (!shouldPostParamsUpdateNotification(mainActivityVisible)) {
            return false
        }
        if (!canPostNotifications(context)) {
            Log.w(TAG, "Parameters updated while MainActivity was closed, but notifications are disabled")
            return false
        }

        createChannel(context)
        val openPaymentApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            UPDATED_NOTIFICATION_ID,
            openPaymentApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = context.getString(R.string.setting_initialize_success_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_tms)
            .setContentTitle(context.getString(R.string.setting_initialize_success_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        return try {
            postNotification(context, UPDATED_NOTIFICATION_ID, notification)
            Log.i(TAG, "Parameters-updated heads-up notification posted")
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission was revoked before the update notice was posted", error)
            false
        }
    }

    fun notifyPendingSettlementWhenMainActivityClosed(context: Context): Boolean {
        if (!shouldPostParamsUpdateNotification(mainActivityVisible)) {
            return false
        }
        if (!canPostNotifications(context)) {
            Log.w(TAG, "A parameter update is pending settlement, but notifications are disabled")
            return false
        }

        createChannel(context)
        val openPaymentApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            PENDING_SETTLEMENT_NOTIFICATION_ID,
            openPaymentApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = context.getString(R.string.pending_param_update_msg)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_tms)
            .setContentTitle(context.getString(R.string.pending_param_update_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        return try {
            postNotification(context, PENDING_SETTLEMENT_NOTIFICATION_ID, notification)
            Log.i(TAG, "Pending parameter-update settlement notification posted")
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission was revoked before the pending notice was posted", error)
            false
        }
    }

    fun cancelPendingSettlementNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(PENDING_SETTLEMENT_NOTIFICATION_ID)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.tms_parameter_updates_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.tms_parameter_updates_channel_description)
                    setShowBadge(false)
                    enableVibration(true)
                },
            )
        }
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(
        context: Context,
        notificationId: Int,
        notification: android.app.Notification,
    ) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
