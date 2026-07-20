package one.globalconnect.xtmsagent.mqtt.downloads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.mqtt.TmsMqttService

class AwsTaskApplyWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        TmsMqttService.start(applicationContext)
        val result = AwsDeviceDownloadManager.applyStagedTask(applicationContext, taskId)
        TmsMqttManager.publishExternalTaskAck(
            applicationContext,
            taskId,
            result.success,
            result.errorMessage,
            result.status,
            result.statusMessage
        )
        return if (result.success) Result.success() else Result.failure()
    }

    companion object {
        const val KEY_TASK_ID = "taskId"
    }
}
