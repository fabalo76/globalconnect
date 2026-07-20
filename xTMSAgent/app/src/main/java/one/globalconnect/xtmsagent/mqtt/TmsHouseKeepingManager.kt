package one.globalconnect.xtmsagent.mqtt

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.mqtt.housekeeping.ST_READY
import one.globalconnect.xtmsagent.mqtt.housekeeping.ST_ACTIVATED
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsHkScheduler
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsStoredFileSet
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsStoredFile
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsStoredLogo
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsStoredTask
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsTaskProcessor
import one.globalconnect.xtmsagent.mqtt.housekeeping.TmsTaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val TAG = "TmsHouseKeepingMgr"

/**
 * Manages the MQTT HouseKeeping flow (hkreq → hkresp).
 *
 * Responsibilities:
 *   - Build and publish hkreq payloads (terminal identity + task statuses from [TmsTaskStore])
 *   - Parse hkresp: persist new tasks with full metadata into [TmsTaskStore]
 *   - Clean up locally activated tasks that the server no longer includes (confirmed effective)
 *   - Delegate all download/activation scheduling to [TmsTaskProcessor] / [TmsHkScheduler]
 *
 * Download scheduling and file operations are NOT performed here — they are owned
 * by [TmsTaskProcessor], which is triggered by [TmsHkScheduler]'s task-check alarm.
 */
