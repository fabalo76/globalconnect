package one.globalconnect.xtmsagent.mqtt.versions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.R
import one.globalconnect.xtmsagent.TMSFunc
import one.globalconnect.xtmsagent.mqtt.TmsTaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "AppUpdateManager"

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS    = 300_000

/**
 * Downloads and installs the terminal's assigned prog-app APK when a verinfo response
 * indicates that the installed version differs from the server-assigned version.
 *
 * Download endpoint: https://{server}:{port}/Download/AppFileDownload.aspx
 * Request body: {"tid":"...","key":"<hmac>","fileId":<int>}
 * Auth: HMAC-SHA256(key=DownloadSecret, data=termId), Base64url-encoded.
 *
 * Installation is performed silently via the Nexgo Platform SDK.
 * A fresh status report is published after successful install.
 */
private const val ACTION_PRE_INSTALL_CHECK   = "one.globalconnect.xtmsagent.ACTION_PRE_INSTALL_CHECK"
private const val ACTION_PRE_INSTALL_DISMISS = "one.globalconnect.xtmsagent.ACTION_PRE_INSTALL_DISMISS"
private const val EXTRA_PRE_INSTALL_PKG      = "pkg"
private const val EXTRA_PRE_INSTALL_VER      = "ver"
private const val EXTRA_PRE_INSTALL_VER_CODE = "verCode"
private const val EXTRA_PRE_INSTALL_SENDER   = "senderPkg"
private const val CONSENT_TIMEOUT_MS         = 30_000L

object AppUpdateManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Keyed by packageId — holds the in-flight deferred for a consent check that is
    // currently awaiting a response from the target app.
    private val consentMap = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Kicks off a background download + install for the given [result].
     * No-op if [result.needsAppUpdate] is false or required fields are missing.
     *
     * @param publishFullStatus called after successful install to refresh server inventory.
     */
    fun downloadAndInstallIfNeeded(
        context: Context,
        result: VersionCheckResult,
        publishFullStatus: () -> Unit
    ) {
        if (!result.needsAppUpdate) return

        for (app in result.appsToUpdate) {
            scope.launch {
                val displayName = app.appName ?: app.packageId
                try {
                    Log.i(TAG, "Downloading app update: fileId=${app.fileId} pkg=${app.packageId} " +
                        "installed=${app.installedVersion ?: "(not installed)"} server=${app.serverVersion}")
                    MainActivity.writeLog("App update download started: ${app.packageId} v${app.serverVersion}")
                    showToast(context, context.getString(R.string.app_update_downloading, displayName, app.serverVersion))

                    val apkFile = downloadApk(context, app.fileId, displayName)

                    // Ask the target app for install consent before proceeding.
                    val canInstall = requestConsent(context, app)
                    if (!canInstall) {
                        // Target app is busy — persist the task and wait for its GO signal.
                        AppInstallPendingStore.store(
                            context,
                            AppInstallPendingStore.PendingInstallInfo(
                                fileId            = app.fileId,
                                packageId         = app.packageId,
                                appName           = app.appName,
                                serverVersion     = app.serverVersion,
                                serverVersionCode = app.serverVersionCode,
                                apkPath           = apkFile.absolutePath
                            )
                        )
                        Log.i(TAG, "Install deferred for ${app.packageId} — APK kept at ${apkFile.absolutePath}")
                        showToast(context, context.getString(R.string.app_update_deferred, displayName))
                        return@launch
                    }

                    showToast(context, context.getString(R.string.app_update_installing, displayName))
                    installApk(context, apkFile, app.packageId, app.appName, publishFullStatus)

                } catch (e: Exception) {
                    Log.e(TAG, "App update failed for fileId=${app.fileId}: ${e.message}", e)
                    MainActivity.writeLog("App update failed for ${app.packageId}: ${e.message}")
                    showToast(context, context.getString(R.string.app_update_download_failed, displayName))
                    TmsTaskStatus.taskOverride.value = context.getString(R.string.app_update_download_failed, displayName)
                    delay(6_000)
                    TmsTaskStatus.taskOverride.value = null
                }
            }
        }
    }

    // ── Consent protocol ─────────────────────────────────────────────────────

    /**
     * Broadcasts [ACTION_PRE_INSTALL_CHECK] to the target package and waits up to
     * [CONSENT_TIMEOUT_MS] for a response via [onConsentResponse].
     *
     * Returns `true` if the target responded with proceed (or timed out — proceed by default),
     * `false` if the target explicitly postponed.
     */
    private suspend fun requestConsent(context: Context, app: AppUpdateInfo): Boolean {
        val targetPkg = app.packageId

        // Check whether the target app is installed and has a receiver for our check action.
        val probe     = Intent(ACTION_PRE_INSTALL_CHECK)
        val receivers = context.packageManager.queryBroadcastReceivers(probe, 0)
        val targetReceiver = receivers.find { it.activityInfo.packageName == targetPkg }

        if (targetReceiver == null) {
            Log.d(TAG, "No PRE_INSTALL_CHECK receiver in $targetPkg — proceeding without consent")
            return true
        }

        val deferred = CompletableDeferred<Boolean>()
        consentMap[targetPkg] = deferred

        try {
            Log.i(TAG, "Sending PRE_INSTALL_CHECK to $targetPkg ver=${app.serverVersion} verCode=${app.serverVersionCode}")
            context.sendBroadcast(Intent(ACTION_PRE_INSTALL_CHECK).apply {
                `package` = targetPkg
                putExtra(EXTRA_PRE_INSTALL_PKG,      targetPkg)
                putExtra(EXTRA_PRE_INSTALL_VER,      app.serverVersion)
                putExtra(EXTRA_PRE_INSTALL_VER_CODE, app.serverVersionCode)
                putExtra(EXTRA_PRE_INSTALL_SENDER,   context.packageName)
            })

            val response = withTimeoutOrNull(CONSENT_TIMEOUT_MS) { deferred.await() }
            if (response == null) {
                Log.w(TAG, "Consent timeout for $targetPkg — proceeding with install")
                return true
            }
            return response
        } finally {
            consentMap.remove(targetPkg)
        }
    }

    /**
     * Called by [AppConsentReceiver] when it receives a response from the target app.
     * Returns `true` if there was a waiting deferred (in-flight check), `false` if not.
     */
    fun onConsentResponse(packageId: String, proceed: Boolean): Boolean {
        val deferred = consentMap[packageId] ?: return false
        deferred.complete(proceed)
        return true
    }

    /**
     * Triggered by [AppConsentReceiver] when the target app later signals it is ready
     * for an install that was previously deferred.  Verifies version, then installs.
     */
    fun installFromPending(context: Context, pending: AppInstallPendingStore.PendingInstallInfo) {
        scope.launch {
            try {
                val apkFile = File(pending.apkPath)
                if (!apkFile.exists()) {
                    Log.e(TAG, "Deferred APK missing for ${pending.packageId}: ${pending.apkPath}")
                    AppInstallPendingStore.remove(context, pending.packageId)
                    return@launch
                }
                AppInstallPendingStore.remove(context, pending.packageId)
                val displayName = pending.appName ?: pending.packageId
                TmsTaskStatus.taskOverride.value = "Installing: $displayName"
                showToast(context, context.getString(R.string.app_update_installing, displayName))
                installApk(context, apkFile, pending.packageId, pending.appName) {
                    // publishFullStatus not available here — the MQTT manager will pick up
                    // the PACKAGE_ADDED broadcast and send a fresh status report.
                }
            } catch (e: Exception) {
                Log.e(TAG, "Deferred install failed for ${pending.packageId}: ${e.message}", e)
                TmsTaskStatus.taskOverride.value = "Install failed: ${pending.appName ?: pending.packageId}"
                delay(6_000)
                TmsTaskStatus.taskOverride.value = null
            }
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun downloadApk(
        context: Context,
        fileId: Int,
        displayName: String
    ): File {
        val cfg    = TMSFunc.tmsCfg
        val termId = cfg.sn
        val apiKey = computeHmacKey(termId, cfg.download_secret)
        val url    = "${cfg.webScheme}://${cfg.server_addr}:${cfg.web_port}/Download/AppFileDownload.aspx"
        val body   = """{"tid":"${termId.jsonEscape()}","key":"${apiKey.jsonEscape()}","fileId":$fileId}"""

        val dest = stagingFile(context, fileId)
        dest.parentFile?.mkdirs()
        dest.delete()

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.doInput  = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("AppFileDownload HTTP ${conn.responseCode}: $err")
            }

            val totalBytes = conn.contentLengthLong
            var bytesWritten = 0L
            var lastPct = -1
            TmsTaskStatus.taskOverride.value = "Downloading: $displayName"
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        bytesWritten += n
                        if (totalBytes > 0) {
                            val pct = (bytesWritten * 100L / totalBytes).toInt().coerceAtMost(100)
                            if (pct != lastPct) {
                                lastPct = pct
                                TmsTaskStatus.taskOverride.value = "Downloading: $displayName $pct%"
                            }
                        }
                    }
                }
            }
            if (dest.length() < 10_000) {
                Log.e(TAG, "AppFileDownload returned only ${dest.length()} bytes for fileId=$fileId — likely an error page")
                throw IllegalStateException("AppFileDownload response too small (${dest.length()} bytes) — not a valid APK")
            }
            Log.i(TAG, "Downloaded fileId=$fileId → ${dest.absolutePath} (${dest.length()} bytes)")
        } finally {
            conn.disconnect()
        }

        return dest
    }

    // ── Install ───────────────────────────────────────────────────────────────

    private fun installApk(
        context: Context,
        apkFile: File,
        packageName: String,
        appName: String?,
        publishFullStatus: () -> Unit
    ) {
        val displayName = appName ?: packageName
        // Copy to world-readable external cache — Nexgo SDK installer runs under a different UID
        // and cannot read files from the app's private internal storage.
        val extCache   = context.getExternalCacheDir() ?: context.cacheDir
        val installApk = File(extCache, "${apkFile.nameWithoutExtension}.apk")
        apkFile.copyTo(installApk, overwrite = true)
        installApk.setReadable(true, false)

        TmsTaskStatus.taskOverride.value = "Installing: $displayName"
        Log.i(TAG, "Installing $packageName via Nexgo SDK from ${installApk.absolutePath} (${installApk.length()} bytes)")

        try {
            val platform = com.nexgo.oaf.apiv3.APIProxy.getDeviceEngine(context).platform
            val sdkResult = platform.installApp(
                installApk.absolutePath,
                object : com.nexgo.oaf.apiv3.OnAppOperatListener {
                    override fun onOperatResult(res: Int) {
                        val success = res == com.nexgo.oaf.apiv3.SdkResult.Success
                        Log.i(TAG, "Nexgo SDK install result for $packageName: res=$res success=$success")
                        if (success) {
                            installApk.delete()
                            apkFile.delete()
                            MainActivity.writeLog("App update installed: $packageName")
                            showToast(context, context.getString(R.string.app_update_installed, displayName))
                            TmsTaskStatus.taskOverride.value = "Installed: $displayName"
                            publishFullStatus()
                        } else {
                            Log.e(TAG, "Nexgo SDK install failed for $packageName: res=$res — files kept for inspection: ${installApk.absolutePath}")
                            MainActivity.writeLog("App update install failed: $packageName res=$res")
                            showToast(context, context.getString(R.string.app_update_install_failed, displayName))
                            TmsTaskStatus.taskOverride.value = "Install failed: $displayName"
                        }
                        scope.launch {
                            delay(if (success) 3_000L else 6_000L)
                            TmsTaskStatus.taskOverride.value = null
                        }
                    }
                }
            )
            if (sdkResult != com.nexgo.oaf.apiv3.SdkResult.Success) {
                Log.e(TAG, "installApp() returned $sdkResult immediately for $packageName — files kept for inspection: ${installApk.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nexgo SDK install threw for $packageName: ${e.message} — files kept for inspection: ${installApk.absolutePath}", e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stagingFile(context: Context, fileId: Int): File {
        val dir = File(context.filesDir, "app_update_staging")
        dir.mkdirs()
        return File(dir, "appupdate_$fileId.dat")
    }

    private fun computeHmacKey(termId: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(termId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun showToast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
}
