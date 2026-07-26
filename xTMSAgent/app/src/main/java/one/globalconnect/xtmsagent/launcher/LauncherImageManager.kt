package one.globalconnect.xtmsagent.launcher

import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val IMAGE_TAG = "LauncherImageMgr"
private const val MAX_IMAGE_BYTES = 1024 * 1024L
private const val IMAGE_CONNECT_TIMEOUT_MS = 30_000
private const val IMAGE_READ_TIMEOUT_MS = 60_000

internal data class LauncherImageSpec(
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
)

object LauncherImageManager {
    fun apply(configDirectory: File, json: JSONObject): Boolean {
        val topPresent = json.hasAny("topImage", "TopImage")
        val bottomPresent = json.hasAny("bottomImage", "BottomImage")
        if (!topPresent && !bottomPresent) return true

        configDirectory.mkdirs()
        val topSpec = json.optImage("topImage", "TopImage")
        val bottomSpec = json.optImage("bottomImage", "BottomImage")
        val topTarget = File(configDirectory, "brandlogo.png")
        val bottomTarget = File(configDirectory, "globalconnectlogo.png")
        val topStage = File(configDirectory, ".brandlogo.download")
        val bottomStage = File(configDirectory, ".globalconnectlogo.download")
        val topBackup = File(configDirectory, ".brandlogo.backup")
        val bottomBackup = File(configDirectory, ".globalconnectlogo.backup")

        return try {
            if (topPresent && topSpec != null) download(topSpec, topStage)
            if (bottomPresent && bottomSpec != null) download(bottomSpec, bottomStage)
            backup(topTarget, topBackup)
            backup(bottomTarget, bottomBackup)
            try {
                if (topPresent) replace(topTarget, topStage, topSpec)
                if (bottomPresent) replace(bottomTarget, bottomStage, bottomSpec)
            } catch (exception: Exception) {
                restore(topTarget, topBackup)
                restore(bottomTarget, bottomBackup)
                throw exception
            }
            Log.i(
                IMAGE_TAG,
                "Launcher images applied top=${if (topPresent) topSpec != null else "unchanged"} " +
                    "bottom=${if (bottomPresent) bottomSpec != null else "unchanged"}",
            )
            true
        } catch (exception: Exception) {
            Log.e(IMAGE_TAG, "Launcher image update failed: ${exception.message}", exception)
            false
        } finally {
            topStage.delete()
            bottomStage.delete()
            topBackup.delete()
            bottomBackup.delete()
        }
    }

    internal fun parseImage(json: JSONObject, vararg keys: String): LauncherImageSpec? =
        json.optImage(*keys)

    private fun download(spec: LauncherImageSpec, destination: File) {
        require(spec.sizeBytes in 1..MAX_IMAGE_BYTES) { "image_size_invalid" }
        require(spec.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "image_sha256_invalid" }
        require(spec.downloadUrl.startsWith("https://")) { "image_url_invalid" }

        val connection = URL(spec.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = IMAGE_CONNECT_TIMEOUT_MS
            connection.readTimeout = IMAGE_READ_TIMEOUT_MS
            connection.doInput = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("image_http_${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES || total > spec.sizeBytes) {
                            throw IllegalStateException("image_too_large")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        require(destination.length() == spec.sizeBytes) { "image_size_mismatch" }
        require(sha256(destination).equals(spec.sha256, ignoreCase = true)) {
            "image_sha256_mismatch"
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(destination.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "image_decode_failed" }
    }

    private fun replace(target: File, staged: File, spec: LauncherImageSpec?) {
        if (spec == null) {
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("image_remove_failed")
            }
            return
        }
        staged.copyTo(target, overwrite = true)
    }

    private fun backup(source: File, backup: File) {
        backup.delete()
        if (source.exists()) source.copyTo(backup, overwrite = true)
    }

    private fun restore(target: File, backup: File) {
        if (backup.exists()) backup.copyTo(target, overwrite = true) else target.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JSONObject.optImage(vararg keys: String): LauncherImageSpec? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val image = optJSONObject(key) ?: throw IllegalArgumentException("image_payload_invalid")
            return LauncherImageSpec(
                image.optStringAny("downloadUrl", "DownloadUrl"),
                image.optStringAny("sha256", "Sha256"),
                image.optLongAny("sizeBytes", "SizeBytes"),
            )
        }
        return null
    }

    private fun JSONObject.hasAny(vararg keys: String): Boolean = keys.any(::has)

    private fun JSONObject.optStringAny(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "")
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun JSONObject.optLongAny(vararg keys: String): Long {
        for (key in keys) {
            if (has(key)) return optLong(key, -1)
        }
        return -1
    }
}
