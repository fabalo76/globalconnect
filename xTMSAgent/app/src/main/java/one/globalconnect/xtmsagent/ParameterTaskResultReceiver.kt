package one.globalconnect.xtmsagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import one.globalconnect.xtmsagent.params.ParamConstants
import one.globalconnect.xtmsagent.params.ParamManager

private const val TAG = "ParameterTaskResult"

class ParameterTaskResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (one.globalconnect.xtmsagent.recovery.StartupRecoveryGuard.inRecovery) return
        if (intent.action != ParamConstants.ACTION_PARAMS_RESULT) return

        val taskId = intent.getStringExtra(ParamConstants.EXTRA_TASK_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (taskId == null) {
            Log.w(TAG, "Ignoring parameter result without a task ID")
            return
        }

        val status = intent.getStringExtra(ParamConstants.EXTRA_RESULT_STATUS)
        val error = intent.getStringExtra(ParamConstants.EXTRA_ERROR_MESSAGE)
        Log.i(TAG, "Payment app parameter result taskId=$taskId status=$status error=${error ?: "(none)"}")
        ParamManager.onParameterApplicationResult(
            context = context.applicationContext,
            taskId = taskId,
            status = status,
            error = error,
        )
    }
}
