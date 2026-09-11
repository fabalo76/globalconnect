package one.globalconnect.xtmsagent.mqtt.downloads

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.diagnostics.NexgoDiagnosticsManager
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class DeviceOwnerInstallResult(
    val success: Boolean,
    val shouldFallback: Boolean,
    val message: String,
)

internal object DeviceOwnerPackageInstaller {
    private const val TAG = "DeviceOwnerInstaller"
    private const val INSTALL_TIMEOUT_MS = 300_000L
    private const val PREFS = "device_owner_installs"
    private const val RECORD_PREFIX = "request_"
    private const val ACTION_INSTALL_STATUS =
        "one.globalconnect.xtmsagent.action.PACKAGE_INSTALL_STATUS"
    private const val EXTRA_REQUEST_ID = "requestId"

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<InstallStatus>>()

    suspend fun install(
        context: Context,
        taskId: String?,
        apkFile: File,
        packageName: String,
        versionCode: Long,
        stagedFile: File,
    ): DeviceOwnerInstallResult {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) {
            return DeviceOwnerInstallResult(
                success = false,
                shouldFallback = true,
                message = "xTMSAgent is not Device Owner",
            )
        }

        val installedVersionCode = installedVersionCode(appContext, packageName)
        if (isApplicationDowngrade(installedVersionCode, versionCode)) {
            return DeviceOwnerInstallResult(
                success = false,
                shouldFallback = true,
                message =
                    "Rollback requested from versionCode=$installedVersionCode " +
                        "to versionCode=$versionCode; using NEXGO privileged installer",
            )
        }

        val packageInstaller = appContext.packageManager.packageInstaller
        val requestId = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<InstallStatus>()
        waiters[requestId] = waiter
        var sessionId: Int? = null

