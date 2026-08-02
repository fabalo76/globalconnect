package one.globalconnect.xtmsagent.params

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

private const val TAG = "ParamManager"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS    = 60_000
private const val CONFIG_RESPONSE_TIMEOUT_MS = 90_000L

/**
 * Intent action declared in the payment application's MainActivity intent-filter.
 * xTMSAgent uses this to discover the installed payment app at runtime, without
 * depending on the payment app's package name (which varies per deployment).
 */
private const val ACTION_PAY_APP = "android.intent.action.PAY_APP"

/**
 * Manages the full parameter download pipeline for the terminal.
 *
 * Two entry points:
 *   - [requestParamDownload]: called by [one.globalconnect.xtmsagent.ParamReceiver] (payment app pull) or
 *     [one.globalconnect.xtmsagent.mqtt.notifications.TmsNotificationHandler] (TMS server push).
 *   - [onParamReady] / [onParamFailed]: called by [TmsMqttManager] when the MQTT paramres
 *     ack arrives from the broker.
 *
 * Download flow:
 *   1. Publish paramreq via MQTT (ft=json) to request the server prepare the file.
 *   2. Broker replies on paramres with {"ok":true} or {"ok":false,"err":"..."}.
 *   3. On ok:  HTTP POST to ParamDownload.aspx, decompress .json.gz, save params.json.
 *   4. Discover payment app via PAY_APP intent filter and broadcast ACTION_PARAMS_READY
 *      with a FileProvider content:// URI so the app can read the file.
 */
object ParamManager {

    /**
     * Guards against concurrent download pipelines (e.g. a payment app pull arriving
     * while a TMS push is already in flight).  Only one pipeline runs at a time.
     */
    private val downloadInProgress = AtomicBoolean(false)
    private val downloadGeneration = AtomicLong(0)
    private val timeoutHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var requestedApplicationId: String? = null
    @Volatile
    private var timeoutRunnable: Runnable? = null
    private val configChunkLock = Any()
    private val configChunks = mutableMapOf<String, ConfigChunkAccumulator>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initiates a parameter download by publishing a paramreq to the MQTT broker.
     *
     * If MQTT is not connected the download cannot proceed; the payment app (if
     * any) is notified with [ParamConstants.ACTION_PARAMS_FAILED].
     *
     * Must NOT be called on the main thread — it may log and check MQTT state.
     *
     * @param context Application context.
     */
    fun requestParamDownload(context: Context, applicationId: String? = null): Boolean {
        if (!downloadInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Download already in progress — ignoring concurrent request")
            return false
        }

        requestedApplicationId = applicationId?.trim()?.takeIf { it.isNotEmpty() }
        val generation = downloadGeneration.incrementAndGet()

        Log.i(TAG, "Requesting parameter download via MQTT config/request applicationId=${requestedApplicationId ?: "(none)"}")
        val published = TmsMqttManager.publishParamReq(
            fileType = "json",
            applicationIds = requestedApplicationId?.let(::listOf).orEmpty(),
        )
        if (!published) {
            Log.w(TAG, "MQTT not connected — cannot send paramreq")
            clearDownloadState(generation)
            notifyPaymentAppFailed(context.applicationContext, "MQTT not connected")
            return false
        } else {
            scheduleConfigResponseTimeout(context.applicationContext, generation, requestedApplicationId)
        }
        // On success: the pipeline continues in onParamReady() when the broker replies.
        return true
    }

    /**
     * Called by [TmsMqttManager] when the broker sends {"ok":true} on the paramres topic.
     *
     * Starts the HTTP download on a background thread and releases [downloadInProgress]
     * in all exit paths.
     *
     * @param context Application context.
     */
    fun onParamReady(context: Context) {
        val generation = downloadGeneration.get()
        Log.i(TAG, "Param-ready ack received — starting HTTP download")
        Thread {
            try {
                downloadAndNotify(context.applicationContext)
            } finally {
                clearDownloadState(generation)
            }
        }.start()
    }

