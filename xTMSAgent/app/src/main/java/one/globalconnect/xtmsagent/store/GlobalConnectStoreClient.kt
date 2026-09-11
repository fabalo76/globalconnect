package one.globalconnect.xtmsagent.store

import android.content.Context
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.net.DeviceApi
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

internal data class StoreCatalog(
    val scope: String,
    val deviceGroupId: String?,
    val applications: List<StoreApplication>,
)

internal data class StoreApplication(
    val applicationId: String,
    val versionId: String,
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val releaseNotes: String,
    val iconUrl: String?,
    val sha256: String,
    val sizeBytes: Long,
)

internal class GlobalConnectStoreClient(private val context: Context) {
    private val config get() = TMSFunc.tmsCfg
    private val serial get() = config.sn.ifBlank { MainActivity.vg_sSN }.trim().uppercase()
    private val token get() = DeviceApi.deviceToken(serial, config)

    fun loadCatalog(): StoreCatalog {
        ensureConfigured()
        val json = request("GET", "/v1/devices/${serial.urlEncode()}/store")
        val apps = json.optJSONArray("applications") ?: json.optJSONArray("Applications")
        val result = buildList {
            if (apps == null) return@buildList
            for (index in 0 until apps.length()) {
                val app = apps.getJSONObject(index)
                add(
                    StoreApplication(
                        applicationId = app.string("softwareApplicationId", "SoftwareApplicationId"),
                        versionId = app.string("softwareApplicationVersionId", "SoftwareApplicationVersionId"),
                        name = app.string("appName", "AppName"),
                        packageName = app.string("packageName", "PackageName"),
                        versionName = app.string("versionId", "VersionId"),
                        versionCode = app.long("versionCode", "VersionCode"),
                        releaseNotes = app.string("releaseNotes", "ReleaseNotes"),
                        iconUrl = app.nullableString("iconUrl", "IconUrl"),
                        sha256 = app.string("sha256", "Sha256"),
                        sizeBytes = app.long("sizeBytes", "SizeBytes"),
                    )
                )
            }
        }
        return StoreCatalog(
            scope = json.string("scope", "Scope"),
            deviceGroupId = json.nullableString("deviceGroupId", "DeviceGroupId"),
            applications = result,
        )
    }

    fun download(
        application: StoreApplication,
        onProgress: (Int) -> Unit,
    ): File {
        ensureConfigured()
        val metadata = request(
            "POST",
            "/v1/devices/${serial.urlEncode()}/store/apps/${application.versionId.urlEncode()}/download",
        )
        val url = metadata.string("url", "Url")
        val expectedSha256 = metadata.string("sha256", "Sha256")
        val expectedSize = metadata.long("sizeBytes", "SizeBytes")
        val fileName = metadata.string("fileName", "FileName").ifBlank { "${application.packageName}.apk" }
        val directory = File(context.externalCacheDir ?: context.cacheDir, "global_connect_store").apply { mkdirs() }
        val destination = File(directory, fileName.safeFileName())
        val temporary = File(directory, "${destination.name}.part")
        temporary.delete()

        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Download failed with HTTP ${connection.responseCode}")
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (contentLength > 0) onProgress(((copied * 100) / contentLength).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (expectedSize > 0 && temporary.length() != expectedSize) {
                throw IllegalStateException("Downloaded size does not match the store metadata")
            }
            val actualSha256 = temporary.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)
                || !actualSha256.equals(application.sha256, ignoreCase = true)
            ) {
                throw IllegalStateException("Downloaded application signature hash is invalid")
            }
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
            onProgress(100)
            return destination
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun request(method: String, path: String): JSONObject {
        var lastError: Exception? = null
        for (url in DeviceApi.urls(path)) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Device $token")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                if (method == "POST") {
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.outputStream.use { it.write("{}".toByteArray()) }
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("Store request failed with HTTP $status${if (message.isBlank()) "" else ": $message"}")
                }
                return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (error: Exception) {
                lastError = error
                if (!DeviceApi.isRecoverableHostFailure(error)) throw error
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("No Global Connect API host is configured")
    }

    private fun ensureConfigured() {
        check(serial.isNotBlank()) { "Device serial number is missing" }
        check(config.download_secret.isNotBlank()) { "Device download secret is missing" }
        check(config.apiHost.isNotBlank()) { "Global Connect API host is missing" }
    }

    private fun JSONObject.string(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        if (!has(name) || isNull(name)) null else optString(name).takeUnless { it.equals("null", true) }
    }.orEmpty()

    private fun JSONObject.nullableString(vararg names: String): String? = string(*names).takeIf { it.isNotBlank() }

    private fun JSONObject.long(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
        if (!has(name) || isNull(name)) null else optLong(name)
    } ?: 0L

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val DOWNLOAD_TIMEOUT_MS = 180_000
    }
}
