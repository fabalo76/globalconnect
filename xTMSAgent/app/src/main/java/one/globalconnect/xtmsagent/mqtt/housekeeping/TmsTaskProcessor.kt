package one.globalconnect.xtmsagent.mqtt.housekeeping

import android.content.Context
import android.content.Intent
import android.util.Log
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.download.TmsFileDownloader
import one.globalconnect.xtmsagent.mqtt.ACTION_HOUSEKEEPING_COMPLETE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.Date

private const val TAG = "TmsTaskProcessor"

/**
 * Downloads and activates HouseKeeping tasks according to their [TmsStoredTask.sched]
 * and [TmsStoredTask.eff] timestamps.
 *
 * Staging area: [Context.getFilesDir]/hk_tasks/{taskId}/
 *   hdr.png, trl.png, sys/{fn}, {apk}.apk, param/{termId}.dat.gz
 *
 * Download flow (at sched):
 *   Download all task assets (logos, sys files, APKs, param file) → ST_DOWNLOADED
 *   Param download sets [TmsStoredTask.paramDownloaded] = true in the store.
 *
 * Activation flow (at eff):
 *   1. Apply hdr/trl logos and sys files (synchronous file copies)
 *   2. Install prog APKs (async, fire-and-forget) → [TmsStoredTask.progActivated] = true
 *   3. If paraDl: apply param file to payment app via [TmsParamApplier]
 *      → [TmsStoredTask.paramActivated] = true on confirmation or 60 s timeout
 *   4. [checkAndFinalizeTask]: when all components activated → ST_ACTIVATED
 *
 * Cleanup: staging directory deleted when task reaches ST_ACTIVATED.
 */
