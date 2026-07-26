package one.globalconnect.xtmsagent.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Base64
import android.util.Log
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.net.DeviceApi
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "LauncherConfigMgr"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS    = 60_000

/**
 * Broadcast fired after a new LauncherConfig has been downloaded and applied.
 * MainActivity registers for this action in [onResume] and refreshes the button grid.
 */
const val ACTION_LAUNCHER_CONFIG_UPDATED = "one.globalconnect.xtmsagent.ACTION_LAUNCHER_CONFIG_UPDATED"

/**
 * Manages downloading, storing, and applying the TMS LauncherConfig JSON.
 *
 * The TMS server assigns a LauncherConfig to each terminal (TermMain.LauncherConfigID).
 * When the config changes, the server sets LauncherConfigNotifyPending=1 and sends an
 * MQTT notification with the LauncherConfigDl extended flag set.  The terminal then
 * calls [downloadAndApply] to fetch the new config from the server.
 *
 * Downloaded config JSON format (served gzip-compressed):
 * ```json
 * {
 *   "configId": 1,
 *   "configName": "Default",
 *   "blockUnknownApps": false,
 *   "generatedAt": "20260417143022",
 *   "apps": [
 *     { "appId": 1, "appName": "PayApp", "packageName": "com.example.pay", "btnColor": "#FF5733" }
 *   ]
 * }
 * ```
 *
 * On success the app list is written to [MainActivity.sPathLaunch] (LaunchAPP.xml) via
 * [MainActivity.SaveAppList] and [ACTION_LAUNCHER_CONFIG_UPDATED] is broadcast so
 * MainActivity can refresh the button grid.
 */
object LauncherConfigManager {

    private const val CFG_ID_FILE = "tms_launcher_config_id.txt"

    /**
     * Guards against concurrent [checkAndDownloadIfMissing] calls (e.g. two MQTT reconnects
     * firing within the same millisecond).  Only one download may run at a time.
     */
    private val downloadInProgress = AtomicBoolean(false)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if a LauncherConfig has been successfully applied on this terminal.
     * Used to gate HouseKeeping: HK must not run before the config is ready on fresh install.
     */
    fun isConfigApplied(context: Context): Boolean {
        val prefs = context.getSharedPreferences("tms_launcher", Context.MODE_PRIVATE)
        return readStoredConfigId(prefs).isNotBlank()
    }

    /**
     * Downloads the terminal's assigned LauncherConfig if none has been applied yet.
     *
     * Returns true if a download was attempted (first provisioning), false if a config
     * was already present. The caller uses this to decide whether to trigger verreq —
     * version checking is only needed when the assigned app set may have changed.
     *
     * Does NOT send a cfgack on success — the server did not set a pending flag for
     * the initial load; cfgack is only needed when responding to a server notification.
     */
    fun checkAndDownloadIfMissing(context: Context): Boolean {
        val internalPath = MainActivity.vg_sIntrenalPath
        if (internalPath.isBlank()) {
            Log.w(TAG, "vg_sIntrenalPath not yet set — skipping startup config check")
            return false
        }
        // Always restore persisted theme overrides, even when config file already exists
        restoreThemeFromPrefs(context)
        val idFile = File(internalPath, CFG_ID_FILE)
        if (idFile.exists()) {
            Log.d(TAG, "LauncherConfig already applied (configId=${idFile.readText().trim()}) — skipping")
            // Theme was restored from prefs above — trigger UI refresh so bar colors reach the window.
            context.sendBroadcast(
                Intent(ACTION_LAUNCHER_CONFIG_UPDATED).apply { `package` = context.packageName }
            )
            return false
        }

        // Prevent two concurrent calls (e.g. two rapid MQTT reconnects) from both
        // entering the download simultaneously, which would race on appList.
        if (!downloadInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Download already in progress — skipping concurrent call")
            return false
        }

        try {
            Log.i(TAG, "No LauncherConfig applied — triggering initial download")
            downloadAndApply(context, urgent = true, sendAck = false)
            return true
        } finally {
            downloadInProgress.set(false)
        }
    }

