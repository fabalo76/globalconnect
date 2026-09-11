package one.globalconnect.xtmsagent.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    private const val MAX_DISPLAY_EVENTS = 100
    private val exportDirectory = "${Environment.DIRECTORY_DOWNLOADS}/xTMSAgent"
    private val exportTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())
    private val lock = Any()

    fun refresh(context: Context): File {
        val appContext = context.applicationContext
        val snapshot = NexgoRuntimeInspector.inspect(appContext)
        val report = JSONObject().apply {
            put("generatedAt", Instant.now().toString())
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
        val file = reportFile(context.applicationContext)
        if (!file.isFile) refresh(context)
        val combined = readDisplayReport(context)
        return writeReport(context.applicationContext, combined)
    }

    fun exportToPublicDownloads(context: Context): DiagnosticsExport {
        val appContext = context.applicationContext
        val privateReport = reportFile(appContext)
        if (!privateReport.isFile) refresh(appContext)

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
