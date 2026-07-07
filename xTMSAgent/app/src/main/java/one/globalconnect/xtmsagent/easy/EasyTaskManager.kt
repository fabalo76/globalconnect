package one.globalconnect.xtmsagent.easy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.mqtt.TmsTaskStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

private const val TAG = "EasyTaskManager"

/** Ordered broadcast action used to query whether the payment app is ready to upgrade. */
private const val ACTION_QUERY_UPGRADE_READY = "com.tms.ACTION_QUERY_UPGRADE_READY"
/** Extra key: package name being installed. */
private const val EXTRA_PACKAGE_NAME         = "pkg"
/** Timeout for the ordered broadcast upgrade-readiness query (ms). */
private const val UPGRADE_QUERY_TIMEOUT_MS   = 5_000L

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS    = 180_000

/** Number of attempts before a network operation is considered permanently failed. */
private const val MAX_RETRIES = 10
/**
 * Delays between consecutive retry attempts (ms). Index 0 = delay before attempt 2, etc.
 * Escalates from 15 s → 30 s → 60 s → 120 s, then holds at 120 s for remaining retries.
 */
private val RETRY_DELAY_MS = longArrayOf(
    15_000L, 30_000L, 60_000L,
    120_000L, 120_000L, 120_000L, 120_000L, 120_000L, 120_000L
)

/**
 * Orchestrates the full Easy Download lifecycle for a terminal.
 *
 * Flow (called from TmsMqttManager when an easy-topic message arrives):
 *
 *   1. Parse [EasyTaskPacket] from MQTT payload.
 *   2. Persist task to [EasyTaskStore] (survives reboot).
 *   3. Publish easyack status=2 (Download Initiated).
 *   4. Fetch file list from EasyFileMeta.aspx.
 *   5. For each file:
 *      a. Download via EasyFileDownload.aspx → internal staging file.
 *      b. Non-APK: copy to Downfile directory; mark progress=2; publish ack.
 *      c. APK: query payment app for upgrade readiness via ordered broadcast.
 *         - Ready or not installed → install via Nexgo SDK (silent, no dialog).
 *         - Not ready (busy) → schedule retry after 60 s.
 *   6. [installViaNexgoSdk] callback fires per-APK → calls [onFileInstalled].
 *   7. When all files done: publish final ack (status=4 or 5).
 *
 * All network/IO work runs on [Dispatchers.IO]. Install callbacks arrive on
 * the broadcast receiver thread and re-enter via [onFileInstalled].
 */
object EasyTaskManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("EasyTaskManager"))

    /**
     * Entry point called by TmsMqttManager when a JSON payload arrives on the easy topic.
     * Parses the packet and starts the download pipeline on a background coroutine.
     */
    fun handleEasyPacket(context: Context, payload: ByteArray) {
        val packet = EasyTaskPacket.parse(payload)
        if (packet == null || packet.easyId <= 0) {
            Log.e(TAG, "Failed to parse easy task packet or easyId=0")
            return
        }
        Log.i(TAG, "Easy task received: easyId=${packet.easyId} f1=${packet.f1} f2=${packet.f2} f3=${packet.f3} f4=${packet.f4} para=${packet.enablePara}")

        // Persist immediately (before any IO that might fail)
        val existing = EasyTaskStore.load(packet.easyId)
        if (existing != null && existing.status >= 4) {
            // Task already completed — re-publish the last known status in case our previous
            // easyack was lost (e.g. MQTT dropped right after publish), which caused the server
            // to re-send the packet because MqttNotified was never set.
            Log.i(TAG, "Easy task easyId=${packet.easyId} already completed (status=${existing.status}) — re-publishing ack")
            scope.launch {
                TmsMqttManager.publishEasyAck(EasyAckReport(
                    easyId     = packet.easyId,
                    status     = existing.status,
                    paraStatus = if (existing.enablePara) existing.paraStatus else null
                ))
            }
            return
        }

        val task = EasyTaskState(
            easyId     = packet.easyId,
            enablePara = packet.enablePara,
            paraStatus = packet.paraStatus,
            status     = existing?.status ?: 1,
            files      = existing?.files  ?: emptyList()
        )
        EasyTaskStore.save(task)

        scope.launch { runTask(context.applicationContext, packet) }
    }

    /**
     * Resumes any Easy Download tasks that were in progress when the application was last stopped.
     *
     * Called once on startup after [EasyTaskStore.init]. Tasks at status 2 or 3 (download
     * initiated / ready to install) are re-queued so files not yet installed are retried.
     * Already-installed files (progress=2) are skipped inside [runTask].
     *
     * Status 1 tasks are left to the server: MqttNotified is still 0 so the poller will
     * re-deliver the packet on the next cycle. Status 4/5 tasks are complete — ignored.
     */
    fun resumeInProgressTasks(context: Context) {
        val tasks = EasyTaskStore.loadAll().filter { it.status in 2..3 }
        if (tasks.isEmpty()) return

        Log.i(TAG, "Resuming ${tasks.size} in-progress easy task(s) after restart")
        for (task in tasks) {
            // Reconstruct a minimal packet from persisted state. f1–f4 are entity IDs used
            // only for the initial MQTT log line; they are not needed to resume a task.
            val packet = EasyTaskPacket(
                easyId     = task.easyId,
                f1 = 0, f2 = 0, f3 = 0, f4 = 0,
                enablePara = task.enablePara,
                paraStatus = task.paraStatus
            )
            scope.launch { runTask(context.applicationContext, packet) }
        }
    }

    /**
     * Called by [installViaNexgoSdk]'s [com.nexgo.oaf.apiv3.OnAppOperatListener] callback
     * after a silent APK install completes. Updates file progress and checks whether
     * the whole task is now done.
     */
    fun onFileInstalled(@Suppress("UNUSED_PARAMETER") context: Context, easyId: Int, fileId: Int, success: Boolean) {
        scope.launch {
            val fileName = EasyTaskStore.load(easyId)?.files?.firstOrNull { it.fileId == fileId }?.fileName ?: "file $fileId"

            val progress = if (success) 2 else 3
            EasyTaskStore.updateFileProgress(easyId, fileId, progress)

            TmsTaskStatus.taskOverride.value = if (success) "Installed: $fileName" else "Install failed: $fileName"

            val task = EasyTaskStore.load(easyId) ?: run {
                delay(3_000)
                TmsTaskStatus.taskOverride.value = null
                return@launch
            }
            val allDone   = task.files.all { it.progress >= 2 }
            val anyFailed = task.files.any { it.progress == 3 }

            val finalStatus = when {
                !allDone    -> 2   // still in progress
                anyFailed   -> 5   // at least one file failed
                else        -> 4   // all installed
            }

            if (allDone) {
                EasyTaskStore.updateStatus(easyId, finalStatus)
            }

            val ack = EasyAckReport(
                easyId     = easyId,
                status     = finalStatus,
                paraStatus = if (task.enablePara) task.paraStatus else null
            )
            TmsMqttManager.publishEasyAck(ack)

            if (allDone) {
                Log.i(TAG, "Easy task easyId=$easyId complete — final status=$finalStatus")
                if (finalStatus == 4) EasyTaskStore.remove(easyId)
                TmsMqttManager.publishFullStatusReport()
                delay(3_000)
                TmsTaskStatus.taskOverride.value = null
            }
        }
    }

    // ── Private pipeline ──────────────────────────────────────────────────────

    /**
     * Retries [block] up to [MAX_RETRIES] times, waiting [RETRY_DELAY_MS] between attempts.
     * Throws the last exception if all attempts fail.
     */
    private suspend fun <T> withRetry(label: String, block: () -> T): T {
        var lastEx: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                return block()
            } catch (e: Exception) {
                lastEx = e
                if (attempt < MAX_RETRIES) {
                    val delayMs = RETRY_DELAY_MS.getOrElse(attempt - 1) { RETRY_DELAY_MS.last() }
                    Log.w(TAG, "$label attempt $attempt/$MAX_RETRIES failed: ${e.message}. Retrying in ${delayMs / 1_000}s…")
                    delay(delayMs)
                } else {
                    Log.e(TAG, "$label failed after $MAX_RETRIES attempts: ${e.message}")
                }
            }
        }
        throw lastEx!!
    }

    private suspend fun runTask(context: Context, packet: EasyTaskPacket) {
        val easyId = packet.easyId

        // Report Download Initiated
        EasyTaskStore.updateStatus(easyId, 2)
        TmsMqttManager.publishEasyAck(EasyAckReport(easyId, status = 2,
            paraStatus = if (packet.enablePara) packet.paraStatus else null))

        // Fetch the file list from the server — retry up to MAX_RETRIES times before giving up.
        val files: List<EasyFileInfo> = try {
            withRetry("fetchFileMeta(easyId=$easyId)") { fetchFileMeta(easyId) }
        } catch (e: Exception) {
            EasyTaskStore.updateStatus(easyId, 5)
            TmsMqttManager.publishEasyAck(EasyAckReport(easyId, status = 5))
            return
        }

        if (files.isEmpty()) {
            Log.w(TAG, "No files returned for easyId=$easyId — marking complete")
            EasyTaskStore.updateStatus(easyId, 4)
            TmsMqttManager.publishEasyAck(EasyAckReport(easyId, status = 4))
            EasyTaskStore.remove(easyId)
            return
        }

        // Populate file list in store (skip files already finished)
        val existing = EasyTaskStore.load(easyId) ?: return
        val newFiles = files.map { fi ->
            existing.files.firstOrNull { it.fileId == fi.fileId }
                ?: EasyFileState(fileId = fi.fileId, fileName = fi.fileName, slot = fi.slot)
        } + existing.files.filter { it.fileId !in files.map { f -> f.fileId }.toSet() }
        EasyTaskStore.save(existing.copy(files = newFiles, status = 2))

        // Process each file
        for (fi in files) {
            val state = EasyTaskStore.load(easyId)?.files?.firstOrNull { it.fileId == fi.fileId }
            if (state?.progress == 2) {
                Log.d(TAG, "Skipping already-installed file ${fi.fileId} in easyId=$easyId")
                continue
            }

            try {
                processFile(context, easyId, fi)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing file ${fi.fileId} in easyId=$easyId: ${e.message}", e)
                EasyTaskStore.updateFileProgress(easyId, fi.fileId, progress = 3)
            }
        }

        // Check if all non-APK files are done (APK installs complete via installViaNexgoSdk callback)
        val task = EasyTaskStore.load(easyId) ?: return
        val hasApks = task.files.any { it.isApk && it.progress < 2 }
        if (!hasApks) {
            val allDone   = task.files.all { it.progress >= 2 }
            val anyFailed = task.files.any { it.progress == 3 }
            val finalStatus = if (anyFailed) 5 else if (allDone) 4 else 2
            EasyTaskStore.updateStatus(easyId, finalStatus)
            TmsMqttManager.publishEasyAck(EasyAckReport(
                easyId     = easyId,
                status     = finalStatus,
                paraStatus = if (task.enablePara) task.paraStatus else null
            ))
            if (finalStatus == 4) {
                EasyTaskStore.remove(easyId)
                TmsMqttManager.publishFullStatusReport()
            }
            delay(3_000)
            TmsTaskStatus.taskOverride.value = null
        }
    }

    /** Downloads a file and dispatches to APK install or file copy. */
    private suspend fun processFile(context: Context, easyId: Int, fi: EasyFileInfo) {
        val stagingFile = stagingFile(context, easyId, fi.fileId)

        // Skip download only if the staging file is already fully on disk (size > 0).
        // A zero-length file indicates a failed partial download — treat as missing.
        if (stagingFile.exists() && stagingFile.length() > 0L) {
            Log.d(TAG, "fileId=${fi.fileId} already downloaded — reusing staging file")
        } else {
            Log.i(TAG, "Downloading fileId=${fi.fileId} (${fi.fileName}) for easyId=$easyId")
            TmsTaskStatus.taskOverride.value = "Downloading: ${fi.fileName}"
            withRetry("downloadFile(easyId=$easyId fileId=${fi.fileId})") {
                stagingFile.delete()
                downloadFile(easyId, fi.fileId, fi.fileName, fi.size, stagingFile)
            }
        }

        if (fi.isApk) {
            installApk(context, easyId, fi, stagingFile)
        } else {
            applyNonApkFile(context, easyId, fi, stagingFile)
        }
    }

    /**
     * For non-APK files (params, logos): copy to the TMS external download directory
     * using the original file name so the existing housekeeping cycle can pick them up.
     * Triggers a housekeeping cycle after placing the files.
     */
    private fun applyNonApkFile(@Suppress("UNUSED_PARAMETER") context: Context, easyId: Int, fi: EasyFileInfo, stagingFile: File) {
        try {
            val destDir = File(MainActivity.vg_sExtrenalPath).also { it.mkdirs() }
            val dest = File(destDir, fi.fileName)
            stagingFile.copyTo(dest, overwrite = true)
            EasyTaskStore.updateFileProgress(easyId, fileId = fi.fileId, progress = 2)
            Log.i(TAG, "Applied non-APK file ${fi.fileId} (${fi.fileName}) → ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply non-APK file ${fi.fileId}: ${e.message}")
            EasyTaskStore.updateFileProgress(easyId, fileId = fi.fileId, progress = 3)
        }
    }

    /**
     * Handles APK installation for Easy Download tasks.
     *
     * If the package is already installed, sends an ordered broadcast to query
     * whether the payment app (or any app that handles the query) is ready to upgrade.
     * Times out after [UPGRADE_QUERY_TIMEOUT_MS] ms and proceeds with install.
     * If the package is not installed, installs immediately.
     */
    private suspend fun installApk(context: Context, easyId: Int, fi: EasyFileInfo, apkFile: File) {
        // Detect installed package name from APK file (use fileName without extension as heuristic;
        // full parsing would require an APK parser library not currently in the project)
        val installedPkg = findInstalledPackageForApk(context, fi.fileName)
        Log.d(TAG, "installApk: ${fi.fileName} installedPkg=$installedPkg")

        if (installedPkg != null) {
            Log.i(TAG, "APK ${fi.fileName} replaces installed package $installedPkg — querying upgrade readiness")
            val ready = withContext(Dispatchers.Main) { queryUpgradeReadiness(context, installedPkg) }
            Log.i(TAG, "Upgrade readiness for $installedPkg: ready=$ready")
            if (!ready) {
                Log.w(TAG, "Payment app $installedPkg not ready for upgrade — scheduling retry in 60s")
                delay(60_000L)
                installApk(context, easyId, fi, apkFile)
                return
            }
        }

        TmsTaskStatus.taskOverride.value = "Installing: ${fi.fileName}"
        Log.i(TAG, "Installing APK ${fi.fileName} via Nexgo SDK (easyId=$easyId fileId=${fi.fileId})")
        installViaNexgoSdk(context, easyId, fi, apkFile)
        // Result delivered asynchronously via OnAppOperatListener → onFileInstalled()
    }

    /**
     * Installs an APK silently using the Nexgo Platform SDK.
     * No user confirmation dialog is shown. The [com.nexgo.oaf.apiv3.OnAppOperatListener]
     * callback fires when the install completes and routes directly into [onFileInstalled].
     */
    private fun installViaNexgoSdk(context: Context, easyId: Int, fi: EasyFileInfo, apkFile: File) {
        // The Nexgo system installer runs under a different UID and cannot read files from
        // the app's private internal storage (/data/user/0/.../files/...).
        // Copy to external cache (world-readable) with an explicit .apk extension first.
        val installApk: File = run {
            val extCache = context.getExternalCacheDir()
                ?: context.cacheDir  // fallback to internal cache if SD not mounted
            val dest = File(extCache, "easy_${easyId}_${fi.fileId}.apk")
            apkFile.copyTo(dest, overwrite = true)
            dest.setReadable(true, false)  // world-readable
            dest
        }

        Log.d(TAG, "installViaNexgoSdk: staging=${apkFile.absolutePath} install=${installApk.absolutePath} size=${installApk.length()}")

        try {
            val platform = com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(context).platform
            Log.d(TAG, "installViaNexgoSdk: platform=$platform — calling installApp()")
            val sdkResult = platform.installApp(
                installApk.absolutePath,
                object : com.nexgo.oaf.apiv3.OnAppOperatListener {
                    override fun onOperatResult(res: Int) {
                        Log.i(TAG, "Nexgo SDK onOperatResult: easyId=$easyId fileId=${fi.fileId} res=$res (Success=${com.nexgo.oaf.apiv3.SdkResult.Success})")
                        installApk.delete()  // clean up external copy
                        val success = res == com.nexgo.oaf.apiv3.SdkResult.Success
                        onFileInstalled(context, easyId, fi.fileId, success)
                    }
                }
            )
            Log.i(TAG, "installViaNexgoSdk: installApp() returned sdkResult=$sdkResult (Success=${com.nexgo.oaf.apiv3.SdkResult.Success})")
            if (sdkResult != com.nexgo.oaf.apiv3.SdkResult.Success) {
                Log.e(TAG, "Nexgo SDK installApp returned $sdkResult immediately for ${fi.fileName} — marking failed")
                installApk.delete()
                onFileInstalled(context, easyId, fi.fileId, success = false)
            } else {
                Log.i(TAG, "Nexgo SDK install initiated from ${installApk.absolutePath} — waiting for onOperatResult callback…")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nexgo SDK install threw for ${fi.fileName}: ${e.message}", e)
            installApk.delete()
            onFileInstalled(context, easyId, fi.fileId, success = false)
        }
    }

    /**
     * Sends an ordered broadcast and waits up to [UPGRADE_QUERY_TIMEOUT_MS] ms for a response.
     *
     * Any installed app that handles [ACTION_QUERY_UPGRADE_READY] can veto the upgrade
     * by calling [BroadcastReceiver.abortBroadcast] (resultCode = RESULT_CANCELED).
     * If no app responds within the timeout, upgrade proceeds (returns true).
     *
     * Must be called on the main thread (sendOrderedBroadcast).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun queryUpgradeReadiness(context: Context, packageName: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            var handled = false
            val timeoutHandler = Handler(Looper.getMainLooper())

            val resultReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (handled) return
                    handled = true
                    timeoutHandler.removeCallbacksAndMessages(null)
                    val ready = resultCode != android.app.Activity.RESULT_CANCELED
                    Log.d(TAG, "Upgrade readiness query for $packageName: ready=$ready")
                    continuation.resume(ready)
                }
            }

            val timeoutRunnable = Runnable {
                if (!handled) {
                    handled = true
                    Log.d(TAG, "Upgrade readiness query timed out for $packageName — assuming ready")
                    continuation.resume(true)
                }
            }

            val broadcastIntent = Intent(ACTION_QUERY_UPGRADE_READY).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }

            context.sendOrderedBroadcast(broadcastIntent, null, resultReceiver, null,
                android.app.Activity.RESULT_OK, null, null)

            timeoutHandler.postDelayed(timeoutRunnable, UPGRADE_QUERY_TIMEOUT_MS)

            continuation.invokeOnCancellation {
                timeoutHandler.removeCallbacksAndMessages(null)
            }
        }

    // ── Network helpers ───────────────────────────────────────────────────────

    /** Fetches file metadata from EasyFileMeta.aspx. */
    private fun fetchFileMeta(easyId: Int): List<EasyFileInfo> {
        val cfg    = TMSFunc.tmsCfg
        val termId = cfg.sn
        val secret = cfg.download_secret
        val url    = "${cfg.webScheme}://${cfg.server_addr}:${cfg.web_port}/Download/EasyFileMeta.aspx"

        val apiKey = computeHmacKey(termId, secret)
        val body   = """{"tid":"${termId.jsonEscape()}","key":"${apiKey.jsonEscape()}","easyId":$easyId}"""

        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.doInput  = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("EasyFileMeta HTTP ${conn.responseCode}: $err")
            }

            val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            EasyFileInfo.parseList(JSONObject(responseText))
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads a file from EasyFileDownload.aspx to [dest], posting download progress. */
    private fun downloadFile(easyId: Int, fileId: Int, fileName: String, totalSize: Long, dest: File) {
        val cfg    = TMSFunc.tmsCfg
        val termId = cfg.sn
        val secret = cfg.download_secret
        val url    = "${cfg.webScheme}://${cfg.server_addr}:${cfg.web_port}/Download/EasyFileDownload.aspx"

        val apiKey = computeHmacKey(termId, secret)
        val body   = """{"tid":"${termId.jsonEscape()}","key":"${apiKey.jsonEscape()}","easyId":$easyId,"fileId":$fileId}"""

        dest.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.doInput  = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("EasyFileDownload HTTP ${conn.responseCode}: $err")
            }

            val knownSize = if (totalSize > 0L) totalSize else conn.contentLengthLong
            var bytesWritten = 0L
            var lastPct = -1
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        bytesWritten += n
                        if (knownSize > 0) {
                            val pct = (bytesWritten * 100L / knownSize).toInt().coerceAtMost(100)
                            if (pct != lastPct) {
                                lastPct = pct
                                TmsTaskStatus.taskOverride.value = "Downloading: $fileName $pct%"
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Downloaded fileId=$fileId (${dest.length()} bytes) → ${dest.absolutePath}")
        } finally {
            conn.disconnect()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the internal staging path for a downloaded easy file. */
    private fun stagingFile(context: Context, easyId: Int, fileId: Int): File {
        val dir = File(context.filesDir, "easy_staging")
        dir.mkdirs()
        return File(dir, "easy_${easyId}_${fileId}.dat")
    }

    /**
     * Tries to find an installed package that corresponds to the given APK file name.
     * Matches against the last component of the app_name field in MainActivity.appList,
     * which typically contains the package name or app label.
     *
     * Returns null if no match or if the package is not installed.
     */
    private fun findInstalledPackageForApk(context: Context, fileName: String): String? {
        val nameWithoutExt = fileName.removeSuffix(".apk")
        return try {
            val pm = context.packageManager
            // Check direct package name match first (e.g. "com.uic.app.apk" → "com.uic.app")
            pm.getPackageInfo(nameWithoutExt, 0)
            nameWithoutExt
        } catch (e: Exception) {
            // Try matching against the running app list from MainActivity
            MainActivity.appList.firstOrNull { app ->
                app.app_package_name.contains(nameWithoutExt, ignoreCase = true) ||
                nameWithoutExt.contains(app.app_package_name, ignoreCase = true)
            }?.app_package_name
        }
    }

    private fun computeHmacKey(termId: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(termId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
}
