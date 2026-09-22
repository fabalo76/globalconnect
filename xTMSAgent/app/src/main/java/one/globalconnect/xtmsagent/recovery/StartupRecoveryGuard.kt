package one.globalconnect.xtmsagent.recovery

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import one.globalconnect.xtmsagent.BuildConfig

object StartupRecoveryGuard {
    @Volatile var inRecovery = false
        private set
    private var state = StartupFailureState()
    private val healthyToken = Any()
    private val healthyHandler by lazy { Handler(Looper.getMainLooper()) }
    private const val PREFS = "startup_failure_guard"

    /** Runs in attachBaseContext, before Android creates content providers. */
    @Synchronized fun install(context: Context) {
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            state = StartupFailureState(p.getLong("started", 0), p.getInt("pid", 0),
                p.getInt("version", 0), p.getBoolean("counted", false),
                p.getString("failures", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() },
                p.getBoolean("recovery", false), p.getBoolean("retryArmed", false))
            val exit = if (Build.VERSION.SDK_INT >= 30 && state.started != 0L && !state.counted) runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, state.pid, 10)
                    .firstOrNull { it.timestamp >= state.started && it.processName == context.packageName }
            }.getOrNull() else null
            state = state.begin(System.currentTimeMillis(), Process.myPid(), BuildConfig.VERSION_CODE,
                exit?.timestamp, exit?.reason)
            save(context)
        } catch (error: Exception) {
            inRecovery = true
            RecoveryApplication.record(context, "startupGuard storage/read failure: ${error.stackTraceToString()}")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                failed(context)
                RecoveryApplication.record(context, "startupCrash thread=${thread.name}\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
        RecoveryApplication.record(context, "startupGuard recovery=$inRecovery failures=${state.failures.size} attempt=${state.started}")
    }

    @Synchronized private fun failed(context: Context) {
        state = state.failed(System.currentTimeMillis())
        save(context)
    }

    /** Main-thread callback must run after normal bootstrap/UI has returned. */
    @Synchronized fun monitorHealthyStartup(context: Context) {
        if (inRecovery) return
        if (state.started == 0L) {
            state = state.begin(System.currentTimeMillis(), Process.myPid(), BuildConfig.VERSION_CODE, null, null)
            save(context)
        }
        val attempt = state.started
        healthyHandler.removeCallbacksAndMessages(healthyToken)
        healthyHandler.postDelayed({
            synchronized(this) {
                val next = state.healthy(attempt)
                if (next != state) {
                    state = next
                    runCatching { save(context) }
                    RecoveryApplication.record(context, "startupGuard stable=true attempt=$attempt")
                }
            }
        }, healthyToken, 60_000)
    }

    @Synchronized fun allowOneRetry(context: Context) {
        state = state.retry().begin(System.currentTimeMillis(), Process.myPid(), BuildConfig.VERSION_CODE, null, null)
        save(context)
        RecoveryApplication.record(context, "startupGuard operatorRetry=true failuresRetained=${state.failures.size}")
    }

    private fun save(context: Context) {
        inRecovery = state.recovery
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("started", state.started).putInt("pid", state.pid).putInt("version", state.version)
            .putBoolean("counted", state.counted).putBoolean("recovery", state.recovery)
            .putBoolean("retryArmed", state.retryArmed)
            .putString("failures", state.failures.joinToString(",")).commit()) { "Unable to save startup guard" }
    }
}
