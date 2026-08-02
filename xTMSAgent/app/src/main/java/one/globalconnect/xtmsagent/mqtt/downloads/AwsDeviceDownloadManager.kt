package one.globalconnect.xtmsagent.mqtt.downloads

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.mqtt.TmsTaskStatus
import one.globalconnect.xtmsagent.net.DeviceApi
import one.globalconnect.xtmsagent.nexgo.NexgoSystemAsset
import one.globalconnect.xtmsagent.nexgo.NexgoSystemAssetInstaller
import one.globalconnect.xtmsagent.requirements.ApplicationRequirementNotifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "AwsDeviceDownload"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 300_000
private const val INSTALL_TIMEOUT_MS = 180_000L
private const val INSTALL_POLL_INTERVAL_MS = 1_000L
private const val INSTALL_RETRY_DELAY_MS = 5_000L
private const val INSTALL_ATTEMPTS = 2

object AwsDeviceDownloadManager {

    data class Result(
        val success: Boolean,
        val errorMessage: String? = null,
        val status: String? = null,
        val statusMessage: String? = null
    )

    suspend fun executeTask(
        context: Context,
        taskId: String,
        taskType: String,
        payload: JSONObject?
    ): Result = withContext(Dispatchers.IO) {
        try {
            ensureNotCancelled(context, taskId)
            val isFirmware = taskType.equals("FirmwareDownload", ignoreCase = true)
                || taskType.equals("UpdateFirmware", ignoreCase = true)
            val isBootAnimation = taskType.equals("BootAnimationDownload", ignoreCase = true)
            val isApplication = !isFirmware && !isBootAnimation
            val payloadPresentation = if (isApplication) {
                ApplicationPresentation.fromPayload(payload)
            } else {
                null
            }
            payloadPresentation?.let {
                TmsTaskStatus.taskOverride.value = context.getString(
                    one.globalconnect.xtmsagent.R.string.task_app_preparing,
                    it.label()
                )
            }
            val response = requestDownload(context, isFirmware, isBootAnimation, payload)
            val applicationPresentation = if (isApplication) {
                ApplicationPresentation.fromResponse(
                    response,
                    payloadPresentation,
                    context.getString(one.globalconnect.xtmsagent.R.string.task_application)
                )
            } else {
                null
            }
            val files = response.files.ifEmpty {
                listOf(
                    DownloadFile(
                        fileName = response.fileName,
                        url = response.url,
                        sha256 = response.sha256,
                        sizeBytes = response.sizeBytes
                    )
                )
            }

            val downloaded = files.map { file ->
                ensureNotCancelled(context, taskId)
                val localFile = downloadSignedFile(
                    context,
                    taskId,
                    file,
                    applicationPresentation?.label()
                )
                verifyFile(localFile, file)
                localFile
            }

            ensureNotCancelled(context, taskId)
            val effectiveAt = parseEffectiveAt(payload)
            if (effectiveAt != null && effectiveAt.isAfter(Instant.now())) {
                persistStagedTask(
                    context,
                    taskId,
                    taskType,
                    effectiveAt,
                    downloaded,
                    applicationPresentation
                )
                scheduleApply(context, taskId, effectiveAt)
                TmsTaskStatus.taskOverride.value = null
                return@withContext Result(
                    success = true,
                    status = "pendingEffective",
                    statusMessage = "Downloaded; waiting for effective time"
                )
            }

            applyDownloadedFiles(
                context,
                taskId,
                taskType,
                downloaded,
                payload,
                applicationPresentation
            )
            taskDirectory(context, taskId).deleteRecursively()
            if (applicationPresentation != null) {
                TmsTaskStatus.showTransient(
                    context.getString(
                        one.globalconnect.xtmsagent.R.string.task_app_installed,
                        applicationPresentation.label()
                    )
                )
            } else {
                TmsTaskStatus.taskOverride.value = null
            }
            Result(success = true, status = "applied")
        } catch (e: Exception) {
            if (isCancelled(context, taskId)) {
                Log.i(TAG, "AWS task $taskId cancelled")
                TmsTaskStatus.taskOverride.value = null
                return@withContext Result(success = true, status = "cancelled", statusMessage = "Task cancelled")
            }
            Log.e(TAG, "AWS download task failed: ${e.message}", e)
            MainActivity.writeLog(
                "AWS task failed: id=$taskId type=$taskType error=${e.message ?: "unknown"}"
            )
            val presentation = ApplicationPresentation.fromPayload(payload)
            if (presentation != null) {
                TmsTaskStatus.showTransient(
                    context.getString(
                        one.globalconnect.xtmsagent.R.string.task_app_failed,
                        presentation.label()
                    )
                )
            } else {
                TmsTaskStatus.taskOverride.value = null
            }
            Result(success = false, errorMessage = e.message ?: "Download task failed")
        }
    }