    /**
     * Downloads the terminal's assigned LauncherConfig from the TMS server and applies it.
     *
     * Called when:
     *  - An MQTT notification with [TmsNotifyExtFlags.LauncherConfigDl] is received ([sendAck]=true)
     *  - [checkAndDownloadIfMissing] finds no config has been applied yet ([sendAck]=false)
     *
     * When [sendAck] is true a [cfgack] is published on success so the server clears
     * [LauncherConfigNotifyPending] — making the notification persistent until confirmed.
     *
     * Must NOT be called on the main thread — runs synchronous network I/O.
     *
     * Auth: Base64url(HMAC-SHA256(key=download_secret, data=termId)) — same scheme as
     * [one.globalconnect.xtmsagent.download.TmsFileDownloader].
     */
    fun downloadAndApply(context: Context, urgent: Boolean = false, sendAck: Boolean = true) {
        Log.i(TAG, "Downloading LauncherConfig through AWS device endpoint (urgent=$urgent)")
        val success = downloadAndApplyAws(context, null)
        if (success && sendAck) {
            TmsMqttManager.publishCfgAck()
        }
        return

        val cfg    = TMSFunc.tmsCfg
        val termId = cfg.sn
        val secret = cfg.download_secret
        val server = cfg.server_addr
        val port   = cfg.web_port

        if (termId.isBlank() || secret.isBlank() || server.isBlank()) {
            Log.w(TAG, "TMS config not ready — tid='$termId' secret_set=${secret.isNotBlank()} " +
                "server='$server' — skipping launcher config download")
            return
        }

        val apiKey = computeHmacKey(termId, secret)
        val body   = """{"tid":"${termId.jsonEscape()}","key":"${apiKey.jsonEscape()}"}"""
        val url    = "${cfg.webScheme}://$server:$port/Download/LauncherConfigDownload.aspx"

        Log.i(TAG, "Downloading LauncherConfig from $server:$port (urgent=$urgent)")

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput      = true
                conn.doInput       = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout    = READ_TIMEOUT_MS

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    Log.i(TAG, "No LauncherConfig assigned to this terminal (HTTP 404) — skipping")
                    return
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    val errMsg = conn.errorStream?.bufferedReader()?.readText() ?: "(no body)"
                    Log.e(TAG, "Server returned HTTP $status for LauncherConfig: $errMsg")
                    return
                }

                val gzipBytes   = conn.inputStream.readBytes()
                val jsonBytes   = decompressGzip(gzipBytes)
                val json        = JSONObject(String(jsonBytes, Charsets.UTF_8))
                val newConfigId = json.optInt("configId", -1)

                Log.i(TAG, "LauncherConfig downloaded: configId=$newConfigId " +
                    "name='${json.optString("configName")}' " +
                    "apps=${json.optJSONArray("apps")?.length() ?: 0}")

                val newGeneratedAt = json.optString("generatedAt", "")
                val prefs = context.getSharedPreferences("tms_launcher", Context.MODE_PRIVATE)
                val storedId          = readStoredConfigId(prefs)
                val storedGeneratedAt = prefs.getString("generatedAt", "") ?: ""
                if (newConfigId != -1 && newConfigId.toString() == storedId &&
                    newGeneratedAt.isNotEmpty() && newGeneratedAt == storedGeneratedAt) {
                    Log.i(TAG, "LauncherConfig unchanged " +
                        "(configId=$newConfigId generatedAt=$newGeneratedAt) — skipping apply")
                    return
                }

                if (!applyConfig(context, json)) {
                    return
                }

