package one.globalconnect.xtmsagent.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports only the installed vendor APK files and our diagnostics, never vendor app data. */
object PssPackageExporter {
    private const val PACKAGE = "com.xgd.possystemservice"

    @Suppress("DEPRECATION")
    fun export(context: Context): DiagnosticsExport {
        val app = context.applicationContext
        val info = app.packageManager.getPackageInfo(PACKAGE, 0)
        val application = checkNotNull(info.applicationInfo) { "PSS application metadata unavailable" }
        val sources = listOf(File(application.sourceDir)) + application.splitSourceDirs.orEmpty().map(::File)
        check(sources.all { it.isFile && it.canRead() }) { "Firmware does not allow reading the PSS APK files" }
        NexgoDiagnosticsManager.record(app, "pssExport.begin package=$PACKAGE version=${info.versionName} files=${sources.size}")
        NexgoDiagnosticsManager.refresh(app)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val name = "xtmsagent-pss-$stamp.zip"
        val directory = "${Environment.DIRECTORY_DOWNLOADS}/xTMSAgent"
        val resolver = app.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, directory)
            put(MediaStore.Downloads.IS_PENDING, 1)
        })) { "Unable to create the PSS export in Downloads" }
        try {
            ZipOutputStream(checkNotNull(resolver.openOutputStream(uri, "w"))).use { zip ->
                val entries = JSONArray()
                sources.forEachIndexed { index, source ->
                    val entry = if (index == 0) "base.apk" else "split-$index.apk"
                    val sizeBefore = source.length()
                    val modifiedBefore = source.lastModified()
                    zip.putNextEntry(ZipEntry(entry))
                    val (bytes, sha256) = source.inputStream().use { copyAndHash(it, zip) }
                    zip.closeEntry()
                    check(bytes == sizeBefore && source.length() == sizeBefore && source.lastModified() == modifiedBefore) {
                        "PSS APK changed during export; please export again"
                    }
                    entries.put(JSONObject().put("file", entry).put("bytes", bytes).put("sha256", sha256))
                    NexgoDiagnosticsManager.record(app, "pssExport.file entry=$entry bytes=$bytes sha256=$sha256")
                }
                val manifest = JSONObject().put("packageName", PACKAGE).put("versionName", info.versionName)
                    .put("versionCode", info.longVersionCode).put("exportedAt", Instant.now().toString())
                    .put("apkFiles", entries)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("diagnostics.txt"))
                zip.write(NexgoDiagnosticsManager.readDisplayReport(app).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                "Unable to publish the PSS export in Downloads"
            }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            NexgoDiagnosticsManager.recordException(app, "pssExport", error)
            throw error
        }
        NexgoDiagnosticsManager.record(app, "pssExport.complete path=$directory/$name")
        return DiagnosticsExport(uri, "$directory/$name")
    }

    internal fun copyAndHash(input: InputStream, output: OutputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            output.write(buffer, 0, size)
            digest.update(buffer, 0, size)
            total += size
        }
        return total to digest.digest().joinToString("") { "%02X".format(it) }
    }
}