    fun cancelTask(context: Context, taskId: String): Boolean {
        if (taskId.isBlank()) return false
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId))
        val directory = taskDirectory(context, taskId)
        directory.deleteRecursively()
        directory.mkdirs()
        cancelledMarker(context, taskId).writeText(Instant.now().toString())
        Log.i(TAG, "Cancelled AWS task $taskId and removed staged artifacts")
        return true
    }

    suspend fun applyStagedTask(context: Context, taskId: String): Result = withContext(Dispatchers.IO) {
        try {
            ensureNotCancelled(context, taskId)
            val metadataFile = metadataFile(context, taskId)
            if (!metadataFile.isFile) return@withContext Result(false, "Staged task metadata not found")
            val metadata = JSONObject(metadataFile.readText())
            val taskType = metadata.getString("taskType")
            val names = metadata.getJSONArray("files")
            val files = (0 until names.length()).map { File(taskDirectory(context, taskId), names.getString(it)) }
            if (files.any { !it.isFile }) return@withContext Result(false, "One or more staged task files are missing")
            val presentation = ApplicationPresentation.fromMetadata(metadata)
            applyDownloadedFiles(context, taskId, taskType, files, presentation = presentation)
            taskDirectory(context, taskId).deleteRecursively()
            presentation?.let {
                TmsTaskStatus.showTransient(
                    context.getString(one.globalconnect.xtmsagent.R.string.task_app_installed, it.label())
                )
            }
            Result(true, status = "applied")
        } catch (e: Exception) {
            if (isCancelled(context, taskId)) {
                Result(true, status = "cancelled", statusMessage = "Task cancelled")
            } else {
                Log.e(TAG, "Unable to apply staged AWS task $taskId", e)
                Result(false, e.message ?: "Staged task apply failed")
            }
        }
    }

    private suspend fun applyDownloadedFiles(
        context: Context,
        taskId: String,
        taskType: String,
        downloaded: List<File>,
        payload: JSONObject? = null,
        presentation: ApplicationPresentation? = null,
    ) {
        ensureNotCancelled(context, taskId)
        val isFirmware = taskType.equals("FirmwareDownload", ignoreCase = true)
            || taskType.equals("UpdateFirmware", ignoreCase = true)
        val isBootAnimation = taskType.equals("BootAnimationDownload", ignoreCase = true)
        if (isBootAnimation) {
            installBootMedia(context, downloaded)
        } else if (isFirmware) {
            installApkFiles(context, taskId, downloaded)
            Log.i(TAG, "Firmware files installed: ${downloaded.joinToString { it.name }}")
        } else {
            val apk = downloaded.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: throw IllegalStateException("Application download did not include an APK file")
            installApk(context, taskId, apk, presentation?.label())
            MainActivity.writeLog("AWS app download installed: ${apk.name}")
            ApplicationRequirementNotifier.notifyInstalled(context, payload)
        }
    }

    private fun requestDownload(
        context: Context,
        isFirmware: Boolean,
        isBootAnimation: Boolean,
        payload: JSONObject?
    ): DownloadResponse {
        val cfg = TMSFunc.tmsCfg
        val serial = cfg.sn.ifBlank { MainActivity.vg_sSN }
        val token = deviceToken(serial, cfg.download_secret)
        val endpoint = when {
            isBootAnimation -> "boot-animation"
            isFirmware -> "firmware"
            else -> "app"
        }
        val path = "/v1/devices/${serial.urlEncode()}/downloads/$endpoint"
        val request = buildDownloadRequest(payload, isFirmware, isBootAnimation).toString()
        var lastFailure: Exception? = null
        for (url in DeviceApi.urls(path)) {
            try {
                return requestDownloadFromUrl(url, token, request)
            } catch (e: Exception) {
                if (!DeviceApi.isRecoverableHostFailure(e)) throw e
                lastFailure = e
                Log.w(TAG, "Device download endpoint failed for $url: ${e.message}")
            }
        }
        throw lastFailure ?: IllegalStateException("Device download endpoint is unavailable")
    }

    private fun requestDownloadFromUrl(url: String, token: String, request: String): DownloadResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Authorization", "Device $token")
            conn.doOutput = true
            conn.doInput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("Device download HTTP ${conn.responseCode}: $err")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return DownloadResponse.fromJson(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    private fun buildDownloadRequest(
        payload: JSONObject?,
        isFirmware: Boolean,
        isBootAnimation: Boolean
    ): JSONObject {
        val request = JSONObject()
        val idKey = when {
            isBootAnimation -> "bootAnimationId"
            isFirmware -> "firmwareVersionId"
            else -> "applicationVersionId"
        }
        val id = payload?.optString(idKey)
            ?.takeIf { it.isNotBlank() }
            ?: payload?.optString("id")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("Id")?.takeIf { it.isNotBlank() }
        val versionId = payload?.optString("versionId")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("VersionId")?.takeIf { it.isNotBlank() }
            ?: payload?.optString(if (isFirmware) "firmwareVersion" else "applicationVersion")?.takeIf { it.isNotBlank() }
        val packageName = payload?.optString("packageName")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("PackageName")?.takeIf { it.isNotBlank() }

        if (id != null) request.put("id", id)
        if (versionId != null) request.put("versionId", versionId)
        if (!isFirmware && !isBootAnimation && packageName != null) request.put("packageName", packageName)
        return request
    }

    private suspend fun installBootMedia(context: Context, files: List<File>) {
        val animation = files.firstOrNull { it.name.equals("bootanimation.zip", ignoreCase = true) }
            ?: throw IllegalStateException("Boot animation package is missing")
        val logo = files.firstOrNull { it.name.equals("xgd_logo.bin", ignoreCase = true) }

        if (logo != null) {
            TmsTaskStatus.taskOverride.value = "Installing: ${logo.name}"
            val result = NexgoSystemAssetInstaller.installPowerLogo(context, logo, sha256Hex(logo))
            if (!result.success) {
                throw IllegalStateException("Boot logo installation failed: ${result.code}")
            }
        }

        TmsTaskStatus.taskOverride.value = "Installing: ${animation.name}"
        val result = NexgoSystemAssetInstaller.installAnimation(
            context,
            NexgoSystemAsset.BOOT_ANIMATION,
            animation,
            sha256Hex(animation)
        )
        if (!result.success) {
            throw IllegalStateException("Boot animation installation failed: ${result.code}")
        }
        MainActivity.writeLog(
            "Boot media installed: animation=${animation.name} logo=${logo?.name ?: "unchanged"}"
        )
    }

    private fun downloadSignedFile(
        context: Context,
        downloadId: String,
        file: DownloadFile,
        displayLabel: String? = null
    ): File {
        val dest = stagingFile(context, downloadId, file.fileName)
        dest.parentFile?.mkdirs()
        dest.delete()

        val label = displayLabel ?: file.fileName
        TmsTaskStatus.taskOverride.value = context.getString(
            one.globalconnect.xtmsagent.R.string.task_download_preparing,
            label
        )
        val conn = URL(file.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("Signed download HTTP ${conn.responseCode}: $err")
            }

            val totalBytes = file.sizeBytes.takeIf { it > 0 } ?: conn.contentLengthLong
            var bytesWritten = 0L
            var lastPct = -1
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        out.write(buffer, 0, read)
                        bytesWritten += read
                        if (totalBytes > 0) {
                            val pct = (bytesWritten * 100L / totalBytes).toInt().coerceAtMost(100)
                            if (pct != lastPct) {
                                lastPct = pct
                                TmsTaskStatus.taskOverride.value = context.getString(
                                    one.globalconnect.xtmsagent.R.string.task_download_progress,
                                    label,
                                    pct
                                )
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Downloaded ${file.fileName} -> ${dest.absolutePath} (${dest.length()} bytes)")
            return dest
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun installApkFiles(context: Context, taskId: String, files: List<File>) {
        for (file in files.filter { it.name.endsWith(".apk", ignoreCase = true) }) {
            installApk(context, taskId, file)
        }
    }

    private suspend fun installApk(
        context: Context,
        taskId: String,
        apkFile: File,
        displayLabel: String? = null,
    ) {
        val extCache = context.getExternalCacheDir() ?: context.cacheDir
        val installFile = File(extCache, apkFile.name.ensureApkExtension())
        apkFile.copyTo(installFile, overwrite = true)
        installFile.setReadable(true, false)
        val expected = readApkIdentity(context, installFile)

        TmsTaskStatus.taskOverride.value = context.getString(
            one.globalconnect.xtmsagent.R.string.task_app_installing,
            displayLabel ?: apkFile.name
        )

        val deviceOwnerResult = DeviceOwnerPackageInstaller.install(
            context = context,
            taskId = taskId,
            apkFile = installFile,
            packageName = expected.packageName,
            versionCode = expected.versionCode,
            stagedFile = apkFile,
        )
        if (deviceOwnerResult.success || isExpectedPackageInstalled(context, expected)) {
            Log.i(TAG, "Device Owner install succeeded: ${deviceOwnerResult.message}")
            cleanupInstalledApk(installFile, apkFile)
            return
        }
        if (!deviceOwnerResult.shouldFallback) {
            throw IllegalStateException(deviceOwnerResult.message)
        }
        Log.w(
            TAG,
            "Device Owner PackageInstaller did not complete; using NEXGO fallback: " +
                deviceOwnerResult.message,
        )
        MainActivity.writeLog(
            "Application installer fallback: ${expected.describe()} " +
                "reason=${deviceOwnerResult.message}",
        )

        val platform = com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(context).platform
        var lastFailure =
            "Android PackageInstaller: ${deviceOwnerResult.message}; " +
                "NEXGO installer did not complete"

        repeat(INSTALL_ATTEMPTS) { attemptIndex ->
            val attempt = attemptIndex + 1
            if (isExpectedPackageInstalled(context, expected)) {
                Log.i(
                    TAG,
                    "APK already installed while preparing attempt $attempt: " +
                        "${expected.describe()}"
                )
                cleanupInstalledApk(installFile, apkFile)
                return
            }

            val result = CompletableDeferred<Boolean>()
            val sdkResult = platform.installApp(
                installFile.absolutePath,
                object : com.nexgo.oaf.apiv3.OnAppOperatListener {
                    override fun onOperatResult(res: Int) {
                        val success = res == com.nexgo.oaf.apiv3.SdkResult.Success
                        Log.i(
                            TAG,
                            "Nexgo install result for ${apkFile.name}: " +
                                "attempt=$attempt res=$res success=$success"
                        )
                        result.complete(success)
                    }
                }
            )

            if (sdkResult == com.nexgo.oaf.apiv3.SdkResult.Success) {
                val success = awaitInstallCompletion(context, result, expected)
                if (success == true || isExpectedPackageInstalled(context, expected)) {
                    if (success == null) {
                        Log.w(
                            TAG,
                            "NEXGO install callback timed out, but PackageManager confirms " +
                                "${expected.describe()}"
                        )
                    }
                    cleanupInstalledApk(installFile, apkFile)
                    return
                }
                lastFailure = if (success == null) {
                    "NEXGO install callback timed out and PackageManager does not report " +
                        expected.describe()
                } else {
                    "NEXGO installer reported failure for ${expected.describe()}"
                }
            } else {
                lastFailure =
                    "installApp returned $sdkResult for ${expected.describe()}"
            }

            if (attempt < INSTALL_ATTEMPTS) {
                Log.w(
                    TAG,
                    "$lastFailure; retrying installation in ${INSTALL_RETRY_DELAY_MS}ms " +
                        "(attempt ${attempt + 1}/$INSTALL_ATTEMPTS)"
                )
                MainActivity.writeLog(
                    "Application install retry: ${expected.describe()} " +
                        "attempt=${attempt + 1}/$INSTALL_ATTEMPTS reason=$lastFailure"
                )
                delay(INSTALL_RETRY_DELAY_MS)
            }
        }

        throw IllegalStateException(
            "Install failed after $INSTALL_ATTEMPTS attempts: $lastFailure"
        )
    }

    private suspend fun awaitInstallCompletion(
        context: Context,
        callback: CompletableDeferred<Boolean>,
        expected: ApkIdentity,
    ): Boolean? = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
        while (true) {
            if (callback.isCompleted) {
                val callbackSuccess = callback.await()
                return@withTimeoutOrNull callbackSuccess ||
                    isExpectedPackageInstalled(context, expected)
            }
            if (isExpectedPackageInstalled(context, expected)) {
                Log.i(
                    TAG,
                    "PackageManager confirmed ${expected.describe()} before the NEXGO callback"
                )
                return@withTimeoutOrNull true
            }
            delay(INSTALL_POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    private fun readApkIdentity(context: Context, apkFile: File): ApkIdentity {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: throw IllegalStateException("Unable to read APK identity from ${apkFile.name}")
        return ApkIdentity(info.packageName, info.versionCodeCompat())
    }

    private fun isExpectedPackageInstalled(context: Context, expected: ApkIdentity): Boolean {
        @Suppress("DEPRECATION")
        val installed = runCatching {
            context.packageManager.getPackageInfo(expected.packageName, 0)
        }.getOrNull() ?: return false
        return installed.versionCodeCompat() >= expected.versionCode
    }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun cleanupInstalledApk(installFile: File, stagedFile: File) {
        installFile.delete()
        stagedFile.delete()
    }

    private fun verifyFile(file: File, expected: DownloadFile) {
        if (expected.sizeBytes > 0 && file.length() != expected.sizeBytes) {
            throw IllegalStateException("${expected.fileName} size mismatch: expected ${expected.sizeBytes}, got ${file.length()}")
        }
        if (expected.sha256.isNotBlank()) {
            val actual = sha256Hex(file)
            if (!actual.equals(expected.sha256, ignoreCase = true)) {
                throw IllegalStateException("${expected.fileName} SHA-256 mismatch")
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deviceToken(serial: String, secret: String): String {
        if (secret.isBlank()) {
            throw IllegalStateException("Device download secret is missing")
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(serial.trim().uppercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun stagingFile(context: Context, taskId: String, fileName: String): File {
        val safeName = fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return File(taskDirectory(context, taskId), safeName)
    }

    private fun persistStagedTask(
        context: Context,
        taskId: String,
        taskType: String,
        effectiveAt: Instant,
        files: List<File>,
        presentation: ApplicationPresentation? = null
    ) {
        val metadata = JSONObject()
            .put("taskId", taskId)
            .put("taskType", taskType)
            .put("effectiveAt", effectiveAt.toString())
            .put("files", JSONArray(files.map { it.name }))
        presentation?.let {
            metadata.put("applicationName", it.name)
            metadata.put("applicationVersion", it.version)
        }
        metadataFile(context, taskId).writeText(metadata.toString())
    }

    private fun scheduleApply(context: Context, taskId: String, effectiveAt: Instant) {
        val delayMs = (effectiveAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<AwsTaskApplyWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AwsTaskApplyWorker.KEY_TASK_ID to taskId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(taskId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun parseEffectiveAt(payload: JSONObject?): Instant? {
        val value = payload?.optString("effectiveAt")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("EffectiveAt")?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { Instant.parse(value) }
            .onFailure { Log.w(TAG, "Invalid effectiveAt '$value'; applying immediately") }
            .getOrNull()
    }

    private fun taskDirectory(context: Context, taskId: String): File {
        val safeTaskId = taskId.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return File(context.filesDir, "aws_tasks/$safeTaskId")
    }

    private fun metadataFile(context: Context, taskId: String) = File(taskDirectory(context, taskId), "task.json")

    private fun cancelledMarker(context: Context, taskId: String) = File(taskDirectory(context, taskId), ".cancelled")

    private fun isCancelled(context: Context, taskId: String) = taskId.isNotBlank() && cancelledMarker(context, taskId).isFile

    private fun ensureNotCancelled(context: Context, taskId: String) {
        if (isCancelled(context, taskId)) throw IllegalStateException("Task $taskId was cancelled")
    }

    private fun workName(taskId: String) = "aws-task-$taskId"

    private fun String.ensureApkExtension(): String =
        if (endsWith(".apk", ignoreCase = true)) this else "$this.apk"

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private data class ApkIdentity(
        val packageName: String,
        val versionCode: Long,
    ) {
        fun describe(): String = "$packageName versionCode=$versionCode"
    }

    private data class DownloadResponse(
        val downloadId: String,
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val files: List<DownloadFile>,
        val applicationName: String?,
        val versionId: String?
    ) {
        companion object {
            fun fromJson(json: JSONObject): DownloadResponse {
                val filesJson = json.optJSONArray("files") ?: json.optJSONArray("Files") ?: JSONArray()
                val files = (0 until filesJson.length()).mapNotNull { index ->
                    filesJson.optJSONObject(index)?.let { DownloadFile.fromJson(it) }
                }
                return DownloadResponse(
                    downloadId = json.optString("downloadId", json.optString("DownloadId")),
                    fileName = json.optString("fileName", json.optString("FileName")),
                    url = json.optString("url", json.optString("Url")),
                    sha256 = json.optString("sha256", json.optString("Sha256")),
                    sizeBytes = json.optLong("sizeBytes", json.optLong("SizeBytes", 0L)),
                    files = files,
                    applicationName = json.optString(
                        "applicationName",
                        json.optString("ApplicationName")
                    ).takeIf { it.isNotBlank() },
                    versionId = json.optString(
                        "versionId",
                        json.optString("VersionId")
                    ).takeIf { it.isNotBlank() }
                )
            }
        }
    }

    private data class ApplicationPresentation(
        val name: String,
        val version: String?
    ) {
        fun label(): String {
            val normalizedVersion = version?.trim()?.takeIf { it.isNotBlank() } ?: return name
            val versionLabel = if (normalizedVersion.startsWith("v", ignoreCase = true)) {
                normalizedVersion
            } else {
                "v$normalizedVersion"
            }
            return "$name $versionLabel"
        }

        companion object {
            fun fromPayload(payload: JSONObject?): ApplicationPresentation? {
                val name = payload.readFirstString(
                    "applicationName",
                    "ApplicationName",
                    "appName",
                    "AppName",
                    "packageName",
                    "PackageName"
                ) ?: return null
                val version = payload.readFirstString(
                    "version",
                    "Version",
                    "versionId",
                    "VersionId",
                    "applicationVersion",
                    "ApplicationVersion"
                )
                return ApplicationPresentation(name, version)
            }

            fun fromResponse(
                response: DownloadResponse,
                fallback: ApplicationPresentation?,
                defaultName: String
            ): ApplicationPresentation {
                val name = response.applicationName
                    ?: fallback?.name
                    ?: defaultName
                return ApplicationPresentation(name, response.versionId ?: fallback?.version)
            }

            fun fromMetadata(metadata: JSONObject): ApplicationPresentation? {
                val name = metadata.optString("applicationName").takeIf { it.isNotBlank() } ?: return null
                val version = metadata.optString("applicationVersion").takeIf { it.isNotBlank() }
                return ApplicationPresentation(name, version)
            }
        }
    }

    private data class DownloadFile(
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long
    ) {
        companion object {
            fun fromJson(json: JSONObject): DownloadFile =
                DownloadFile(
                    fileName = json.optString("fileName", json.optString("FileName")),
                    url = json.optString("url", json.optString("Url")),
                    sha256 = json.optString("sha256", json.optString("Sha256")),
                    sizeBytes = json.optLong("sizeBytes", json.optLong("SizeBytes", 0L))
                )
        }
    }

    private fun JSONObject?.readFirstString(vararg names: String): String? {
        if (this == null) return null
        return names.firstNotNullOfOrNull { name ->
            optString(name).trim().takeIf { it.isNotBlank() }
        }
    }
}
