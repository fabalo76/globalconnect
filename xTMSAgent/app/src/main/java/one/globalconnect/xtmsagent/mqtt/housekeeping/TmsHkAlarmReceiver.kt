package one.globalconnect.xtmsagent.mqtt.housekeeping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager

private const val TAG = "TmsHkAlarmReceiver"

/**
 * Receives the two HouseKeeping alarm actions fired by [AlarmManager]:
 *
 *  [ACTION_HK_DAILY]      — daily HouseKeeping window; triggers hkreq publish.
 *  [ACTION_HK_TASK_CHECK] — hourly (or precise) task-processor pass.
 *
 * Boot handling is done by the existing [BootReceiver] in TmsMqttService.kt, which
 * starts [TmsMqttService]; [TmsMqttService.onCreate] calls [TmsHkScheduler.scheduleIfNeeded].
 */
class TmsHkAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            ACTION_HK_DAILY -> {
                Log.i(TAG, "Daily HK alarm — triggering hkreq")
                TmsHkScheduler.onDailyAlarm(ctx)
                TmsMqttManager.triggerHouseKeeping(urgent = false)
            }
            ACTION_HK_TASK_CHECK -> {
                Log.i(TAG, "Task-check alarm — evaluating pending tasks")
                TmsTaskProcessor.checkAndProcess(ctx)
            }
        }
    }
}