object TmsHouseKeepingManager {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            one.globalconnect.xtmsagent.loggingCoroutineExceptionHandler(TAG)
    )

    // ── Outbound ──────────────────────────────────────────────────────────────

    /**
     * Builds and publishes an hkreq payload via [publishFn].
     * Called by [TmsMqttManager.triggerHouseKeeping].
     */
    fun triggerHouseKeeping(context: Context, publishFn: (ByteArray) -> Boolean) {
        scope.launch {
            val payload = buildHkReq(context)
            if (!publishFn(payload)) {
                Log.w(TAG, "hkreq not sent — MQTT not connected")
            } else {
                Log.i(TAG, "hkreq published (${payload.size} bytes)")
            }
        }
    }

    private fun buildHkReq(context: Context): ByteArray {
        val json = JSONObject().apply {
            put("stan",  nextStan(context))
            put("info",  buildInfoObj(context))
            put("tasks", TmsTaskStore.buildHkReqTasksJson(context))
            put("stats", buildStatsObj())
            put("vchk",  TmsTaskStore.loadVchk(context))
        }
        val raw        = json.toString().toByteArray(Charsets.UTF_8)
        val compressed = gzip(raw)
        Log.d(TAG, "hkreq: ${raw.size} B → ${compressed.size} B (gzip)")
        return compressed
    }

    private fun buildInfoObj(context: Context): JSONObject = JSONObject().apply {
        put("serial", TMSFunc.tmsCfg.sn)
        readImei(context, 0)?.let  { put("imei", it) }
        readIccid(context, 0)?.let { put("sim1", it) }
        readIccid(context, 1)?.let { put("sim2", it) }
        readLocalIp()?.let         { put("ip", it) }
    }

    private fun buildStatsObj(): JSONObject = JSONObject().apply {
        put("pktIn",     0)
        put("pktOut",    0)
        put("swipeOk",   0)
        put("swipeFail", 0)
        put("ctOk",      0)
        put("ctFail",    0)
        put("clssOk",    0)
        put("clssFail",  0)
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    /**
     * Called by [TmsMqttManager] when an hkresp arrives on the subscribed topic.
     *
     * - Saves the VChk ID so the next hkreq carries it back.
     * - Upserts each pending task into [TmsTaskStore] with full file metadata.
     * - Removes locally ST_ACTIVATED tasks absent from the server list (server confirmed them).
     * - Triggers [TmsHkScheduler.scheduleTaskCheck] so [TmsTaskProcessor] picks up new tasks.
     */
    fun handleHkResp(context: Context, payload: ByteArray) {
        if (payload.isEmpty()) return
        val json = try {
            val data = if (payload.size >= 2 &&
                payload[0] == 0x1F.toByte() && payload[1] == 0x8B.toByte())
                gunzip(payload) else payload
            JSONObject(String(data, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse hkresp: ${e.message}")
            return
        }

        val rc = json.optInt("rc", -1)
        Log.i(TAG, "hkresp received — rc=$rc tasks=${json.optJSONArray("tasks")?.length() ?: 0} vchk=${json.optInt("vchk", 0)}")
        Log.d(TAG, "hkresp JSON: $json")
        if (rc != 0) {
            Log.w(TAG, "hkresp rc=$rc — server reported error, skipping")
            return
        }

        val vchk = json.optInt("vchk", 0)
        if (vchk > 0) {
            TmsTaskStore.saveVchk(context, vchk)
            Log.i(TAG, "hkresp vchk=$vchk stored")
        }

        val serverTasks   = json.optJSONArray("tasks") ?: JSONArray()
        val serverTaskIds = mutableSetOf<Int>()

        // Snapshot existing task IDs before upserting so we can detect genuinely new arrivals
        val existingTaskIds = TmsTaskStore.loadTasks(context).map { it.id }.toSet()

        for (i in 0 until serverTasks.length()) {
            val obj  = serverTasks.optJSONObject(i) ?: continue
            val task = parseTask(obj) ?: continue
            serverTaskIds.add(task.id)

            val isNew = task.id !in existingTaskIds
            Log.i(TAG, "hkresp task[${i+1}/${serverTasks.length()}]: " +
                "id=${task.id} ttid=${obj.optInt("ttid")} " +
                "sched=${task.sched} eff=${task.eff} " +
                "sys=${task.sys?.name ?: "-"} prog=${task.prog?.name ?: "-"} " +
                "hdr=${task.hdr?.fid ?: "-"} trl=${task.trl?.fid ?: "-"} " +
                if (isNew) "[NEW]" else "[UPDATE]")

            // New task: clear any stale staging files left from a previous cycle with this ID
            if (isNew) {
                TmsTaskProcessor.clearStaging(context, task.id)
            }

            TmsTaskStore.upsert(context, task)
        }

        // Server stopped sending a task we have marked as activated → confirmed effective → remove
        TmsTaskStore.loadTasks(context)
            .filter { it.status == ST_ACTIVATED && it.id !in serverTaskIds }
            .forEach { t ->
                TmsTaskStore.removeTask(context, t.id)
                Log.i(TAG, "Task ${t.id} confirmed effective by server — removed from local store")
            }

        // GC: remove staging dirs for any task ID no longer in the active list
        TmsTaskProcessor.gcStagingDirs(context)

        val newCount = serverTaskIds.size
        if (newCount == 0) {
            Log.i(TAG, "hkresp: no pending tasks")
        } else {
            Log.i(TAG, "hkresp: $newCount task(s) — scheduling task check")
            MainActivity.writeLog("MQTT HK response: $newCount task(s)")
            TmsHkScheduler.scheduleTaskCheck(context)
        }
    }

    // ── hkresp task parsing ───────────────────────────────────────────────────

    private fun parseTask(obj: JSONObject): TmsStoredTask? {
        val id = obj.optInt("id", 0)
        if (id == 0) return null
        return TmsStoredTask(
            id     = id,
            type   = obj.optInt("type", 0),
            status = ST_READY,
            sched  = obj.optString("sched", ""),
            eff    = obj.optString("eff",   ""),
            hdr    = obj.optJSONObject("hdr")?.let  { parseLogo(it) },
            trl    = obj.optJSONObject("trl")?.let  { parseLogo(it) },
            sys    = obj.optJSONObject("sys")?.let  { parseFileSet(it) },
            prog   = obj.optJSONObject("prog")?.let { parseFileSet(it) },
            paraDl = obj.optBoolean("paraDl", false)
        )
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
            ver   = o.optString("ver",  ""),
            files = files
        )
    }

    // ── STAN counter ──────────────────────────────────────────────────────────

    private fun nextStan(context: Context): String {
        val prefs = context.getSharedPreferences("tms_hk", android.content.Context.MODE_PRIVATE)
        val current = prefs.getInt("stan", 0)
        val next = if (current >= 999_999) 1 else current + 1
        prefs.edit().putInt("stan", next).apply()
        return next.toString().padStart(6, '0')
    }

    // ── Device info helpers ───────────────────────────────────────────────────

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readImei(context: Context, slotIndex: Int): String? = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tm.getImei(slotIndex)
        } else {
            if (slotIndex == 0) @Suppress("DEPRECATION") tm.deviceId else null
        }
        imei?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    private fun readIccid(context: Context, slotIndex: Int): String? = try {
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager
        @Suppress("DEPRECATION")
        sm.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
            ?.iccId?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun readLocalIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    } catch (_: Exception) { null }

    // ── Compression helpers ───────────────────────────────────────────────────

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
