package one.globalconnect.xtmsagent.mqtt.housekeeping

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import one.globalconnect.xtmsagent.launcher.LauncherConfigManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "TmsHkScheduler"

private const val PREFS_NAME     = "tms_hk_scheduler"
private const val KEY_SLOT_HOUR  = "slot_hour"
private const val KEY_SLOT_MIN   = "slot_min"
private const val KEY_LAST_HK_DAY = "last_hk_day"   // yyyyMMdd of last successful hkreq

/** Broadcast action — AlarmManager fires this when the daily HK slot arrives. */
const val ACTION_HK_DAILY      = "one.globalconnect.xtmsagent.HK_DAILY"

/** Broadcast action — AlarmManager fires this for hourly/precise task-check passes. */
const val ACTION_HK_TASK_CHECK = "one.globalconnect.xtmsagent.HK_TASK_CHECK"

private const val WINDOW_START_HOUR = 8    // inclusive
private const val WINDOW_END_HOUR   = 18   // exclusive (last slot at 17:xx)

private const val REQUEST_HK_DAILY  = 100
private const val REQUEST_TASK_CHECK = 101

private val DAY_FMT = SimpleDateFormat("yyyyMMdd", Locale.US)

/**
 * Manages two independent AlarmManager alarms:
 *
 *  1. **Daily HK alarm** — fires once per day at a randomly chosen time in the
 *     08:00–18:00 window.  The time is picked on first call and persisted so it
 *     remains stable across reboots.  If the device was off when the slot passed,
 *     the alarm fires 30 seconds after the scheduler starts.
 *
 *  2. **Task-check alarm** — fires every hour (or earlier when a task is due soon)
 *     so [TmsTaskProcessor] can download and activate tasks close to their
 *     scheduled/effective times.
 */
object TmsHkScheduler {

    /**
     * Must be called from [TmsMqttService.onCreate] and after each [BootReceiver] fires.
     * Idempotent — safe to call multiple times.
     */
    fun scheduleIfNeeded(context: Context) {
        ensureTimeSlot(context)
        scheduleDailyAlarm(context)
        scheduleTaskCheck(context)
    }

    /** Called by [TmsHkAlarmReceiver] when the daily alarm fires. */
    fun onDailyAlarm(context: Context) {
        markHkRan(context)
        scheduleDailyAlarm(context)   // arm for tomorrow
        scheduleTaskCheck(context)    // re-arm task check
    }

    /**
     * Called when HouseKeeping is triggered by the deferred mechanism (after LauncherConfig
     * is applied on a fresh install).  Marks HK as run today and re-arms the daily alarm for
     * tomorrow — this replaces the pending 5-min catch-up alarm via the same PendingIntent
     * request code, preventing a redundant second HK run when the catch-up alarm fires.
     */
    fun onDeferredHkTriggered(context: Context) {
        markHkRan(context)
        scheduleDailyAlarm(context)
    }

    /**
     * Re-evaluates all stored tasks and sets the task-check alarm at whichever
     * comes first: 1 hour from now, or the nearest upcoming sched/eff time.
     *
     * Called after every hkresp (new tasks arrived) and after every task-check
     * run (statuses changed).
     */
    fun scheduleTaskCheck(context: Context) {
        val now = System.currentTimeMillis()
        val oneHour = 60L * 60_000L
        var fireAt = now + oneHour

        TmsTaskStore.loadTasks(context).forEach { task ->
            val targetStr = when (task.status) {
                ST_READY, ST_PARTIAL -> task.sched
                ST_DOWNLOADED        -> task.eff
                else                 -> return@forEach
            }
            val t = parseEpochMs(targetStr) ?: return@forEach
            when {
                t <= now  -> fireAt = now + 5_000L  // overdue — check in 5 s
                t < fireAt -> fireAt = t
            }
        }

        setExact(context, REQUEST_TASK_CHECK,
            Intent(ACTION_HK_TASK_CHECK).apply { `package` = context.packageName },
            fireAt)

        Log.d(TAG, "Task-check alarm in ${(fireAt - now) / 1_000}s")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun ensureTimeSlot(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_SLOT_HOUR)) return
        val hour = WINDOW_START_HOUR +
                (Math.random() * (WINDOW_END_HOUR - WINDOW_START_HOUR)).toInt()
        val min  = (Math.random() * 60).toInt()
        p.edit().putInt(KEY_SLOT_HOUR, hour).putInt(KEY_SLOT_MIN, min).apply()
        Log.i(TAG, "HK time slot chosen: %02d:%02d".format(hour, min))
    }

    private fun scheduleDailyAlarm(context: Context) {
        val p       = prefs(context)
        val hour    = p.getInt(KEY_SLOT_HOUR, WINDOW_START_HOUR)
        val min     = p.getInt(KEY_SLOT_MIN, 0)
        val lastDay = p.getString(KEY_LAST_HK_DAY, "")
        val today   = DAY_FMT.format(java.util.Date())
        val now     = System.currentTimeMillis()

        val slotToday = slotEpoch(hour, min, offsetDays = 0)

        val fireAt = when {
            // Slot has passed today and HK hasn't run — fire in 30 s (catch-up),
            // but only if LauncherConfig is already applied.  On fresh install the
            // deferred-HK mechanism will trigger HK as soon as the config is ready;
            // fall back to 5 min here so the alarm doesn't race the download.
            lastDay != today && slotToday <= now -> {
                if (LauncherConfigManager.isConfigApplied(context)) {
                    Log.i(TAG, "HK slot %02d:%02d passed and HK not yet run today — catch-up in 30s"
                        .format(hour, min))
                    now + 30_000L
                } else {
                    Log.i(TAG, "HK catch-up deferred 5 min — LauncherConfig not yet applied")
                    now + 300_000L
                }
            }
            // Slot is still in the future today
            slotToday > now -> slotToday
            // Already ran today — arm for tomorrow
            else -> slotEpoch(hour, min, offsetDays = 1)
        }

        setExact(context, REQUEST_HK_DAILY,
            Intent(ACTION_HK_DAILY).apply { `package` = context.packageName },
            fireAt)

        Log.i(TAG, "Daily HK alarm: %02d:%02d — fires in ${(fireAt - now) / 60_000} min"
            .format(hour, min))
    }

    private fun markHkRan(context: Context) {
        prefs(context).edit()
            .putString(KEY_LAST_HK_DAY, DAY_FMT.format(java.util.Date()))
            .apply()
    }

    private fun slotEpoch(hour: Int, min: Int, offsetDays: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, offsetDays)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun parseEpochMs(s: String): Long? = try {
        if (s.length == 14) {
            Calendar.getInstance().apply {
                set(s.substring(0, 4).toInt(),
                    s.substring(4, 6).toInt() - 1,
                    s.substring(6, 8).toInt(),
                    s.substring(8, 10).toInt(),
                    s.substring(10, 12).toInt(),
                    s.substring(12, 14).toInt())
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else null
    } catch (_: Exception) { null }

    private fun setExact(context: Context, requestCode: Int, intent: Intent, fireAt: Long) {
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
