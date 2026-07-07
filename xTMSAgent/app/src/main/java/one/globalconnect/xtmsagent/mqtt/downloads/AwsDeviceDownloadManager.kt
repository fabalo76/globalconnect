package one.globalconnect.xtmsagent.mqtt.downloads

import android.content.Context
import android.util.Log
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.mqtt.TmsTaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "AwsDeviceDownload"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 300_000
private const val INSTALL_TIMEOUT_MS = 180_000L

object AwsDeviceDownloadManager {

    data class Result(val success: Boolean, val errorMessage: String? = null)

    suspend fun executeTask(
        context: Context,
        taskType: String,
        payload: JSONObject?
    ): Result = withContext(Dispatchers.IO) {
        try {
            val isFirmware = taskType.equals("FirmwareDownload", ignoreCase = true)
                || taskType.equals("UpdateFirmware", ignoreCase = true)
            val response = requestDownload(context, isFirmware, payload)
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
                val localFile = downloadSignedFile(context, response.downloadId, file)
                verifyFile(localFile, file)
                localFile
            }

            if (isFirmware) {
                installApkFiles(context, downloaded)
                Log.i(TAG, "Firmware download staged: ${downloaded.joinToString { it.name }}")
            } else {
                val apk = downloaded.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: throw IllegalStateException("Application download did not include an APK file")
                installApk(context, apk)
                MainActivity.writeLog("AWS app download installed: ${apk.name}")
            }

            TmsTaskStatus.taskOverride.value = null
            Result(success = true)
        } catch (e: Exception) {
            Log.e(TAG, "AWS download task failed: ${e.message}", e)
            TmsTaskStatus.taskOverride.value = null
            Result(success = false, errorMessage = e.message ?: "Download task failed")
        }
    }

    private fun requestDownload(context: Context, isFirmware: Boolean, payload: JSONObject?): DownloadResponse {
        val cfg = TMSFunc.tmsCfg
        val serial = cfg.sn.ifBlank { MainActivity.vg_sSN }
        val token = deviceToken(serial, cfg.download_secret)
        val endpoint = if (isFirmware) "firmware" else "app"
        val url = "${cfg.webScheme}://${cfg.apiHost}:${cfg.web_port}/v1/devices/${serial.urlEncode()}/downloads/$endpoint"
        val request = buildDownloadRequest(payload, isFirmware).toString()

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

    private fun buildDownloadRequest(payload: JSONObject?, isFirmware: Boolean): JSONObject {
        val request = JSONObject()
        val id = payload?.optString(if (isFirmware) "firmwareVersionId" else "applicationVersionId")
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
        if (!isFirmware && packageName != null) request.put("packageName", packageName)
        return request
    }

    private fun downloadSignedFile(context: Context, downloadId: String, file: DownloadFile): File {
        val dest = stagingFile(context, downloadId, file.fileName)
        dest.parentFile?.mkdirs()
        dest.delete()

        TmsTaskStatus.taskOverride.value = "Downloading: ${file.fileName}"
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
                                TmsTaskStatus.taskOverride.value = "Downloading: ${file.fileName} $pct%"
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

    private suspend fun installApkFiles(context: Context, files: List<File>) {
        for (file in files.filter { it.name.endsWith(".apk", ignoreCase = true) }) {
            installApk(context, file)
        }
    }

    private suspend fun installApk(context: Context, apkFile: File) {
        val extCache = context.getExternalCacheDir() ?: context.cacheDir
        val installFile = File(extCache, apkFile.name.ensureApkExtension())
        apkFile.copyTo(installFile, overwrite = true)
        installFile.setReadable(true, false)

        TmsTaskStatus.taskOverride.value = "Installing: ${apkFile.name}"
        val result = CompletableDeferred<Boolean>()
        val platform = com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(context).platform
        val sdkResult = platform.installApp(
            installFile.absolutePath,
            object : com.nexgo.oaf.apiv3.OnAppOperatListener {
                override fun onOperatResult(res: Int) {
                    val success = res == com.nexgo.oaf.apiv3.SdkResult.Success
                    Log.i(TAG, "Nexgo install result for ${apkFile.name}: res=$res success=$success")
                    result.complete(success)
                }
            }
        )

        if (sdkResult != com.nexgo.oaf.apiv3.SdkResult.Success) {
            throw IllegalStateException("installApp returned $sdkResult for ${apkFile.name}")
        }

        val success = withTimeoutOrNull(INSTALL_TIMEOUT_MS) { result.await() }
            ?: throw IllegalStateException("Install timed out for ${apkFile.name}")
        if (!success) {
            throw IllegalStateException("Install failed for ${apkFile.name}")
        }

        installFile.delete()
        apkFile.delete()
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

    private fun stagingFile(context: Context, downloadId: String, fileName: String): File {
        val safeName = fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return File(File(context.filesDir, "aws_downloads/$downloadId"), safeName)
    }

    private fun String.ensureApkExtension(): String =
        if (endsWith(".apk", ignoreCase = true)) this else "$this.apk"

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private data class DownloadResponse(
        val downloadId: String,
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val files: List<DownloadFile>
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
                    files = files
                )
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
}
