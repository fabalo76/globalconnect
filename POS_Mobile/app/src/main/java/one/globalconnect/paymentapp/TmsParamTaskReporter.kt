package one.globalconnect.paymentapp

import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "TmsParamTaskReporter"
private const val ACTION_PARAMS_RESULT = "one.globalconnect.paymentapp.ACTION_PARAMS_RESULT"
private const val EXTRA_TASK_ID = "taskId"
private const val EXTRA_RESULT_PACKAGE = "resultPackage"
private const val EXTRA_RESULT_STATUS = "status"
private const val EXTRA_ERROR_MESSAGE = "error_message"
private const val XTMS_PACKAGE_PREFIX = "one.globalconnect.xtmsagent"

internal data class TmsParamTaskContext(
    val taskId: String,
    val resultPackage: String,
)

internal object TmsParamTaskReporter {

    fun from(intent: Intent): TmsParamTaskContext? {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.trim().orEmpty()
        val resultPackage = intent.getStringExtra(EXTRA_RESULT_PACKAGE)?.trim().orEmpty()
        return if (taskId.isNotEmpty() && resultPackage.startsWith(XTMS_PACKAGE_PREFIX)) {
            TmsParamTaskContext(taskId, resultPackage)
        } else {
            null
        }
    }

    fun completed(context: Context, task: TmsParamTaskContext?) {
        report(context, task, status = "completed")
    }

    fun failed(context: Context, task: TmsParamTaskContext?, error: String) {
        report(context, task, status = "failed", message = error)
    }

    fun deferred(context: Context, task: TmsParamTaskContext?, message: String) {
        report(context, task, status = "deferred", message = message)
    }

    private fun report(
        context: Context,
        task: TmsParamTaskContext?,
        status: String,
        message: String? = null,
    ) {
        if (task == null) return
        context.sendBroadcast(
            Intent(ACTION_PARAMS_RESULT).apply {
                `package` = task.resultPackage
                putExtra(EXTRA_TASK_ID, task.taskId)
                putExtra(EXTRA_RESULT_STATUS, status)
                putExtra(EXTRA_ERROR_MESSAGE, message)
            },
        )
        Log.i(TAG, "Parameter task result sent taskId=${task.taskId} status=$status")
    }
}