        return try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(packageName)
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInstallReason(PackageManagerCompat.INSTALL_REASON_POLICY)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
                    )
                }
            }

            sessionId = packageInstaller.createSession(params)
            val record = InstallRecord(
                requestId = requestId,
                sessionId = sessionId,
                taskId = taskId,
                packageName = packageName,
                versionCode = versionCode,
                installPath = apkFile.absolutePath,
                stagedPath = stagedFile.absolutePath,
            )
            persistRecord(appContext, record)

            packageInstaller.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        session.fsync(output)
                    }
                }
                session.setStagingProgress(1f)
                session.commit(statusIntentSender(appContext, requestId, sessionId))
            }

            val startedMessage =
                "PackageInstaller committed task=${taskId ?: "none"} session=$sessionId " +
                    "package=$packageName versionCode=$versionCode"
            Log.i(TAG, startedMessage)
            MainActivity.writeLog(startedMessage)
            NexgoDiagnosticsManager.record(appContext, startedMessage)

            val status = withTimeoutOrNull(INSTALL_TIMEOUT_MS) { waiter.await() }
            when {
                status == null && isRequestedVersionInstalled(appContext, packageName, versionCode) -> {
                    removeRecord(appContext, requestId)
                    DeviceOwnerInstallResult(
                        success = true,
                        shouldFallback = false,
                        message = "PackageManager confirmed installation after status timeout",
                    )
                }
                status == null -> {
                    abandonSession(packageInstaller, sessionId)
                    removeRecord(appContext, requestId)
                    DeviceOwnerInstallResult(
                        success = false,
                        shouldFallback = true,
                        message = "PackageInstaller timed out after ${INSTALL_TIMEOUT_MS / 1000}s",
                    )
                }
                status.status == PackageInstaller.STATUS_SUCCESS &&
                    isRequestedVersionInstalled(appContext, packageName, versionCode) -> {
                    removeRecord(appContext, requestId)
                    DeviceOwnerInstallResult(
                        success = true,
                        shouldFallback = false,
                        message = status.describe(),
                    )
                }
                status.status == PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    abandonSession(packageInstaller, sessionId)
                    removeRecord(appContext, requestId)
                    DeviceOwnerInstallResult(
                        success = false,
                        shouldFallback = true,
                        message = "PackageInstaller requested user action despite Device Owner",
                    )
                }
                else -> {
                    removeRecord(appContext, requestId)
                    DeviceOwnerInstallResult(
                        success = false,
                        shouldFallback = true,
                        message = status.describe(),
                    )
                }
            }
        } catch (e: Exception) {
            sessionId?.let { abandonSession(packageInstaller, it) }
            removeRecord(appContext, requestId)
            DeviceOwnerInstallResult(
                success = false,
                shouldFallback = true,
                message = "PackageInstaller error: ${e.message ?: e.javaClass.simpleName}",
            )
        } finally {
            waiters.remove(requestId)
        }
    }

    /**
     * A successful self-update kills the coroutine that committed the session.
     * MY_PACKAGE_REPLACED runs in the new process; use the persisted record to
     * finish the task and publish its ACK.
     */
    fun reconcileSelfUpdate(context: Context) {
        val appContext = context.applicationContext
        records(appContext)
            .filter { it.packageName == appContext.packageName }
            .filter { isRequestedVersionInstalled(appContext, it.packageName, it.versionCode) }
            .forEach { completeSelfUpdate(appContext, it, "MY_PACKAGE_REPLACED") }
    }

    fun persistFallbackSelfUpdate(
        context: Context,
        taskId: String,
        packageName: String,
        versionCode: Long,
        installFile: File,
        stagedFile: File,
    ): String? {
        val appContext = context.applicationContext
        if (packageName != appContext.packageName) return null

        val requestId = UUID.randomUUID().toString()
        persistRecord(
            appContext,
            InstallRecord(
                requestId = requestId,
                sessionId = -1,
                taskId = taskId,
                packageName = packageName,
                versionCode = versionCode,
                installPath = installFile.absolutePath,
                stagedPath = stagedFile.absolutePath,
            ),
        )
        Log.i(
            TAG,
            "Persisted NEXGO fallback self-update task=$taskId " +
                "package=$packageName versionCode=$versionCode",
        )
        return requestId
    }

    fun discardFallbackSelfUpdate(context: Context, requestId: String?) {
        requestId?.let { removeRecord(context.applicationContext, it) }
    }

    internal fun onInstallStatus(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) {
            Log.w(TAG, "PackageInstaller status omitted requestId")
            return
        }
        val status = InstallStatus(
            status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            ),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME),
        )
        val record = readRecord(context, requestId)
        val diagnostic =
            "PackageInstaller status request=$requestId session=${record?.sessionId} " +
                "status=${status.status} package=${status.packageName ?: record?.packageName} " +
                "message=${status.message ?: "none"}"
        Log.i(TAG, diagnostic)
        MainActivity.writeLog(diagnostic)
        NexgoDiagnosticsManager.record(context, diagnostic)

        if (status.status == PackageInstaller.STATUS_SUCCESS &&
            record != null &&
            record.packageName == context.packageName &&
            isRequestedVersionInstalled(context, record.packageName, record.versionCode)
        ) {
            completeSelfUpdate(context, record, "PackageInstaller callback")
        }
        waiters[requestId]?.complete(status)
    }

    private fun statusIntentSender(
        context: Context,
        requestId: String,
        sessionId: Int,
    ) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, PackageInstallStatusReceiver::class.java).apply {
            action = ACTION_INSTALL_STATUS
            putExtra(EXTRA_REQUEST_ID, requestId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            },
    ).intentSender

    @Synchronized
    private fun completeSelfUpdate(
        context: Context,
        record: InstallRecord,
        source: String,
    ) {
        if (readRecord(context, record.requestId) == null) return
        removeRecord(context, record.requestId)
        cleanup(record)
        val message =
            "Self-update installed: ${record.packageName} " +
                "versionCode=${record.versionCode} source=$source"
        Log.i(TAG, message)
        MainActivity.writeLog(message)
        NexgoDiagnosticsManager.record(context, message)
        TmsMqttManager.queueFullStatusReport(
            context,
            "PackageInstaller self-update reconciled at versionCode=${record.versionCode}",
        )
        record.taskId?.let { taskId ->
            TmsMqttManager.publishExternalTaskAck(
                context = context,
                taskId = taskId,
                success = true,
                status = "applied",
                statusMessage = "Application self-update installed",
            )
        }
    }

    private fun persistRecord(context: Context, record: InstallRecord) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(RECORD_PREFIX + record.requestId, record.toJson().toString())
            .commit()
        if (!saved) throw IllegalStateException("Unable to persist PackageInstaller session")
    }

    private fun readRecord(context: Context, requestId: String): InstallRecord? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(RECORD_PREFIX + requestId, null)
            ?: return null
        return runCatching { InstallRecord.fromJson(JSONObject(json)) }.getOrNull()
    }

    private fun records(context: Context): List<InstallRecord> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all
            .filterKeys { it.startsWith(RECORD_PREFIX) }
            .values
            .mapNotNull { raw ->
                (raw as? String)?.let {
                    runCatching { InstallRecord.fromJson(JSONObject(it)) }.getOrNull()
                }
            }

    private fun removeRecord(context: Context, requestId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(RECORD_PREFIX + requestId)
            .apply()
    }

    private fun abandonSession(packageInstaller: PackageInstaller, sessionId: Int) {
        runCatching { packageInstaller.abandonSession(sessionId) }
            .onFailure { Log.w(TAG, "Unable to abandon install session $sessionId", it) }
    }

    private fun isRequestedVersionInstalled(
        context: Context,
        packageName: String,
        versionCode: Long,
    ): Boolean = isSameApplicationVersion(
        installedVersionCode(context, packageName),
        versionCode,
    )

    private fun installedVersionCode(
        context: Context,
        packageName: String,
    ): Long? {
        @Suppress("DEPRECATION")
        val info = runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.getOrNull() ?: return null
        return info.versionCodeCompat()
    }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun cleanup(record: InstallRecord) {
        listOf(record.installPath, record.stagedPath)
            .filter { it.isNotBlank() }
            .forEach { path -> runCatching { File(path).delete() } }
    }

    private data class InstallStatus(
        val status: Int,
        val message: String?,
        val packageName: String?,
    ) {
        fun describe(): String =
            "PackageInstaller status=$status" +
                (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    }

    private data class InstallRecord(
        val requestId: String,
        val sessionId: Int,
        val taskId: String?,
        val packageName: String,
        val versionCode: Long,
        val installPath: String,
        val stagedPath: String,
    ) {
        fun toJson() = JSONObject()
            .put("requestId", requestId)
            .put("sessionId", sessionId)
            .put("taskId", taskId ?: JSONObject.NULL)
            .put("packageName", packageName)
            .put("versionCode", versionCode)
            .put("installPath", installPath)
            .put("stagedPath", stagedPath)

        companion object {
            fun fromJson(json: JSONObject) = InstallRecord(
                requestId = json.getString("requestId"),
                sessionId = json.getInt("sessionId"),
                taskId = if (json.isNull("taskId")) {
                    null
                } else {
                    json.optString("taskId").takeIf { it.isNotBlank() }
                },
                packageName = json.getString("packageName"),
                versionCode = json.getLong("versionCode"),
                installPath = json.optString("installPath"),
                stagedPath = json.optString("stagedPath"),
            )
        }
    }

    /**
     * Avoid a direct reference to the API-26 constant while keeping this class
     * loadable on older supported Android versions.
     */
    private object PackageManagerCompat {
        const val INSTALL_REASON_POLICY = 1
    }
}

class PackageInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DeviceOwnerPackageInstaller.onInstallStatus(context.applicationContext, intent)
    }
}
