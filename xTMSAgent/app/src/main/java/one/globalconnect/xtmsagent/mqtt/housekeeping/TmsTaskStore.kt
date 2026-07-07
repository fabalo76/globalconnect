package one.globalconnect.xtmsagent.mqtt.housekeeping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Terminal-side task status codes (mirrors server Dn_TaskTerm.TaskStatus)
const val ST_READY      = 1   // received, download not started
const val ST_PARTIAL    = 2   // download in progress or incomplete
const val ST_DOWNLOADED = 3   // all files downloaded, not yet applied
const val ST_ACTIVATED  = 4   // applied and effective

data class TmsStoredFile(val fid: Int, val fn: String, val fv: String)

data class TmsStoredFileSet(
    val name: String,
    val ver: String,
    val files: List<TmsStoredFile>
)

data class TmsStoredLogo(val fid: Int, val fn: String)

/**
 * Full task record persisted locally.
 *
 * [sched] and [eff] are yyyyMMddHHmmss strings from the server.
 * [sched] = earliest download time; [eff] = time to apply the downloaded assets.
 * Empty string means "as soon as possible".
 *
 * Per-component activation flags allow independent tracking of the multi-step
 * activation sequence (prog APK install → param apply → ST_ACTIVATED):
 *  - [paraDl]         server flag: parameter download is enabled for this task
 *  - [paramDownloaded] param file staged locally (set after download phase)
 *  - [progActivated]  APK install was initiated (set at activation time)
 *  - [paramActivated] params delivered to payment app and confirmed (or timed out)
 *
 * [allComponentsActivated] returns true when all present components are done,
 * which is when the task may move to ST_ACTIVATED.
 */
data class TmsStoredTask(
    val id: Int,
    val type: Int,
    val status: Int,
    val sched: String,
    val eff: String,
    val hdr: TmsStoredLogo?,
    val trl: TmsStoredLogo?,
    val sys: TmsStoredFileSet?,
    val prog: TmsStoredFileSet?,
    val paraDl: Boolean = false,
    val paramDownloaded: Boolean = false,
    val progActivated: Boolean = false,
    val paramActivated: Boolean = false
) {
    fun withStatus(newStatus: Int) = copy(status = newStatus)

    fun allComponentsActivated(): Boolean {
        val progDone  = prog == null || progActivated
        val paramDone = !paraDl || paramActivated
        return progDone && paramDone
    }
}

/**
 * Single source of truth for HouseKeeping task state.
 *
 * Uses SharedPreferences key "tasks_v2" (full metadata).
 */
object TmsTaskStore {

    private const val PREFS_NAME = "tms_hk"
    private const val KEY_TASKS  = "tasks_v2"
    private const val KEY_VCHK   = "vchk"

    // ── Tasks ─────────────────────────────────────────────────────────────────

