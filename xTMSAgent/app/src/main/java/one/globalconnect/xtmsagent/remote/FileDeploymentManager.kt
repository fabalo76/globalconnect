package one.globalconnect.xtmsagent.remote

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.net.DeviceApi
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

internal object FileDeploymentManager {
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 300_000
    private const val MAX_FILE_SIZE = 256L * 1024 * 1024

    fun execute(context: Context, taskId: String, payload: JSONObject?): String {
        require(payload != null) { "FILE_DEPLOYMENT_INVALID_PAYLOAD" }
        require(payload.optString("targetOperatingSystem").equals("android", ignoreCase = true)) {
            "FILE_DEPLOYMENT_OS_MISMATCH"
        }
        UUID.fromString(taskId)
        val destinationPath = payload.getString("destinationPath")
        val policy = PublicFilePolicy(Environment.getExternalStorageDirectory())
        val destination = policy.resolve(destinationPath)
        val clearFolder = payload.optBoolean("clearFolder", false)
        require(!clearFolder || destination != Environment.getExternalStorageDirectory().canonicalFile) { "FILE_DEPLOYMENT_CLEAR_PATH_FORBIDDEN" }
        require(!destination.exists() || destination.isDirectory) { "FILE_NOT_DIRECTORY" }

        val files = payload.getJSONArray("files")
        require(files.length() in 1..50) { "FILE_DEPLOYMENT_FILES_INVALID" }
        val names = HashSet<String>()
        var totalSize = 0L
        for (index in 0 until files.length()) {
            val item = files.getJSONObject(index)
            val name = item.getString("fileName")
            require(name.isNotBlank() && name.length <= 255 && name == File(name).name && name !in setOf(".", "..")
                && !name.contains('\\') && !name.contains('\u0000') && names.add(name.lowercase())) { "FILE_DEPLOYMENT_FILE_NAME_INVALID" }
            val size = item.getLong("sizeBytes")
            require(size in 1..MAX_FILE_SIZE) { "FILE_TOO_LARGE" }
            totalSize += size
            require(totalSize <= 1024L * 1024 * 1024) { "FILE_TOO_LARGE" }
        }
        val staging = File(context.cacheDir, "file-deployments/$taskId").apply {
            deleteRecursively()
            mkdirs()
        }
        val downloaded = mutableListOf<Pair<File, String>>()
        try {
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val fileId = UUID.fromString(item.getString("fileId"))
                val expectedName = item.getString("fileName")
                require(expectedName == File(expectedName).name && expectedName !in setOf(".", "..")) {
                    "FILE_DEPLOYMENT_FILE_NAME_INVALID"
                }
                val response = requestDownload(taskId, fileId.toString(), payload.getString("downloadEndpoint"))
                require(response.getString("fileName") == expectedName) { "FILE_DEPLOYMENT_FILE_MISMATCH" }
                val size = response.getLong("sizeBytes")
                require(size == item.getLong("sizeBytes")) { "FILE_DEPLOYMENT_SIZE_MISMATCH" }
                val compression = response.optString("compression", "")
                require(compression in setOf("", "null", "gzip")) { "FILE_DEPLOYMENT_FILES_INVALID" }
                val staged = File(staging, expectedName)
                download(response.getString("url"), staged, size, compression == "gzip")
                require(response.getString("sha256").equals(item.getString("sha256"), ignoreCase = true)
                    && sha256(staged).equals(item.getString("sha256"), ignoreCase = true)) {
                    "FILE_DEPLOYMENT_CHECKSUM_MISMATCH"
                }
                downloaded += staged to expectedName
            }

            destination.mkdirs()
            require(destination.isDirectory) { "FILE_NOT_DIRECTORY" }
            if (clearFolder) {
                val children = destination.listFiles() ?: error("FILE_PERMISSION_DENIED")
                children.forEach { validateDeletion(it, policy) }
                children.forEach { deleteWithoutLinks(it) }
            }
            downloaded.forEach { (source, name) ->
                val target = policy.resolve("$destinationPath/$name".replace("//", "/"))
                require(target.parentFile?.canonicalFile == destination.canonicalFile) { "FILE_PATH_DENIED" }
                source.inputStream().use { input ->
                    FileOutputStream(target, false).use { output -> input.copyTo(output) }
                }
            }
            MainActivity.writeLog("File deployment completed: task=$taskId path=$destinationPath files=${downloaded.size}")
            return "Deployed ${downloaded.size} file(s) to $destinationPath"
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun validateDeletion(file: File, policy: PublicFilePolicy) {
        require(!OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)) { "FILE_PATH_DENIED" }
        policy.resolve(policy.path(file))
        if (file.isDirectory) (file.listFiles() ?: error("FILE_PERMISSION_DENIED")).forEach { validateDeletion(it, policy) }
    }

    private fun deleteWithoutLinks(file: File) {
        require(!OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)) { "FILE_PATH_DENIED" }
        if (file.isDirectory) (file.listFiles() ?: error("FILE_PERMISSION_DENIED")).forEach { deleteWithoutLinks(it) }
        require(file.delete()) { "FILE_PERMISSION_DENIED" }
    }

    private fun requestDownload(taskId: String, fileId: String, endpointTemplate: String): JSONObject {
        val cfg = TMSFunc.tmsCfg
        val serial = cfg.sn.ifBlank { MainActivity.vg_sSN }
        val token = DeviceApi.deviceToken(serial, cfg)
        require(endpointTemplate == "/v1/devices/{serial}/downloads/file-deployment") { "FILE_DEPLOYMENT_ENDPOINT_INVALID" }
        val path = endpointTemplate.replace("{serial}", java.net.URLEncoder.encode(serial, Charsets.UTF_8.name()))
        val body = JSONObject().put("taskId", taskId).put("fileId", fileId).toString()
        var lastFailure: Exception? = null
        for (url in DeviceApi.urls(path)) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connection.setRequestProperty("Authorization", "Device $token")
                    connection.doOutput = true
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "FILE_DEPLOYMENT_DOWNLOAD_HTTP_${connection.responseCode}"
                    }
                    return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                if (!DeviceApi.isRecoverableHostFailure(error)) throw error
                lastFailure = error
            }
        }
        throw lastFailure ?: IllegalStateException("FILE_DEPLOYMENT_DOWNLOAD_UNAVAILABLE")
    }

    private fun download(url: String, destination: File, expectedSize: Long, gzip: Boolean) {
        require(URL(url).protocol == "https") { "FILE_TRANSFER_FAILED" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "FILE_TRANSFER_FAILED" }
            var total = 0L
            FileOutputStream(destination).use { output ->
                (if (gzip) GZIPInputStream(connection.inputStream) else connection.inputStream).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= expectedSize) { "FILE_TOO_LARGE" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(total == expectedSize) { "FILE_DEPLOYMENT_SIZE_MISMATCH" }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
