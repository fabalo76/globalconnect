package one.globalconnect.xtmsagent.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.app.admin.DevicePolicyManager
import android.app.ActivityManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.SystemClock
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver
import one.globalconnect.xtmsagent.remote.RemoteControlAccessibilityService
import one.globalconnect.xtmsagent.remote.RestrictedSettingsProvisioner
import one.globalconnect.xtmsagent.BuildConfig
import one.globalconnect.xtmsagent.nexgo.NexgoRuntimeInspector
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DiagnosticsExport(
    val uri: Uri,
    val displayPath: String,
)

object NexgoDiagnosticsManager {
    private const val DIRECTORY = "diagnostics"
    private const val REPORT_FILE = "nexgo-diagnostics.txt"
    private const val EVENT_FILE = "nexgo-events.log"
    private const val MAX_EVENT_BYTES = 256 * 1024L
    private const val MAX_DISPLAY_EVENTS = 1000
    private val exportDirectory = "${Environment.DIRECTORY_DOWNLOADS}/xTMSAgent"
    private val exportTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())
    private val lock = Any()

    fun recordSetupState(context: Context, phase: String) {
        runCatching {
            val policy = context.getSystemService(DevicePolicyManager::class.java)
            val component = ComponentName(context, TmsDeviceAdminReceiver::class.java)
            record(context, "$phase adminActive=${policy.isAdminActive(component)} " +
                "deviceOwner=${policy.isDeviceOwnerApp(context.packageName)} " +
                "accessibilityListed=${RemoteControlAccessibilityService.isEnabled(context)} " +
                "accessibilityConnected=${RemoteControlAccessibilityService.isAvailable()} " +
                "accessibilityGlobal=${Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)}")
        }.onFailure { recordException(context, phase, it) }
    }

    fun recordException(context: Context, phase: String, error: Throwable) {
        // This path is used for local setup operations, not network requests or credentials.
        runCatching {
            record(context, "$phase exception=${error.javaClass.name} message=${error.message.orEmpty()}")
            error.stackTrace.take(12).forEach { record(context, "$phase at=$it") }
            error.cause?.takeIf { it !== error }?.let {
                record(context, "$phase cause=${it.javaClass.name} message=${it.message.orEmpty()}")
            }
        }
    }

    fun refresh(context: Context): File {
        val appContext = context.applicationContext
        val snapshot = NexgoRuntimeInspector.inspect(appContext)
        val report = JSONObject().apply {
            put("generatedAt", Instant.now().toString())
            put("reportSchemaVersion", 3)
            put("application", JSONObject().apply {
                put("packageName", appContext.packageName)
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("distribution", "unified")
                put("buildType", BuildConfig.BUILD_TYPE)
            })
            put("android", JSONObject().apply {
                put("release", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("buildDisplay", Build.DISPLAY)
                put("fingerprint", Build.FINGERPRINT)
                put("supportedAbis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList()))
                put("uptimeMillis", SystemClock.elapsedRealtime())
            })
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("product", Build.PRODUCT)
                put("device", Build.DEVICE)
                put("hardware", Build.HARDWARE)
            })
            put("nexgo", snapshot.toJson())
            put("commandEnvironment", NexgoRuntimeInspector.commandDiagnostics())
            put("screen", JSONObject().apply {
                NexgoRuntimeInspector.physicalDisplay(appContext)?.let {
                    put("portraitWidthPixels", it.width)
                    put("portraitHeightPixels", it.height)
                }
                val metrics = appContext.resources.displayMetrics
                put("appWidthPixels", metrics.widthPixels)
                put("appHeightPixels", metrics.heightPixels)
                put("densityDpi", metrics.densityDpi)
            })
            put("management", JSONObject().apply {
                val policy = appContext.getSystemService(DevicePolicyManager::class.java)
                put("deviceOwner", policy.isDeviceOwnerApp(appContext.packageName))
                put("profileOwner", policy.isProfileOwnerApp(appContext.packageName))
                put("adminActive", policy.isAdminActive(ComponentName(appContext, TmsDeviceAdminReceiver::class.java)))
                put("activeAdmins", org.json.JSONArray(policy.activeAdmins.orEmpty().map { it.flattenToString() }))
                put("accessibilityEnabled", RemoteControlAccessibilityService.isEnabled(appContext))
                put("accessibilityConnected", RemoteControlAccessibilityService.isAvailable())
                put("restrictedSettingsState", RestrictedSettingsProvisioner.state(appContext))
                put("accessibilityGlobalEnabled", Settings.Secure.getInt(appContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0))
                put("factoryTmsDeviceOwner", policy.isDeviceOwnerApp("com.nexgo.xtms"))
                put("deviceProvisioned", Settings.Global.getInt(appContext.contentResolver, Settings.Global.DEVICE_PROVISIONED, -1))
                put("userSetupComplete", Settings.Secure.getInt(appContext.contentResolver, "user_setup_complete", -1))
                put("writeSecureSettingsGranted", appContext.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED)
            })
            if (Build.VERSION.SDK_INT >= 30) {
                put("recentProcessExits", org.json.JSONArray().apply {
                    runCatching {
                        appContext.getSystemService(ActivityManager::class.java)
                            .getHistoricalProcessExitReasons(appContext.packageName, 0, 5)
                    }.getOrDefault(emptyList()).forEach { info ->
                        put(JSONObject().put("timestamp", info.timestamp)
                            .put("reason", info.reason).put("status", info.status)
                            .put("importance", info.importance))
                    }
                })
            }
        }
        record(
            appContext,
            "snapshot androidModel=${Build.MODEL} " +
                "nexgoReportedModel=${snapshot.profile.reportedModel} " +
                "resolvedModel=${snapshot.profile.modelKey} " +
                "firmware=${snapshot.firmware ?: "unknown"} " +
                "commandProfileVerified=${snapshot.profile.commandProfileVerified} " +
                "pss=${snapshot.pss.versionName ?: "unknown"} " +
                "permissionGate=${snapshot.pss.permissionGateEnabled ?: "unknown"}",
        )
        return writeReport(appContext, report.toString(2))
    }

    fun record(context: Context, message: String) {
        DailyFileLog.record(context.applicationContext, message)
        val safeMessage = message.replace('\r', ' ').replace('\n', ' ').take(1_000)
        synchronized(lock) {
            val file = eventFile(context.applicationContext)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_EVENT_BYTES) {
                val retained = file.readLines().takeLast(MAX_DISPLAY_EVENTS / 2)
                file.writeText(retained.joinToString(separator = "\n", postfix = "\n"))
            }
            file.appendText("${Instant.now()} $safeMessage\n")
        }
    }

    fun readDisplayReport(context: Context): String {
        val report = reportFile(context.applicationContext)
        val events = synchronized(lock) {
            eventFile(context.applicationContext).takeIf(File::isFile)
                ?.readLines()
                ?.takeLast(MAX_DISPLAY_EVENTS)
                .orEmpty()
        }
        return buildString {
            if (report.isFile) append(report.readText()) else append("Diagnostics report unavailable")
            if (events.isNotEmpty()) {
                append("\n\nRecent diagnostic events\n")
                append(events.joinToString("\n"))
            }
        }
    }

    fun shareFile(context: Context): File {
        refresh(context)
        val combined = readDisplayReport(context)
        return File(reportFile(context.applicationContext).parentFile, "nexgo-diagnostics-share.txt")
            .apply { writeText(combined) }
    }

    fun exportToPublicDownloads(context: Context): DiagnosticsExport {
        val appContext = context.applicationContext
        refresh(appContext)

        val displayName = "xtmsagent-diagnostics-${exportTimestamp.format(Instant.now())}.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, exportDirectory)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = checkNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
        ) { "Unable to create the public diagnostics file" }

        try {
            resolver.openOutputStream(uri, "w").use { output ->
                checkNotNull(output) { "Unable to open the public diagnostics file" }
                output.write(readDisplayReport(appContext).toByteArray(Charsets.UTF_8))
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (exception: Exception) {
            resolver.delete(uri, null, null)
            throw exception
        }

        val displayPath = "$exportDirectory/$displayName"
        record(appContext, "diagnosticsExport path=$displayPath")
        return DiagnosticsExport(uri = uri, displayPath = displayPath)
    }

    private fun writeReport(context: Context, content: String): File = synchronized(lock) {
        val destination = reportFile(context)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "$REPORT_FILE.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        destination
    }

    private fun reportFile(context: Context) = File(context.filesDir, "$DIRECTORY/$REPORT_FILE")

    private fun eventFile(context: Context) = File(context.filesDir, "$DIRECTORY/$EVENT_FILE")
}