    fun loadTasks(context: Context): MutableList<TmsStoredTask> {
        val stored = prefs(context).getString(KEY_TASKS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(stored)
            (0 until arr.length()).mapNotNull { parseTask(arr.optJSONObject(it)) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveTasks(context: Context, tasks: List<TmsStoredTask>) {
        val arr = JSONArray().also { a -> tasks.forEach { a.put(toJson(it)) } }
        prefs(context).edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    /**
     * Insert or replace by task ID.
     * Status is never downgraded. Component activation flags are OR-merged so a
     * server re-send of an already-activating task does not reset in-progress flags.
     */
    fun upsert(context: Context, incoming: TmsStoredTask) {
        val tasks = loadTasks(context)
        val idx = tasks.indexOfFirst { it.id == incoming.id }
        if (idx >= 0) {
            val existing = tasks[idx]
            tasks[idx] = incoming.copy(
                status         = maxOf(existing.status, incoming.status),
                paramDownloaded = existing.paramDownloaded || incoming.paramDownloaded,
                progActivated   = existing.progActivated   || incoming.progActivated,
                paramActivated  = existing.paramActivated  || incoming.paramActivated
            )
        } else {
            tasks.add(incoming)
        }
        saveTasks(context, tasks)
    }

    fun updateStatus(context: Context, taskId: Int, status: Int) {
        updateTask(context, taskId) { it.withStatus(status) }
    }

    fun setParamDownloaded(context: Context, taskId: Int) {
        updateTask(context, taskId) { it.copy(paramDownloaded = true) }
    }

    fun setProgActivated(context: Context, taskId: Int) {
        updateTask(context, taskId) { it.copy(progActivated = true) }
    }

    fun setParamActivated(context: Context, taskId: Int) {
        updateTask(context, taskId) { it.copy(paramActivated = true) }
    }

    fun removeTask(context: Context, taskId: Int) {
        saveTasks(context, loadTasks(context).filter { it.id != taskId })
    }

    /** Build the minimal task JSON array for inclusion in an hkreq payload. */
    fun buildHkReqTasksJson(context: Context): JSONArray =
        JSONArray().also { arr ->
            loadTasks(context).forEach { t ->
                arr.put(JSONObject().apply {
                    put("id",   t.id)
                    put("type", t.type)
                    put("st",   t.status)
                })
            }
        }

    // ── VChk ─────────────────────────────────────────────────────────────────

    fun loadVchk(context: Context): Int = prefs(context).getInt(KEY_VCHK, 0)

    fun saveVchk(context: Context, vchk: Int) {
        prefs(context).edit().putInt(KEY_VCHK, vchk).apply()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun updateTask(context: Context, taskId: Int, transform: (TmsStoredTask) -> TmsStoredTask) {
        val tasks = loadTasks(context)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) return
        tasks[idx] = transform(tasks[idx])
        saveTasks(context, tasks)
    }

    // ── JSON serialisation ────────────────────────────────────────────────────

    private fun parseTask(obj: JSONObject?): TmsStoredTask? {
        obj ?: return null
        return try {
            TmsStoredTask(
                id              = obj.getInt("id"),
                type            = obj.optInt("type", 0),
                status          = obj.optInt("st", ST_READY),
                sched           = obj.optString("sched", ""),
                eff             = obj.optString("eff", ""),
                hdr             = obj.optJSONObject("hdr")?.let  { parseLogo(it) },
                trl             = obj.optJSONObject("trl")?.let  { parseLogo(it) },
                sys             = obj.optJSONObject("sys")?.let  { parseFileSet(it) },
                prog            = obj.optJSONObject("prog")?.let { parseFileSet(it) },
                paraDl          = obj.optBoolean("paraDl",         false),
                paramDownloaded = obj.optBoolean("paramDl",        false),
                progActivated   = obj.optBoolean("progAct",        false),
                paramActivated  = obj.optBoolean("paramAct",       false)
            )
        } catch (_: Exception) { null }
    }

    private fun parseLogo(o: JSONObject) =
        TmsStoredLogo(fid = o.optInt("fid", 0), fn = o.optString("fn", ""))

    private fun parseFileSet(o: JSONObject): TmsStoredFileSet {
        val filesArr = o.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesArr.length()).mapNotNull { i ->
            filesArr.optJSONObject(i)?.let { f ->
                TmsStoredFile(
                    fid = f.optInt("fid", 0),
                    fn  = f.optString("fn", ""),
                    fv  = f.optString("fv", "")
                )
            }
        }
        return TmsStoredFileSet(
            name  = o.optString("name", ""),
            ver   = o.optString("ver", ""),
            files = files
        )
    }

    private fun toJson(t: TmsStoredTask): JSONObject = JSONObject().apply {
        put("id",    t.id)
        put("type",  t.type)
        put("st",    t.status)
        put("sched", t.sched)
        put("eff",   t.eff)
        t.hdr?.let  { put("hdr",  JSONObject().apply { put("fid", it.fid); put("fn", it.fn) }) }
        t.trl?.let  { put("trl",  JSONObject().apply { put("fid", it.fid); put("fn", it.fn) }) }
        t.sys?.let  { put("sys",  fileSetJson(it)) }
        t.prog?.let { put("prog", fileSetJson(it)) }
        if (t.paraDl)          put("paraDl",  true)
        if (t.paramDownloaded) put("paramDl", true)
        if (t.progActivated)   put("progAct", true)
        if (t.paramActivated)  put("paramAct", true)
    }

    private fun fileSetJson(fs: TmsStoredFileSet): JSONObject = JSONObject().apply {
        put("name", fs.name)
        put("ver",  fs.ver)
        put("files", JSONArray().also { a ->
            fs.files.forEach { f ->
                a.put(JSONObject().apply { put("fid", f.fid); put("fn", f.fn); put("fv", f.fv) })
            }
        })
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
