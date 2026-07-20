package one.globalconnect.xtmsagent.mqtt.housekeeping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TmsParamApplier"

/**
 * Sends the terminal's downloaded parameter file to the payment application
 * and awaits confirmation.
 *
 * Protocol:
 *  1. Sends broadcast [ACTION_PARAM_APPLY] with the param file path and task ID.
 *  2. Waits up to [CONFIRM_TIMEOUT_MS] for the payment app to reply with [ACTION_PARAM_APPLIED].
 *  3. If the payment app confirms, [onComplete] fires immediately.
 *  4. If the timeout expires first (or the payment app has no reply mechanism),
 *     [onComplete] fires anyway so the task is not permanently stuck.
 *
 * The payment application must:
 *  - Listen for [ACTION_PARAM_APPLY] broadcast (exported or same-process).
 *  - Apply the .dat.gz file from the extra [EXTRA_PARAM_FILE_PATH].
 *  - Optionally broadcast [ACTION_PARAM_APPLIED] with [EXTRA_TASK_ID] when done.
 *
 * If the payment app has no reply mechanism, the 60-second timeout provides a
 * fire-and-forget fallback so the HK task still completes.
 */
object TmsParamApplier {

    /** Broadcast sent TO the payment app to trigger parameter apply. */
    const val ACTION_PARAM_APPLY   = "com.uic.tms.PARAM_APPLY"

    /** Broadcast expected FROM the payment app confirming parameters were applied. */
    const val ACTION_PARAM_APPLIED = "com.uic.tms.PARAM_APPLIED"

    const val EXTRA_TASK_ID         = "taskId"
    const val EXTRA_PARAM_FILE_PATH = "paramFilePath"

    private const val CONFIRM_TIMEOUT_MS = 60_000L

    /**
     * Apply the parameter file from [paramFile] for [taskId].
     * [onComplete] is called exactly once — either on payment-app confirmation or timeout.
     */
    fun apply(context: Context, taskId: Int, paramFile: File, onComplete: () -> Unit) {
        if (!paramFile.exists()) {
            Log.w(TAG, "Task $taskId: param file not found at ${paramFile.absolutePath} — skipping apply")
            onComplete()
            return
        }

        val done = AtomicBoolean(false)

        // One-shot completion guard — ensures onComplete fires exactly once
        val complete: () -> Unit = {
            if (done.compareAndSet(false, true)) {
                onComplete()
            }
        }

        // Register receiver for payment-app confirmation
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val respondingTaskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
                if (respondingTaskId != taskId) return
                Log.i(TAG, "Task $taskId: param apply confirmed by payment app")
                try { ctx.unregisterReceiver(this) } catch (_: IllegalArgumentException) {}
                complete()
            }
        }

        val filter = IntentFilter(ACTION_PARAM_APPLIED)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Send broadcast to payment app
        val applyIntent = Intent(ACTION_PARAM_APPLY).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_PARAM_FILE_PATH, paramFile.absolutePath)
        }
        context.sendBroadcast(applyIntent)
        Log.i(TAG, "Task $taskId: param apply broadcast sent → ${paramFile.name}")

        // Timeout fallback — unblock task if payment app never replies
        Handler(Looper.getMainLooper()).postDelayed({
            if (!done.get()) {
                try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
                Log.w(TAG, "Task $taskId: param apply confirmation timeout — marking applied without ack")
                complete()
            }
        }, CONFIRM_TIMEOUT_MS)
    }
}