                // ACK the server so it clears LauncherConfigNotifyPending.
                // Only sent when responding to a server-pushed notification (sendAck=true).
                if (sendAck) {
                    TmsMqttManager.publishCfgAck()
                }

            } finally {
                conn.disconnect()
            }

        } catch (e: Exception) {
            Log.e(TAG, "LauncherConfig download failed: ${e.message}", e)
        }
    }

    fun downloadAndApplyAws(context: Context, payload: JSONObject?): Boolean {
        val cfg = TMSFunc.tmsCfg
        val serial = cfg.sn.ifBlank { MainActivity.vg_sSN }
        if (serial.isBlank() || cfg.download_secret.isBlank() || cfg.apiHost.isBlank()) {
            Log.w(TAG, "AWS launcher config download skipped because device download settings are incomplete")
            return false
        }

        val endpointTemplate = payload?.optString("downloadEndpoint")
            ?.takeIf { it.isNotBlank() }
            ?: "/v1/devices/{serial}/downloads/launcher-config"
        val endpoint = endpointTemplate.replace("{serial}", serial.urlEncode())
        var lastFailure: Exception? = null
        for (url in DeviceApi.urls(endpoint)) {
            try {
                return downloadAndApplyAwsFromUrl(context, payload, serial, url)
            } catch (e: Exception) {
                if (!DeviceApi.isRecoverableHostFailure(e)) {
                    Log.e(TAG, "AWS LauncherConfig download failed: ${e.message}", e)
                    return false
                }
                lastFailure = e
                Log.w(TAG, "AWS LauncherConfig endpoint failed for $url: ${e.message}")
            }
        }
        Log.e(TAG, "AWS LauncherConfig download failed: ${lastFailure?.message ?: "endpoint is unavailable"}", lastFailure)
        return false
    }

    private fun downloadAndApplyAwsFromUrl(
        context: Context,
        payload: JSONObject?,
        serial: String,
        url: String
    ): Boolean {
        val cfg = TMSFunc.tmsCfg
        Log.i(TAG, "Downloading AWS LauncherConfig from $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Authorization", "Device ${computeDeviceToken(serial, cfg.download_secret)}")
            conn.doOutput = true
            conn.doInput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.outputStream.use { it.write((payload ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                Log.i(TAG, "No AWS LauncherConfig assigned to this terminal")
                return false
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val errMsg = conn.errorStream?.bufferedReader()?.readText() ?: "(no body)"
                Log.e(TAG, "AWS LauncherConfig HTTP ${conn.responseCode}: $errMsg")
                return false
            }

            val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val config = response.optJSONObject("configuration")
                ?: response.optJSONObject("Configuration")
                ?: response
            applyConfig(context, config)
        } catch (e: Exception) {
            if (DeviceApi.isRecoverableHostFailure(e)) throw e
            Log.e(TAG, "AWS LauncherConfig download failed: ${e.message}", e)
            false
        } finally {
            conn.disconnect()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun applyConfig(context: Context, json: JSONObject): Boolean {
        val configId         = readConfigId(json)
        val generatedAt      = json.optStringAny("generatedAt", "GeneratedAt")
        val blockUnknown     = json.optBooleanAny(default = false, "blockUnknownApps", "BlockUnknownApps")
        val enableNavBar     = json.optBooleanAny(default = true, "enableNavigationBar", "EnableNavigationBar")
        val enableControlBar = json.optBooleanAny(default = true, "enableControlBar", "EnableControlBar")
        val statusBarColor   = json.optStringAny("statusBarColor", "StatusBarColor")
        val navBarColor      = json.optStringAny("navigationBarColor", "NavigationBarColor")
        val appsArray        = json.optJSONArrayAny("apps", "Apps", "applications", "Applications")

        // Build new app list sorted by displayOrder
        val tempList = ArrayList<Pair<Int, MainActivity.Companion.AppInfo>>()
        if (appsArray != null) {
            for (i in 0 until appsArray.length()) {
                val app          = appsArray.getJSONObject(i)
                val appName      = app.optStringAny("appName", "AppName", "name", "Name")
                val pkgName      = app.optStringAny("packageName", "PackageName")
                val displayOrder = app.optIntAny(default = i * 10, "displayOrder", "DisplayOrder")
                val btnColorRaw  = app.optStringAny("btnColor", "BtnColor", "buttonColor", "ButtonColor")
                    .ifBlank { "FF888888" }
                if (pkgName.isBlank()) continue
                // Server sends ARGB hex without '#' (e.g. "FFe22f1c"); Color.parseColor needs '#'.
                val btnColorStr = if (btnColorRaw.startsWith("#")) btnColorRaw else "#$btnColorRaw"
                val btnColor = try { Color.parseColor(btnColorStr) }
                              catch (_: Exception) { 0xFF888888.toInt() }
                tempList.add(Pair(displayOrder, MainActivity.Companion.AppInfo(appName, pkgName, btnColor)))
            }
        }
        tempList.sortBy { it.first }
        val newAppList = ArrayList(tempList.map { it.second })

        // Persist config state to SharedPreferences
        context.getSharedPreferences("tms_launcher", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("blockUnknownApps", blockUnknown)
            .putString("configId", configId)
            .putString("generatedAt", generatedAt)
            .putBoolean("enableNavigationBar", enableNavBar)
            .putBoolean("enableControlBar", enableControlBar)
            .putString("statusBarColor", statusBarColor)
            .putString("navigationBarColor", navBarColor)
            .apply()

        // Apply theme overrides from TMS config
        MainActivity.stTheme.enable_navigation_bar = enableNavBar
        MainActivity.stTheme.enable_control_bar    = enableControlBar
        if (statusBarColor.isNotEmpty()) {
            MainActivity.stTheme.status_bar_color =
                if (statusBarColor.startsWith("#")) statusBarColor else "#$statusBarColor"
        }
        if (navBarColor.isNotEmpty()) {
            MainActivity.stTheme.navigation_bar_color =
                if (navBarColor.startsWith("#")) navBarColor else "#$navBarColor"
        }

        // If MainActivity hasn't finished Init() yet, sPathLaunch is blank —
        // we cannot write LaunchAPP.xml.  Do NOT write the marker file either;
        // leaving it absent lets checkAndDownloadIfMissing retry on the next reconnect.
        if (MainActivity.sPathLaunch.isBlank()) {
            Log.w(TAG, "sPathLaunch not set yet — will retry on next MQTT connect")
            return false
        }

        if (!LauncherImageManager.apply(File(MainActivity.vg_sIntrenalPath, "cfg"), json)) {
            Log.w(TAG, "LauncherConfig image download failed — configuration will be retried")
            return false
        }

        // Replace the in-memory app list and persist it.
        // If this throws for any reason the marker file is NOT written, so the
        // next startup will detect "no config applied" and retry cleanly.
        MainActivity.appList.clear()
        MainActivity.appList.addAll(newAppList)
        MainActivity.SaveAppList()

        // Write the marker ONLY after the app list has been successfully saved.
        // Writing it earlier (before SaveAppList) caused a stuck state on reinstall:
        // if SaveAppList threw the marker existed but the XML was empty/corrupt,
        // and every subsequent reconnect saw "already applied" and skipped the download.
        saveConfigIdMarker(configId)

        Log.i(TAG, "LauncherConfig applied: ${newAppList.size} app(s), " +
            "blockUnknownApps=$blockUnknown, enableNavBar=$enableNavBar, " +
            "enableControlBar=$enableControlBar, generatedAt=$generatedAt")

        // Broadcast so MainActivity refreshes its button grid if it is in the foreground
        context.sendBroadcast(
            Intent(ACTION_LAUNCHER_CONFIG_UPDATED).apply { `package` = context.packageName }
        )

        // Fire any HouseKeeping request that was deferred while waiting for this config.
        TmsMqttManager.triggerDeferredHouseKeepingIfPending()
        return true
    }

    private fun restoreThemeFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("tms_launcher", Context.MODE_PRIVATE)
        if (!prefs.contains("enableNavigationBar")) return  // no TMS theme saved yet
        MainActivity.stTheme.enable_navigation_bar = prefs.getBoolean("enableNavigationBar", true)
        MainActivity.stTheme.enable_control_bar    = prefs.getBoolean("enableControlBar", true)
        val statusBarColor = prefs.getString("statusBarColor", "") ?: ""
        val navBarColor    = prefs.getString("navigationBarColor", "") ?: ""
        if (statusBarColor.isNotEmpty()) {
            MainActivity.stTheme.status_bar_color =
                if (statusBarColor.startsWith("#")) statusBarColor else "#$statusBarColor"
        }
        if (navBarColor.isNotEmpty()) {
            MainActivity.stTheme.navigation_bar_color =
                if (navBarColor.startsWith("#")) navBarColor else "#$navBarColor"
        }
    }

    private fun saveConfigIdMarker(configId: String) {
        val internalPath = MainActivity.vg_sIntrenalPath
        if (internalPath.isBlank()) return
        try {
            File(internalPath, CFG_ID_FILE).writeText(configId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist config ID marker: ${e.message}")
        }
    }

    private fun readConfigId(json: JSONObject): String {
        val value = json.optString("configId", json.optString("ConfigId", ""))
        if (value.isNotBlank()) return value
        val legacyId = json.optInt("configId", -1)
        return if (legacyId >= 0) legacyId.toString() else ""
    }

    private fun JSONObject.optStringAny(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "")
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun JSONObject.optBooleanAny(default: Boolean, vararg keys: String): Boolean {
        for (key in keys) {
            if (has(key)) return optBoolean(key, default)
        }
        return default
    }

    private fun JSONObject.optIntAny(default: Int, vararg keys: String): Int {
        for (key in keys) {
            if (has(key)) return optInt(key, default)
        }
        return default
    }

    private fun JSONObject.optJSONArrayAny(vararg keys: String): org.json.JSONArray? {
        for (key in keys) {
            val value = optJSONArray(key)
            if (value != null) return value
        }
        return null
    }

    private fun readStoredConfigId(prefs: android.content.SharedPreferences): String {
        val value = try {
            prefs.getString("configId", "")
        } catch (_: ClassCastException) {
            ""
        }
        if (!value.isNullOrBlank()) return value
        val legacyId = try {
            prefs.getInt("configId", -1)
        } catch (_: ClassCastException) {
            -1
        }
        return if (legacyId >= 0) legacyId.toString() else ""
    }

    private fun decompressGzip(compressed: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(compressed.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun computeHmacKey(termId: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(termId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun computeDeviceToken(serial: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(serial.trim().uppercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
