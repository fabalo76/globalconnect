package one.globalconnect.xtmsagent.mqtt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingTaskAck(
    val taskId: String,
    val success: Boolean,
    val errorMessage: String?,
    val status: String?,
    val statusMessage: String?
)

object PendingTaskAckStore {
    private const val PREFS = "pending_task_acks"
    private const val KEY_ITEMS = "items"

    @Synchronized
    fun enqueue(
        context: Context,
        taskId: String,
        success: Boolean,
        errorMessage: String?,
        status: String?,
        statusMessage: String?
    ) {
        val items = read(context).filterNot { it.taskId == taskId && it.status == status }.toMutableList()
        items += PendingTaskAck(taskId, success, errorMessage, status, statusMessage)
        write(context, items.takeLast(100))
    }

    @Synchronized
    fun takeAll(context: Context): List<PendingTaskAck> {
        val items = read(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
        return items
    }

    private fun read(context: Context): List<PendingTaskAck> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    PendingTaskAck(
                        taskId = item.optString("taskId"),
                        success = item.optBoolean("success"),
                        errorMessage = item.optNullableString("errorMessage"),
                        status = item.optNullableString("status"),
                        statusMessage = item.optNullableString("statusMessage")
                    ).takeIf { it.taskId.isNotBlank() }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, items: List<PendingTaskAck>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject()
                .put("taskId", item.taskId)
                .put("success", item.success)
                .put("errorMessage", item.errorMessage ?: JSONObject.NULL)
                .put("status", item.status ?: JSONObject.NULL)
                .put("statusMessage", item.statusMessage ?: JSONObject.NULL))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
