package one.globalconnect.xtmsagent.easy

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "EasyTaskStore"
private const val STORE_FILE = "easy_tasks.json"

/**
 * Per-file download/install progress within an easy task.
 * Stored as part of [EasyTaskState].
 */
data class EasyFileState(
    val fileId: Int,
    val fileName: String,
    val slot: Int,
    /**
     * 0 = pending download
     * 1 = downloaded (ready to install / apply)
     * 2 = installed / applied successfully
     * 3 = failed
     */
    val progress: Int = 0
) {
    val isApk: Boolean get() = fileName.endsWith(".apk", ignoreCase = true)

    fun toJson(): JSONObject = JSONObject().apply {
        put("fileId",   fileId)
        put("fileName", fileName)
        put("slot",     slot)
        put("progress", progress)
    }

    companion object {
        fun fromJson(obj: JSONObject) = EasyFileState(
            fileId   = obj.getInt("fileId"),
            fileName = obj.getString("fileName"),
            slot     = obj.getInt("slot"),
            progress = obj.optInt("progress", 0)
        )
    }
}

/**
 * The persisted state for one Easy Download task assigned to this terminal.
 * Survives reboots — written to internal storage as JSON.
 */
data class EasyTaskState(
    val easyId: Int,
    val enablePara: Boolean,
    val paraStatus: Int,
    /**
     * Overall task status from the server's perspective:
     *   1=Created, 2=DownloadInitiated, 3=ReadyToInstall, 4=Installed, 5=Error
     */
    val status: Int = 1,
    /** Per-file progress list; populated after EasyFileMeta is fetched. */
    val files: List<EasyFileState> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("easyId",     easyId)
        put("enablePara", enablePara)
        put("paraStatus", paraStatus)
        put("status",     status)
        val arr = JSONArray()
        files.forEach { arr.put(it.toJson()) }
        put("files", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): EasyTaskState {
            val filesArr = obj.optJSONArray("files") ?: JSONArray()
            val files = (0 until filesArr.length()).map {
                EasyFileState.fromJson(filesArr.getJSONObject(it))
            }
            return EasyTaskState(
                easyId     = obj.getInt("easyId"),
                enablePara = obj.optBoolean("enablePara", false),
                paraStatus = obj.optInt("paraStatus", 0),
                status     = obj.optInt("status", 1),
                files      = files
            )
        }
    }
}

/**
 * Persistent store for Easy Download tasks.
 *
 * Stores a list of [EasyTaskState] objects as JSON in the app's internal files directory.
 * All mutations are written synchronously to disk so no in-flight work is lost on reboot.
 *
 * Thread-safety: all public methods are @Synchronized.
 */
object EasyTaskStore {

    private lateinit var storeFile: File

    fun init(context: Context) {
        storeFile = File(context.filesDir, STORE_FILE)
    }

    /** Returns all persisted tasks. */
    @Synchronized
    fun loadAll(): List<EasyTaskState> {
        if (!::storeFile.isInitialized || !storeFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(storeFile.readText(Charsets.UTF_8))
            (0 until arr.length()).map { EasyTaskState.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tasks: ${e.message}")
            emptyList()
        }
    }

    /** Returns a task by easyId, or null if not found. */
    @Synchronized
    fun load(easyId: Int): EasyTaskState? = loadAll().firstOrNull { it.easyId == easyId }

    /** Saves or replaces the task with the same easyId. */
    @Synchronized
    fun save(task: EasyTaskState) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.easyId == task.easyId }
        if (idx >= 0) all[idx] = task else all += task
        write(all)
        Log.d(TAG, "Saved task easyId=${task.easyId} status=${task.status}")
    }

    /** Removes the task with the given easyId (called after status=4 or 5 is confirmed). */
    @Synchronized
    fun remove(easyId: Int) {
        val all = loadAll().filter { it.easyId != easyId }
        write(all)
        Log.d(TAG, "Removed task easyId=$easyId")
    }

    /** Updates the progress of a specific file within a task. */
    @Synchronized
    fun updateFileProgress(easyId: Int, fileId: Int, progress: Int) {
        val task = load(easyId) ?: return
        val newFiles = task.files.map {
            if (it.fileId == fileId) it.copy(progress = progress) else it
        }
        save(task.copy(files = newFiles))
    }

    /** Updates the overall task status. */
    @Synchronized
    fun updateStatus(easyId: Int, status: Int) {
        val task = load(easyId) ?: return
        save(task.copy(status = status))
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun write(tasks: List<EasyTaskState>) {
        try {
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            storeFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write tasks: ${e.message}")
        }
    }
}