    /**
     * Called by [TmsMqttManager] when the broker sends {"ok":false} on the paramres topic.
     *
     * @param context Application context.
     * @param error   Error string from the broker, or null.
     */
    fun onParamFailed(context: Context, error: String?) {
        Log.e(TAG, "Param-ready ack: server reported failure — $error")
        clearDownloadState(downloadGeneration.get())
        notifyPaymentAppFailed(context.applicationContext, error ?: "server unavailable")
    }

    fun isDownloadInProgress(): Boolean = downloadInProgress.get()

    fun onConfigResponse(context: Context, payloadText: String) {
        val generation = downloadGeneration.get()
        Thread {
            var terminalResponse = false
            try {
                val completePayload = acceptConfigResponsePayload(payloadText)
                if (completePayload == null) {
                    return@Thread
                }
                terminalResponse = true

                val failureMessage = readConfigFailure(completePayload)
                if (failureMessage != null) {
                    Log.w(TAG, "Config response reported failure: $failureMessage")
                    notifyPaymentAppFailed(context.applicationContext, failureMessage)
                    return@Thread
                }

                val selectedConfigs = selectConfigurationsForDelivery(completePayload)
                if (selectedConfigs.isEmpty()) {
                    notifyPaymentAppFailed(context.applicationContext, "No supported application configuration in response")
                    return@Thread
                }

                selectedConfigs.forEach { selectedConfig ->
                    val applicationId = selectedConfig.optString("applicationId", "").trim()
                    val jsonBytes = selectedConfig.toString().toByteArray(Charsets.UTF_8)
                    val paramsFile = saveParamsFile(context.applicationContext, jsonBytes, applicationId)
                        ?: run {
                            notifyParameterClientFailed(
                                context.applicationContext,
                                applicationId,
                                "Could not save params file",
                            )
                            return@forEach
                        }

                    Log.i(
                        TAG,
                        "Config response saved: applicationId=${applicationId.ifBlank { "(unknown)" }} " +
                            "schemaVersion=${selectedConfig.opt("schemaVersion") ?: "(none)"} " +
                            "path=${paramsFile.absolutePath} bytes=${jsonBytes.size}"
                    )
                    notifyParameterClientReady(context.applicationContext, paramsFile, applicationId)
                }
            } catch (e: Exception) {
                terminalResponse = true
                Log.e(TAG, "Config response handling failed: ${e.message}", e)
                notifyPaymentAppFailed(context.applicationContext, e.message ?: "config response error")
            } finally {
                if (terminalResponse) {
                    clearDownloadState(generation)
                }
            }
        }.start()
    }

    // ── HTTP download ─────────────────────────────────────────────────────────

