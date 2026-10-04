package one.globalconnect.xtmsagent.remote

import android.content.Context
import android.os.Environment
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.TmsServerProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object RemoteFileCommands {
    private const val MAX_SIZE = 256L * 1024 * 1024
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val completed = linkedMapOf<String, String>()

    fun handle(context: Context, request: JSONObject, publishListing: (String) -> Unit) {
        val id = request.optString("requestId")
        if (runCatching { UUID.fromString(id) }.isFailure) return
        val expires = request.optLong("expiresAt")
        if (expires < System.currentTimeMillis() || expires > System.currentTimeMillis() + 11 * 60 * 1000) return
        try {
            worker.execute {
                if (expires < System.currentTimeMillis()) return@execute
                val result = completed[id]?.let { JSONObject(it) } ?: run {
                    val response = JSONObject().put("fileRequestId", id)
                    try {
                        require(request.optString("compression") == "gzip") { "FILE_INVALID_REQUEST" }
                        execute(context, request, response)
                        response.put("ok", true)
                    } catch (e: Exception) {
                        response.put("ok", false).put("code", e.message?.takeIf { it.startsWith("FILE_") } ?: "FILE_IO_ERROR")
                    }
                    completed[id] = response.toString()
                    if (completed.size > 64) completed.remove(completed.keys.first())
                    response
                }
                runCatching {
                    if (request.optString("operation") in listOf("roots", "list")) publishListing(result.toString())
                    else put(request.getString("resultUrl"), "application/json", result.toString().byteInputStream(), result.toString().toByteArray().size.toLong())
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (request.optString("operation") in listOf("roots", "list"))
                publishListing(JSONObject().put("fileRequestId", id).put("ok", false).put("code", "FILE_BUSY").toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun execute(context: Context, request: JSONObject, response: JSONObject) {
        val policy = PublicFilePolicy(Environment.getExternalStorageDirectory())
        when (request.getString("operation")) {
            "roots" -> response.put("roots", JSONArray().put("/sdcard"))
            "list" -> {
                val directory = policy.resolve(request.getString("path"))
                require(directory.isDirectory) { "FILE_NOT_DIRECTORY" }
                val offset = request.optInt("offset")
                require(offset >= 0) { "FILE_INVALID_REQUEST" }
                val files = directory.listFiles()?.filter {
                    !it.name.startsWith(".gc-upload-") && runCatching { policy.resolve(policy.path(it)) }.isSuccess
                }?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    ?: error("FILE_PERMISSION_DENIED")
                val entries = JSONArray()
                var next = offset
                var bytes = 0
                for (file in files.drop(offset).take(100)) {
                    val entry = JSONObject().put("name", file.name).put("path", policy.path(file))
                        .put("directory", file.isDirectory).put("size", if (file.isFile) file.length() else 0)
                        .put("modified", file.lastModified())
                    val length = entry.toString().toByteArray().size
                    if (bytes + length > 48000) break
                    entries.put(entry)
                    bytes += length
                    next++
                }
                response.put("path", policy.path(directory)).put("entries", entries)
                    .put("nextOffset", if (next < files.size) next else -1)
            }
            "download" -> {
                val file = policy.resolve(request.getString("path"))
                require(file.isFile && file.canRead()) { "FILE_PERMISSION_DENIED" }
                require(file.length() <= MAX_SIZE) { "FILE_TOO_LARGE" }
                val size = file.length()
                val modified = file.lastModified()
                val gzip = File.createTempFile("remote-file-", ".gz", context.cacheDir)
                try {
                    GZIPOutputStream(gzip.outputStream()).use { out -> file.inputStream().use {
                        require(copyBounded(it, out, size) == size) { "FILE_CHANGED" }
                    } }
                    require(file.length() == size && file.lastModified() == modified) { "FILE_CHANGED" }
                    gzip.inputStream().use { put(request.getString("dataUrl"), "application/gzip", it, gzip.length()) }
                } finally { gzip.delete() }
            }
            "upload" -> {
                val path = request.getString("path")
                val target = policy.resolve(path)
                val size = request.getLong("size")
                require(size in 0..MAX_SIZE) { "FILE_TOO_LARGE" }
                require(!target.exists()) { "FILE_EXISTS" }
                require(target.parentFile?.isDirectory == true) { "FILE_NOT_DIRECTORY" }
                val temporary = File.createTempFile(".gc-upload-", ".part", target.parentFile)
                val connection = connection(request.getString("dataUrl"))
                try {
                    require(connection.responseCode in 200..299) { "FILE_TRANSFER_FAILED" }
                    GZIPInputStream(connection.inputStream).use { input ->
                        temporary.outputStream().use { output -> require(copyBounded(input, output, size) == size) { "FILE_INVALID_REQUEST" } }
                    }
                    require(policy.resolve(path) == target && policy.resolve(policy.path(temporary)) == temporary.canonicalFile) { "FILE_PATH_DENIED" }
                    java.nio.file.Files.copy(temporary.toPath(), target.toPath())
                } finally { connection.disconnect(); temporary.delete() }
            }
            else -> error("FILE_INVALID_REQUEST")
        }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "FILE_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun connection(url: String): HttpURLConnection {
        val parsed = URL(url)
        val profile = TMSFunc.tmsCfg
        require(RemoteTransferUrlPolicy.isAllowed(parsed, profile.apiHost,
            TmsServerProfile.current(profile) == TmsServerProfile.DEMO)) { "FILE_INVALID_REQUEST" }
        return (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000; readTimeout = 120000; instanceFollowRedirects = false
        }
    }

    internal fun put(url: String, type: String, input: InputStream, length: Long) {
        val connection = connection(url)
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", type)
            connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use { input.copyTo(it) }
            require(connection.responseCode in 200..299) { "FILE_TRANSFER_FAILED" }
        } finally { connection.disconnect() }
    }
}
