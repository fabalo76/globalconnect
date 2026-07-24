package one.globalconnect.xtmsagent.diagnostics

import android.content.Context
import android.os.Build
import one.globalconnect.xtmsagent.BuildConfig
import one.globalconnect.xtmsagent.nexgo.NexgoRuntimeInspector
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

object NexgoDiagnosticsManager {
    private const val DIRECTORY = "diagnostics"
    private const val REPORT_FILE = "nexgo-diagnostics.txt"
    private const val EVENT_FILE = "nexgo-events.log"
    private const val MAX_EVENT_BYTES = 256 * 1024L
    private const val MAX_DISPLAY_EVENTS = 100
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
                put("flavor", BuildConfig.FLAVOR)
                put("buildType", BuildConfig.BUILD_TYPE)
            })
            put("android", JSONObject().apply {
                put("release", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
            })
            put("nexgo", snapshot.toJson())
        }
        record(
            appContext,
            "snapshot model=${snapshot.profile.modelKey} " +
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
