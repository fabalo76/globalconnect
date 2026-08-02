package one.globalconnect.pinpad.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.security.PinpadSecurityConfigStore
import one.globalconnect.pinpad.storage.PinpadJpegStore
import one.globalconnect.pinpad.storage.PinpadMediaStore
import one.globalconnect.pinpad.storage.PinpadPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class PinpadTmsConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PARAMS_READY) return
        val uri = intent.getStringExtra(EXTRA_PARAMS_URI)?.let(Uri::parse) ?: return
        val pendingResult = goAsync()
        Thread {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    applyConfiguration(context.applicationContext, JSONObject(input.bufferedReader().readText()))
                } ?: error("TMS configuration URI could not be opened")
            } catch (error: Exception) {
                Log.e(TAG, "Unable to apply PINPAD_APP configuration", error)
                PinpadTraceLog.device("PINPAD_APP configuration failed=${error.message}")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun applyConfiguration(context: Context, config: JSONObject) {
        val fields = config.optJSONObject("fields") ?: config.firstTreeRecord()
        val securityStore = PinpadSecurityConfigStore(context)
        securityStore.setProtectSecretKeyInjection(
            fields.optBoolean(FIELD_PROTECT_SECRET_KEYS, securityStore.protectSecretKeyInjection()),
        )

        val keyload1 = fields.optionalString(FIELD_KEYLOAD_PASSWORD_1)
        val keyload2 = fields.optionalString(FIELD_KEYLOAD_PASSWORD_2)
        if (keyload1 != null || keyload2 != null) {
            check(keyload1 != null && keyload2 != null && securityStore.updateKeyLoadPasswords(keyload1, keyload2)) {
                "Both key-load passwords must be distinct seven-digit values"
            }
        }
        check(
            securityStore.updateSettingsPasswords(
                exitPassword1 = fields.optionalString(FIELD_EXIT_PASSWORD_1),
                exitPassword2 = fields.optionalString(FIELD_EXIT_PASSWORD_2),
                androidConfigPassword1 = fields.optionalString(FIELD_ANDROID_PASSWORD_1),
                androidConfigPassword2 = fields.optionalString(FIELD_ANDROID_PASSWORD_2),
            ),
        ) { "TMS settings passwords must contain 4 to 12 digits" }

        val preferences = PinpadPreferences(context)
        fields.optionalString(FIELD_DISPLAY_FONT_SIZE)?.let { option ->
            check(option.length == 1 && option[0] in '0'..'5') {
                "Display font size must be between 0 and 5"
            }
            preferences.setDisplayFontSize(option)
        }
        if (fields.has(FIELD_KEYPAD_BEEPER_ENABLED) && !fields.isNull(FIELD_KEYPAD_BEEPER_ENABLED)) {
            preferences.setKeypadBeeperEnabled(fields.getBoolean(FIELD_KEYPAD_BEEPER_ENABLED))
        }

        val emvResult = PinpadTmsEmvConfigurationApplier(context).apply(
            PinpadTmsEmvConfiguration(
                dataFormats = fields.embeddedTextFiles(FIELD_DATA_FORMAT_FILE, multiple = false),
                terminal = fields.embeddedTextFiles(FIELD_TERMINAL_CONFIG_FILE, multiple = false),
                caKeys = fields.embeddedTextFiles(FIELD_CA_KEY_FILES, multiple = true),
                contact = fields.embeddedTextFiles(FIELD_CONTACT_CONFIG_FILES, multiple = true),
                contactless = fields.embeddedTextFiles(FIELD_CONTACTLESS_CONFIG_FILES, multiple = true),
            ),
        )

        val catalogTables = config.optJSONObject("catalogTables")
        val images = catalogTables?.optJSONArray(CATALOG_IMAGES) ?: JSONArray()
        val media = catalogTables?.optJSONArray(CATALOG_MEDIA) ?: JSONArray()
        val importedImages = importAssets(context, images, AssetKind.Image)
        val importedMedia = importAssets(context, media, AssetKind.Media)
        PinpadTraceLog.device(
            "PINPAD_APP configuration applied protectSecretKeys=${securityStore.protectSecretKeyInjection()} " +
                "fontSize=${preferences.displayFontSize()} beeper=${preferences.keypadBeeperEnabled()} " +
                "emvSdk=${emvResult.sdkApplied} images=$importedImages/${images.length()} " +
                "media=$importedMedia/${media.length()}",
        )
    }

    private fun JSONObject.embeddedTextFiles(
        fieldName: String,
        multiple: Boolean,
    ): TmsConfigurationValue<List<TmsEmbeddedTextFile>> {
        if (!has(fieldName)) return TmsConfigurationValue(supplied = false, value = emptyList())
        val value = opt(fieldName)
        if (value == null || value == JSONObject.NULL) {
            return TmsConfigurationValue(supplied = true, value = emptyList())
        }
        val records = when {
            multiple && value is JSONArray -> (0 until value.length()).map { value.optJSONObject(it) }
            !multiple && value is JSONObject -> listOf(value)
            else -> error("$fieldName has an invalid embedded-file value")
        }
        check(records.size <= MAX_EMBEDDED_FILES) { "$fieldName contains too many files" }
        return TmsConfigurationValue(
            supplied = true,
            value = records.mapIndexed { index, record ->
                decodeEmbeddedTextFile(record ?: error("$fieldName[$index] is not a file"))
            },
        )
    }

    private fun decodeEmbeddedTextFile(record: JSONObject): TmsEmbeddedTextFile {
        val name = record.optString("name").trim()
        val declaredSize = record.optInt("size", -1)
        val expectedSha256 = record.optString("sha256").trim()
        val encoding = record.optString("encoding").trim()
        val encodedContent = record.optString("content")
        check(
            name.isNotBlank() &&
                name.length <= 180 &&
                name == File(name).name &&
                '/' !in name &&
                '\\' !in name
        ) {
            "Embedded configuration filename is invalid"
        }
        check(declaredSize in 0..MAX_EMBEDDED_FILE_BYTES) {
            "Embedded configuration file $name exceeds the size limit"
        }
        check(encoding.equals("base64", ignoreCase = true)) {
            "Embedded configuration file $name has an unsupported encoding"
        }
        check(expectedSha256.matches(SHA256_REGEX)) {
            "Embedded configuration file $name has an invalid checksum"
        }
        val bytes = Base64.decode(encodedContent, Base64.DEFAULT)
        check(bytes.size == declaredSize && bytes.size <= MAX_EMBEDDED_FILE_BYTES) {
            "Embedded configuration file $name size mismatch"
        }
        val computedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(computedSha256.equals(expectedSha256, ignoreCase = true)) {
            "Embedded configuration file $name checksum mismatch"
        }
        val contents = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return TmsEmbeddedTextFile(name, contents, computedSha256)
    }

    private fun importAssets(context: Context, records: JSONArray, kind: AssetKind): Int {
        var imported = 0
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val fileName = record.optString("fileName").trim()
            val downloadUrl = record.optString("downloadUrl").trim()
            val expectedSha256 = record.optString("sha256").trim()
            if (fileName.isBlank() || downloadUrl.isBlank() || !expectedSha256.matches(SHA256_REGEX)) {
                continue
            }
            val tempFile = File.createTempFile("pinpad-tms-", ".download", context.cacheDir)
            try {
                download(downloadUrl, tempFile, kind.maxBytes)
                check(sha256(tempFile).equals(expectedSha256, ignoreCase = true)) {
                    "Checksum mismatch for managed asset"
                }
                val success = when (kind) {
                    AssetKind.Image -> PinpadJpegStore(context).importManagedImage(fileName, tempFile)
                    AssetKind.Media -> PinpadMediaStore(context).importManagedMedia(fileName, tempFile)
                }
                if (success) imported += 1
            } catch (error: Exception) {
                Log.w(TAG, "Managed ${kind.name.lowercase()} import failed for $fileName", error)
                PinpadTraceLog.device(
                    "managed ${kind.name.lowercase()} import failed file=$fileName error=${error.message}",
                )
            } finally {
                tempFile.delete()
            }
        }
        return imported
    }

    private fun download(downloadUrl: String, target: File, maxBytes: Long) {
        val url = URL(downloadUrl)
        require(url.protocol.equals("https", ignoreCase = true)) { "Managed asset URL must use HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Managed asset HTTP ${connection.responseCode}"
            }
            val declaredLength = connection.contentLengthLong
            check(declaredLength in 0..maxBytes) { "Managed asset exceeds size limit" }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= maxBytes) { "Managed asset exceeds size limit" }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JSONObject.firstTreeRecord(): JSONObject {
        val tree = optJSONObject("tree") ?: return JSONObject()
        val keys = tree.keys()
        while (keys.hasNext()) {
            val records = tree.optJSONArray(keys.next()) ?: continue
            records.optJSONObject(0)?.let { return it }
        }
        return JSONObject()
    }

    private fun JSONObject.optionalString(name: String): String? =
        optString(name).trim().takeIf { it.isNotEmpty() }

    private enum class AssetKind(val maxBytes: Long) {
        Image(5L * 1024 * 1024),
        Media(64L * 1024 * 1024),
    }

    companion object {
        const val ACTION_PARAMS_READY = "one.globalconnect.xtmsagent.ACTION_PARAMS_READY"
        const val ACTION_PARAMS_FAILED = "one.globalconnect.xtmsagent.ACTION_PARAMS_FAILED"
        const val ACTION_REQUEST_PARAMS = "one.globalconnect.xtmsagent.ACTION_REQUEST_PARAMS"
        const val EXTRA_PARAMS_URI = "params_uri"
        const val EXTRA_APPLICATION_ID = "applicationId"
        const val APPLICATION_ID = "PINPAD_APP"
        private const val FIELD_PROTECT_SECRET_KEYS = "protectSecretKeyInjection"
        private const val FIELD_KEYLOAD_PASSWORD_1 = "keyloadPassword1"
        private const val FIELD_KEYLOAD_PASSWORD_2 = "keyloadPassword2"
        private const val FIELD_EXIT_PASSWORD_1 = "exitHomePassword1"
        private const val FIELD_EXIT_PASSWORD_2 = "exitHomePassword2"
        private const val FIELD_ANDROID_PASSWORD_1 = "androidConfigPassword1"
        private const val FIELD_ANDROID_PASSWORD_2 = "androidConfigPassword2"
        private const val FIELD_DISPLAY_FONT_SIZE = "displayFontSize"
        private const val FIELD_KEYPAD_BEEPER_ENABLED = "keypadBeeperEnabled"
        private const val FIELD_DATA_FORMAT_FILE = "dataFormatFile"
        private const val FIELD_TERMINAL_CONFIG_FILE = "emvTerminalConfigFile"
        private const val FIELD_CA_KEY_FILES = "emvCaKeyFiles"
        private const val FIELD_CONTACT_CONFIG_FILES = "emvContactConfigFiles"
        private const val FIELD_CONTACTLESS_CONFIG_FILES = "emvContactlessConfigFiles"
        private const val CATALOG_IMAGES = "images"
        private const val CATALOG_MEDIA = "media"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_EMBEDDED_FILE_BYTES = 256 * 1024
        private const val MAX_EMBEDDED_FILES = 64
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private const val TAG = "PinpadTmsConfig"
    }
}