object TmsTaskProcessor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Evaluate all stored tasks and act on those whose time has come.
     * Called by [TmsHkAlarmReceiver] for both hourly checks and precise task alarms.
     */
    fun checkAndProcess(context: Context) {
        scope.launch {
            val now   = Date()
            val tasks = TmsTaskStore.loadTasks(context)
            var anyChanged = false

            for (task in tasks) {
                when (task.status) {

                    ST_READY, ST_PARTIAL -> {
                        if (schedDue(task.sched, now)) {
                            Log.i(TAG, "Task ${task.id}: downloading (sched=${task.sched})")
                            val ok = downloadTask(context, task)
                            val newSt = if (ok) ST_DOWNLOADED else ST_PARTIAL
                            TmsTaskStore.updateStatus(context, task.id, newSt)
                            Log.i(TAG, "Task ${task.id}: status → $newSt")
                            anyChanged = true

                            if (ok && effDue(task.eff, now)) {
                                // eff also past — activate immediately; load fresh task
                                // so paramDownloaded flag is reflected
                                val fresh = TmsTaskStore.loadTasks(context)
                                    .firstOrNull { it.id == task.id }
                                if (fresh != null) activateTask(context, fresh)
                            }
                        }
                    }

                    ST_DOWNLOADED -> {
                        if (effDue(task.eff, now)) {
                            Log.i(TAG, "Task ${task.id}: activating (eff=${task.eff})")
                            activateTask(context, task)
                            anyChanged = true
                        }
                    }
                    // ST_ACTIVATED: wait for server confirmation in next hkresp → cleanup
                }
            }

            if (anyChanged) {
                context.sendBroadcast(
                    Intent(ACTION_HOUSEKEEPING_COMPLETE)
                        .apply { `package` = context.packageName }
                )
            }

            TmsHkScheduler.scheduleTaskCheck(context)
        }
    }

    // ── Download phase ────────────────────────────────────────────────────────

    private fun downloadTask(context: Context, task: TmsStoredTask): Boolean {
        val staging = stagingDir(context, task.id).also { it.mkdirs() }
        var allOk = true

        task.hdr?.takeIf { it.fn.isNotBlank() }?.let { logo ->
            val dest = File(staging, "hdr.png")
            val rc = TmsFileDownloader.downloadFile("H", "", "", logo.fn, "", dest.absolutePath)
            if (rc != 0) { Log.w(TAG, "Task ${task.id}: hdr failed rc=$rc"); allOk = false }
            else Log.i(TAG, "Task ${task.id}: hdr downloaded")
        }

        task.trl?.takeIf { it.fn.isNotBlank() }?.let { logo ->
            val dest = File(staging, "trl.png")
            val rc = TmsFileDownloader.downloadFile("T", "", "", logo.fn, "", dest.absolutePath)
            if (rc != 0) { Log.w(TAG, "Task ${task.id}: trl failed rc=$rc"); allOk = false }
            else Log.i(TAG, "Task ${task.id}: trl downloaded")
        }

        task.sys?.files?.forEach { f ->
            if (f.fn.isBlank()) return@forEach
            val dest = File(staging, "sys/${f.fn}").also { it.parentFile?.mkdirs() }
            val rc = TmsFileDownloader.downloadFile(
                "S", task.sys.name, task.sys.ver, f.fn, f.fv, dest.absolutePath)
            if (rc != 0) { Log.w(TAG, "Task ${task.id}: sys/${f.fn} failed rc=$rc"); allOk = false }
            else Log.i(TAG, "Task ${task.id}: sys/${f.fn} downloaded")
        }

        task.prog?.files?.forEach { f ->
            if (f.fn.isBlank()) return@forEach
            val dest = File(staging, f.fn)
            val rc = TmsFileDownloader.downloadFile(
                "P", task.prog.name, task.prog.ver, f.fn, f.fv, dest.absolutePath)
            if (rc != 0) { Log.w(TAG, "Task ${task.id}: prog/${f.fn} failed rc=$rc"); allOk = false }
            else Log.i(TAG, "Task ${task.id}: prog/${f.fn} downloaded (${dest.length()} B)")
        }

        if (task.paraDl) {
            val termId = TMSFunc.tmsCfg.sn
            val paramDir  = File(staging, "param").also { it.mkdirs() }
            val paramDest = File(paramDir, "$termId.dat.gz")
            val rc = TmsFileDownloader.downloadParamFile("dat", paramDest.absolutePath)
            if (rc != 0) {
                Log.w(TAG, "Task ${task.id}: param download failed rc=$rc")
                allOk = false
            } else {
                Log.i(TAG, "Task ${task.id}: param downloaded (${paramDest.length()} B)")
                TmsTaskStore.setParamDownloaded(context, task.id)
            }
        }

        return allOk
    }

    // ── Activation phase ──────────────────────────────────────────────────────

    private fun activateTask(context: Context, task: TmsStoredTask) {
        val staging = stagingDir(context, task.id)

        // 1. Apply logos (synchronous)
        task.hdr?.let {
            File(staging, "hdr.png").takeIf { it.exists() }?.let { src ->
                val dest = File("${MainActivity.vg_sIntrenalPath}/cfg/brandlogo.png")
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = true)
                Log.i(TAG, "Task ${task.id}: header logo applied → ${dest.absolutePath}")
            }
        }

        task.trl?.let {
            File(staging, "trl.png").takeIf { it.exists() }?.let { src ->
                val dest = File("${MainActivity.vg_sIntrenalPath}/cfg/uiclogo.png")
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = true)
                Log.i(TAG, "Task ${task.id}: trailer logo applied → ${dest.absolutePath}")
            }
        }

        // 2. Apply sys files (synchronous)
        task.sys?.files?.forEach { f ->
            File(staging, "sys/${f.fn}").takeIf { it.exists() }?.let { src ->
                val dest = File(MainActivity.vg_sExtrenalPath, "sys/${f.fn}")
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = true)
                Log.i(TAG, "Task ${task.id}: sys/${f.fn} applied")
            }
        }

        // 3. Install prog APKs — fire-and-forget; mark progActivated immediately
        task.prog?.files?.forEach { f ->
            File(staging, f.fn).takeIf { it.exists() }?.let { apk -> installApk(context, apk) }
        }
        if (task.prog != null) {
            TmsTaskStore.setProgActivated(context, task.id)
            Log.i(TAG, "Task ${task.id}: APK install initiated — progActivated")
        }

        // 4. Apply params to payment app (after prog step)
        if (task.paraDl) {
            if (task.paramDownloaded) {
                val termId    = TMSFunc.tmsCfg.sn
                val paramFile = File(stagingDir(context, task.id), "param/$termId.dat.gz")
                Log.i(TAG, "Task ${task.id}: sending params to payment app")
                TmsParamApplier.apply(context, task.id, paramFile) {
                    TmsTaskStore.setParamActivated(context, task.id)
                    Log.i(TAG, "Task ${task.id}: paramActivated")
                    checkAndFinalizeTask(context, task.id)
                }
                // Staging cleanup deferred — param dir needed until TmsParamApplier callback
                staging.listFiles()?.forEach { f -> if (f.name != "param") f.deleteRecursively() }
                return  // finalization happens async in the param apply callback
            } else {
                // Param file was not downloaded (partial download) — skip apply, mark done anyway
                Log.w(TAG, "Task ${task.id}: paraDl=true but param not in staging — marking paramActivated without apply")
                TmsTaskStore.setParamActivated(context, task.id)
            }
        }

        // No async param step — clean staging and finalize synchronously
        staging.deleteRecursively()
        checkAndFinalizeTask(context, task.id)
    }

    /**
     * Moves the task to ST_ACTIVATED when all component flags are satisfied.
     * Safe to call from any thread; touches shared store with internal synchronization.
     */
    private fun checkAndFinalizeTask(context: Context, taskId: Int) {
        val task = TmsTaskStore.loadTasks(context).firstOrNull { it.id == taskId } ?: return
        if (!task.allComponentsActivated()) {
            Log.d(TAG, "Task $taskId: not all components activated yet " +
                "(progActivated=${task.progActivated}, paramActivated=${task.paramActivated})")
            return
        }
        TmsTaskStore.updateStatus(context, taskId, ST_ACTIVATED)
        stagingDir(context, taskId).deleteRecursively()
        Log.i(TAG, "Task $taskId: all components activated → ST_ACTIVATED")
        context.sendBroadcast(
            Intent(ACTION_HOUSEKEEPING_COMPLETE).apply { `package` = context.packageName }
        )
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val extCache = context.externalCacheDir ?: context.cacheDir
            val installFile = File(extCache, apkFile.name)
            apkFile.copyTo(installFile, overwrite = true)
            installFile.setReadable(true, false)

            val platform = com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(context).platform
            val rc = platform.installApp(installFile.absolutePath,
                object : com.nexgo.oaf.apiv3.OnAppOperatListener {
                    override fun onOperatResult(res: Int) {
                        val ok = res == com.nexgo.oaf.apiv3.SdkResult.Success
                        Log.i(TAG, "APK install ${if (ok) "OK" else "FAILED (res=$res)"}: ${apkFile.name}")
                        if (ok) installFile.delete()
                    }
                })
            if (rc != com.nexgo.oaf.apiv3.SdkResult.Success)
                Log.e(TAG, "installApp() returned $rc immediately for ${apkFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "APK install exception for ${apkFile.name}: ${e.message}")
        }
    }

    // ── Time helpers ──────────────────────────────────────────────────────────

    private fun schedDue(sched: String, now: Date): Boolean =
        sched.isEmpty() || parseDate(sched)?.let { !it.after(now) } ?: true

    private fun effDue(eff: String, now: Date): Boolean =
        eff.isEmpty() || parseDate(eff)?.let { !it.after(now) } ?: true

    private fun parseDate(s: String): Date? = try {
        if (s.length != 14) null
        else Calendar.getInstance().apply {
            set(s.substring(0, 4).toInt(),
                s.substring(4, 6).toInt() - 1,
                s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(),
                s.substring(10, 12).toInt(),
                s.substring(12, 14).toInt())
            set(Calendar.MILLISECOND, 0)
        }.time
    } catch (_: Exception) { null }

    // ── Staging GC ────────────────────────────────────────────────────────────

    fun gcStagingDirs(context: Context) {
        val activeIds = TmsTaskStore.loadTasks(context).map { it.id }.toSet()
        val hkRoot = File(context.filesDir, "hk_tasks")
        hkRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val id = dir.name.toIntOrNull() ?: return@forEach
            if (id !in activeIds) {
                dir.deleteRecursively()
                Log.i(TAG, "GC: deleted orphaned staging dir for task $id")
            }
        }
    }

    fun clearStaging(context: Context, taskId: Int) {
        val dir = File(context.filesDir, "hk_tasks/$taskId")
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "GC: cleared stale staging dir for incoming task $taskId")
        }
    }

    private fun stagingDir(context: Context, taskId: Int) =
        File(context.filesDir, "hk_tasks/$taskId")
}