    private fun scheduleConfigResponseTimeout(
        context: Context,
        generation: Long,
        applicationId: String?,
    ) {
        val runnable = Runnable {
            if (downloadGeneration.get() != generation) return@Runnable
            if (!downloadInProgress.compareAndSet(true, false)) return@Runnable

            requestedApplicationId = null
            timeoutRunnable = null
            val message = "Timed out waiting for Global Connect config/response after ${CONFIG_RESPONSE_TIMEOUT_MS / 1000}s"
            Log.w(TAG, "$message applicationId=${applicationId ?: "(none)"} generation=$generation")
            notifyPaymentAppFailed(context.applicationContext, message)
        }

        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, CONFIG_RESPONSE_TIMEOUT_MS)
    }

    private fun clearDownloadState(generation: Long) {
        if (downloadGeneration.get() != generation) return
        timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        timeoutRunnable = null
        downloadInProgress.set(false)
        requestedApplicationId = null
    }

    private fun downloadAndNotify(context: Context) {
        val cfg    = TMSFunc.tmsCfg
        val termId = cfg.sn
        val secret = cfg.download_secret
        val server = cfg.server_addr
        val port   = cfg.web_port

        if (termId.isBlank() || secret.isBlank() || server.isBlank()) {
            Log.w(TAG, "TMS config not ready — tid='$termId' secret_set=${secret.isNotBlank()} " +
                "server='$server'")
            notifyPaymentAppFailed(context, "TMS config not ready")
            return
        }

        val apiKey = computeHmacKey(termId, secret)
        val applicationId = requestedApplicationId
        val body   = buildDownloadRequestBody(termId, apiKey, applicationId)
        val url    = "${cfg.webScheme}://$server:$port/Download/ParamDownload.aspx"

        Log.i(TAG, "Downloading params.json.gz from $server:$port")

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput       = true
                conn.doInput        = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout    = READ_TIMEOUT_MS

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    val errMsg = conn.errorStream?.bufferedReader()?.readText() ?: "(no body)"
                    Log.e(TAG, "HTTP $status from ParamDownload.aspx: $errMsg")
                    notifyPaymentAppFailed(context, "HTTP $status")
                    return
                }

                val gzipBytes = conn.inputStream.readBytes()
                val jsonBytes = decompressGzip(gzipBytes)

                val paramsFile = saveParamsFile(context, jsonBytes)
                    ?: run {
                        notifyPaymentAppFailed(context, "Could not save params file")
                        return
                    }

                Log.i(TAG, "Params saved: ${paramsFile.absolutePath} (${jsonBytes.size} bytes raw)")
                notifyParameterClientReady(context, paramsFile)

            } finally {
                conn.disconnect()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Param download failed: ${e.message}", e)
            notifyPaymentAppFailed(context, e.message ?: "download error")
        }
    }

    // ── File storage ──────────────────────────────────────────────────────────

    private fun saveParamsFile(context: Context, data: ByteArray, applicationId: String? = null): File? {
        return try {
            val dir = File(context.filesDir, ParamConstants.PARAMS_FOLDER)
            dir.mkdirs()
            val suffix = applicationId
                ?.trim()
                ?.lowercase()
                ?.replace(Regex("[^a-z0-9_-]"), "_")
                ?.takeIf { it.isNotBlank() }
            val file = File(
                dir,
                suffix?.let { "params-$it.json" } ?: ParamConstants.PARAMS_FILENAME,
            )
            file.writeBytes(data)
            file
        } catch (e: Exception) {
            Log.e(TAG, "Could not write params file: ${e.message}", e)
            null
        }
    }

    // ── Payment app notification ──────────────────────────────────────────────

    /**
     * Broadcasts [ParamConstants.ACTION_PARAMS_READY] to the payment app with a
     * FileProvider URI granting read access to the decompressed params JSON.
     *
     * The URI permission grant is explicit — no AndroidManifest entry required on
     * the payment app side.  The payment app should consume the file immediately;
     * the grant is not persistent across process restarts.
     */
    private fun notifyParameterClientReady(
        context: Context,
        paramsFile: File,
        applicationId: String? = requestedApplicationId,
    ) {
        val paymentPkg = findParameterClientPackage(context, applicationId)
        if (paymentPkg == null) {
            Log.w(TAG, "No payment app found (PAY_APP intent filter) — params saved locally but not forwarded")
            return
        }

        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", paramsFile)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider URI error: ${e.message}", e)
            notifyPaymentAppFailed(context, "FileProvider error")
            return
        }

        // Grant read permission before sending the broadcast so the payment app can
        // open the URI immediately in its onReceive() call.
        context.grantUriPermission(paymentPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        context.sendBroadcast(
            Intent(ParamConstants.ACTION_PARAMS_READY).apply {
                `package` = paymentPkg
                putExtra(ParamConstants.EXTRA_PARAMS_URI, uri.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )

        Log.i(TAG, "ACTION_PARAMS_READY → $paymentPkg, uri=$uri")
    }

    private fun notifyPaymentAppFailed(context: Context, error: String) {
        notifyParameterClientFailed(context, requestedApplicationId, error)
    }

    private fun notifyParameterClientFailed(context: Context, applicationId: String?, error: String) {
        val paymentPkg = findParameterClientPackage(context, applicationId) ?: return
        context.sendBroadcast(
            Intent(ParamConstants.ACTION_PARAMS_FAILED).apply {
                `package` = paymentPkg
                putExtra(ParamConstants.EXTRA_ERROR_MESSAGE, error)
            }
        )
        Log.i(TAG, "ACTION_PARAMS_FAILED → $paymentPkg: $error")
    }

    // ── Payment app discovery ─────────────────────────────────────────────────

    /**
     * Discovers the installed payment application by querying for activities that
     * declare the [ACTION_PAY_APP] intent filter.
     *
     * Returns the package name of the first matching app, or null if none is installed.
     * Requires QUERY_ALL_PACKAGES permission (already declared in the manifest).
     */
    @Suppress("DEPRECATION")
    private fun findPaymentAppPackage(context: Context): String? {
        val intent = Intent(ACTION_PAY_APP)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent, PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            context.packageManager.queryIntentActivities(intent, 0)
        }
        val pkg = matches.firstOrNull()?.activityInfo?.packageName
        if (pkg == null) Log.d(TAG, "queryIntentActivities(PAY_APP) returned no results")
        else Log.d(TAG, "Payment app discovered: $pkg")
        return pkg
    }

    private fun findParameterClientPackage(context: Context, applicationId: String?): String? {
        return if (applicationId.equals(PINPAD_APPLICATION_ID, ignoreCase = true)) {
            runCatching {
                context.packageManager.getApplicationInfo(PINPAD_PACKAGE, 0)
                PINPAD_PACKAGE
            }.getOrNull().also {
                if (it == null) Log.w(TAG, "PINPAD_APP requested but $PINPAD_PACKAGE is not installed")
            }
        } else {
            findPaymentAppPackage(context)
        }
    }

    private const val PINPAD_APPLICATION_ID = "PINPAD_APP"
    private const val PAYMENT_APPLICATION_ID = "PAYMENT_APP"
    private const val PINPAD_PACKAGE = "one.globalconnect.pinpad"

    // ── Crypto / helpers ──────────────────────────────────────────────────────

    private fun computeHmacKey(termId: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(termId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun decompressGzip(compressed: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(compressed.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun String.jsonEscape() =
        replace("\\", "\\\\").replace("\"", "\\\"")

    private fun buildDownloadRequestBody(
        termId: String,
        apiKey: String,
        applicationId: String?,
    ): String {
        val base = StringBuilder()
            .append("{\"tid\":\"").append(termId.jsonEscape())
            .append("\",\"key\":\"").append(apiKey.jsonEscape())
            .append("\",\"ft\":\"json\"")
        if (!applicationId.isNullOrBlank()) {
            base.append(",\"applicationIds\":[\"")
                .append(applicationId.jsonEscape())
                .append("\"]")
        }
        return base.append("}").toString()
    }

    private fun selectRequestedConfiguration(payloadText: String): JSONObject? {
        val root = JSONObject(payloadText)
        val configurations = root.optJSONArray("configurations")
        if (configurations == null) {
            if (root.has("catalogTables") && root.has("tree")) return normalizeCompiledConfig(root)
            root.optJSONObject("configuration")?.let { configuration ->
                val normalized = normalizeCompiledConfig(configuration)
                val requested = requestedApplicationId
                if (normalized.optString("applicationId").isBlank() && !requested.isNullOrBlank()) {
                    normalized.put("applicationId", requested)
                }
                Log.i(
                    TAG,
                    "config/response single configuration selected applicationId=${normalized.optString("applicationId", "(none)")} " +
                        "schemaVersion=${normalized.opt("schemaVersion") ?: "(none)"} " +
                        "catalogTables=${normalized.optJSONObject("catalogTables")?.length() ?: 0} " +
                        "treeNodes=${normalized.optJSONObject("tree")?.length() ?: 0}"
                )
                return normalized
            }
            decodeInlineConfigData(root)?.let { decoded ->
                Log.i(
                    TAG,
                    "config/response inline data decoded keys=${decoded.keys().asSequence().joinToString()} " +
                        "applicationId=${decoded.optString("applicationId", "(none)")}"
                )
                return selectRequestedConfiguration(decoded.toString())
            }
            Log.w(TAG, "config/response has no configurations array. keys=${root.keys().asSequence().joinToString()}")
            return null
        }

        val requested = requestedApplicationId
        Log.i(TAG, "config/response contains ${configurations.length()} configuration(s), requestedApplicationId=${requested ?: "(none)"}")
        for (i in 0 until configurations.length()) {
            val item = configurations.optJSONObject(i) ?: continue
            val applicationId = item.optString("applicationId", "")
            val configuration = item.optJSONObject("configuration") ?: item
            val normalized = normalizeCompiledConfig(configuration)
            val normalizedApplicationId = normalized.optString("applicationId", applicationId)
            Log.d(
                TAG,
                "config/response[$i] applicationId=${applicationId.ifBlank { normalizedApplicationId }} " +
                    "schemaVersion=${normalized.opt("schemaVersion") ?: "(none)"} " +
                    "catalogTables=${normalized.optJSONObject("catalogTables")?.length() ?: 0} " +
                    "treeNodes=${normalized.optJSONObject("tree")?.length() ?: 0}"
            )
            if (requested.isNullOrBlank() ||
                applicationId.equals(requested, ignoreCase = true) ||
                normalizedApplicationId.equals(requested, ignoreCase = true)
            ) {
                return normalized
            }
        }
        return null
    }

    private fun selectConfigurationsForDelivery(payloadText: String): List<JSONObject> {
        if (!requestedApplicationId.isNullOrBlank()) {
            return listOfNotNull(selectRequestedConfiguration(payloadText))
        }

        val root = JSONObject(payloadText)
        val configurations = root.optJSONArray("configurations")
            ?: return listOfNotNull(selectRequestedConfiguration(payloadText))
        val result = mutableListOf<JSONObject>()
        for (index in 0 until configurations.length()) {
            val item = configurations.optJSONObject(index) ?: continue
            val wrapperApplicationId = item.optString("applicationId", "").trim()
            val normalized = normalizeCompiledConfig(item.optJSONObject("configuration") ?: item)
            if (normalized.optString("applicationId").isBlank() && wrapperApplicationId.isNotBlank()) {
                normalized.put("applicationId", wrapperApplicationId)
            }
            val applicationId = normalized.optString("applicationId", wrapperApplicationId)
            if (applicationId.equals(PAYMENT_APPLICATION_ID, ignoreCase = true)
                || applicationId.equals(PINPAD_APPLICATION_ID, ignoreCase = true)
            ) {
                result += normalized
            }
        }
        return result
    }

    private fun readConfigFailure(payloadText: String): String? {
        val root = JSONObject(payloadText)
        val success = root.opt("success")
        val ok = root.opt("ok")
        val errorCode = root.optString("errorCode", "").trim()
        val errorMessage = root.optString("errorMessage", "").trim()
        val failed = success == false || ok == false || errorCode.isNotEmpty() || errorMessage.isNotEmpty()
        if (!failed) return null

        return when {
            errorCode.isNotEmpty() && errorMessage.isNotEmpty() -> "$errorCode: $errorMessage"
            errorMessage.isNotEmpty() -> errorMessage
            errorCode.isNotEmpty() -> errorCode
            else -> "Global Connect rejected the configuration request"
        }
    }

    private fun normalizeCompiledConfig(config: JSONObject): JSONObject {
        val normalized = JSONObject(config.toString())
        return normalized
    }

    private fun decodeInlineConfigData(root: JSONObject): JSONObject? {
        val data = root.optString("data", "").trim()
        if (data.isBlank()) return null

        val encoding = root.optString("encoding", "").lowercase()
        return try {
            val decodedBytes = if (encoding.contains("base64")) {
                Base64.decode(data, Base64.DEFAULT)
            } else {
                data.toByteArray(Charsets.UTF_8)
            }
            val jsonBytes = if (encoding.contains("gzip")) {
                decompressGzip(decodedBytes)
            } else {
                decodedBytes
            }
            JSONObject(String(jsonBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Could not decode inline config/response data: ${e.message}", e)
            null
        }
    }

    /**
     * Reassembles the bounded chunked envelope used by Global Connect when a
     * compressed configuration is too large for one MQTT publish.
     *
     * Returns null while more chunks are required, otherwise returns either
     * the original payload or a regular inline-data envelope that the existing
     * decoder can process.
     */
    private fun acceptConfigResponsePayload(payloadText: String): String? {
        val root = JSONObject(payloadText)
        if (!root.optBoolean("chunked", false)) {
            return payloadText
        }

        val requestId = root.optString("requestId", "").trim()
        val encoding = root.optString("encoding", "").trim()
        val chunkIndex = root.optInt("chunkIndex", -1)
        val chunkCount = root.optInt("chunkCount", -1)
        val data = root.optString("data", "")
        require(requestId.isNotBlank()) { "Chunked config response is missing requestId" }
        require(encoding.contains("gzip", ignoreCase = true) &&
            encoding.contains("base64", ignoreCase = true)) {
            "Unsupported chunked config encoding '$encoding'"
        }
        require(chunkCount in 1..MAX_CONFIG_CHUNKS) {
            "Invalid config chunk count $chunkCount"
        }
        require(chunkIndex in 0 until chunkCount) {
            "Invalid config chunk index $chunkIndex/$chunkCount"
        }
        require(data.length <= MAX_CONFIG_CHUNK_CHARS) {
            "Config chunk $chunkIndex is too large"
        }

        synchronized(configChunkLock) {
            val now = System.currentTimeMillis()
            configChunks.entries.removeAll { now - it.value.createdAtMs > CONFIG_CHUNK_TTL_MS }

            val accumulator = configChunks.getOrPut(requestId) {
                ConfigChunkAccumulator(
                    encoding = encoding,
                    chunkCount = chunkCount,
                    chunks = arrayOfNulls(chunkCount),
                    createdAtMs = now,
                )
            }
            require(accumulator.chunkCount == chunkCount && accumulator.encoding == encoding) {
                "Config chunk metadata changed for request $requestId"
            }

            val previous = accumulator.chunks[chunkIndex]
            require(previous == null || previous == data) {
                "Conflicting duplicate config chunk $chunkIndex"
            }
            accumulator.chunks[chunkIndex] = data

            val received = accumulator.chunks.count { it != null }
            Log.i(TAG, "Config response chunk received requestId=$requestId part=${chunkIndex + 1}/$chunkCount received=$received")
            if (received != chunkCount) return null

            val encoded = buildString {
                accumulator.chunks.forEach { append(requireNotNull(it)) }
            }
            configChunks.remove(requestId)
            require(encoded.length <= MAX_CONFIG_ENCODED_CHARS) {
                "Reassembled config response is too large"
            }

            Log.i(TAG, "Config response chunks reassembled requestId=$requestId encodedChars=${encoded.length}")
            return JSONObject()
                .put("success", true)
                .put("requestId", requestId)
                .put("encoding", encoding)
                .put("data", encoded)
                .toString()
        }
    }

    private data class ConfigChunkAccumulator(
        val encoding: String,
        val chunkCount: Int,
        val chunks: Array<String?>,
        val createdAtMs: Long,
    )

    private const val MAX_CONFIG_CHUNKS = 512
    private const val MAX_CONFIG_CHUNK_CHARS = 16 * 1024
    private const val MAX_CONFIG_ENCODED_CHARS = 8 * 1024 * 1024
    private const val CONFIG_CHUNK_TTL_MS = 2 * CONFIG_RESPONSE_TIMEOUT_MS
}
