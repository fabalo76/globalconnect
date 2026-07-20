package one.globalconnect.xtmsagent.licensing

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import org.json.JSONObject
import java.security.MessageDigest

class ApplicationLicensingService : Service() {
    private var messenger: Messenger? = null

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(IncomingHandler(Looper.getMainLooper()))
        Log.i(TAG, "Application licensing service created")
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onDestroy() {
        ApplicationLicenseBroker.failAll("LICENSE_SERVICE_STOPPED")
        messenger = null
        Log.i(TAG, "Application licensing service destroyed")
        super.onDestroy()
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            try {
                handleMessageSafely(message)
            } catch (e: Exception) {
                Log.e(TAG, "Rejected malformed licensing IPC message what=${message.what}", e)
                val requestId = runCatching { message.data.getString(KEY_REQUEST_ID).orEmpty() }.getOrDefault("")
                message.replyTo?.let { send(it, requestId, error(requestId, "INVALID_LICENSE_REQUEST")) }
            }
        }

        private fun handleMessageSafely(message: Message) {
            if (message.what == MSG_ENTER_KIOSK || message.what == MSG_EXIT_KIOSK) {
                val packageName = message.data.getString(KEY_PACKAGE_NAME).orEmpty()
                if (callerOwnsPackage(message.sendingUid, packageName) && isRegisteredPackage(packageName)) {
                    applyKioskMode(message.what == MSG_ENTER_KIOSK)
                }
                return
            }
            if (message.what != MSG_REQUEST_LICENSE) return super.handleMessage(message)
            val replyTo = message.replyTo ?: return
            val data = message.data
            val requestId = data.getString(KEY_REQUEST_ID).orEmpty()
            val packageName = data.getString(KEY_PACKAGE_NAME).orEmpty()
            if (requestId.isBlank() || requestId.length > MAX_REQUEST_ID_LENGTH ||
                packageName.isBlank() || packageName.length > MAX_PACKAGE_NAME_LENGTH ||
                !callerOwnsPackage(message.sendingUid, packageName)
            ) {
                send(replyTo, requestId, error(requestId, "CALLER_PACKAGE_MISMATCH"))
                return
            }

            val installedSigner = packageSignerSha256(packageName)
            if (installedSigner == null || !installedSigner.equals(data.getString(KEY_APK_SIGNER_SHA256), ignoreCase = true)) {
                send(replyTo, requestId, error(requestId, "CALLER_SIGNER_MISMATCH"))
                return
            }

            val payload = JSONObject()
                .put("requestId", requestId)
                .put("applicationCode", data.getString(KEY_APPLICATION_CODE).orEmpty())
                .put("packageName", packageName)
                .put("appVersion", data.getString(KEY_APP_VERSION).orEmpty())
                .put("versionCode", data.getLong(KEY_VERSION_CODE))
                .put("publicKeySpkiBase64", data.getString(KEY_PUBLIC_KEY).orEmpty())
                .put("apkSignerSha256", installedSigner)

            val registered = ApplicationLicenseBroker.register(requestId) { response ->
                if (runCatching { JSONObject(response).optBoolean("success") }.getOrDefault(false)) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(packageName, true).apply()
                }
                send(replyTo, requestId, response)
            }
            if (!registered) {
                send(replyTo, requestId, error(requestId, "DUPLICATE_LICENSE_REQUEST"))
                return
            }
            if (!TmsMqttManager.publishApplicationLicenseRequest(payload) { success, _ ->
                    if (!success) ApplicationLicenseBroker.fail(requestId, "TMS_NOT_CONNECTED")
                }) {
                ApplicationLicenseBroker.fail(requestId, "TMS_NOT_CONNECTED")
            }
        }
    }

    private fun callerOwnsPackage(uid: Int, packageName: String): Boolean =
        packageManager.getPackagesForUid(uid)?.contains(packageName) == true

    private fun isRegisteredPackage(packageName: String): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(packageName, false)

    private fun applyKioskMode(locked: Boolean) {
        KioskModeController.setLocked(this, locked)
    }

    private fun packageSignerSha256(packageName: String): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    }.onFailure { Log.w(TAG, "Unable to inspect caller package signer", it) }.getOrNull()

    private fun send(replyTo: Messenger, requestId: String, responseJson: String) {
        runCatching {
            replyTo.send(Message.obtain(null, MSG_LICENSE_RESPONSE).apply {
                data = Bundle().apply {
                    putString(KEY_REQUEST_ID, requestId)
                    putString(KEY_RESPONSE_JSON, responseJson)
                }
            })
        }
    }

    private fun error(requestId: String, code: String) = JSONObject()
        .put("requestId", requestId).put("success", false).put("errorCode", code).toString()

    companion object {
        const val ACTION = "one.globalconnect.xtmsagent.APPLICATION_LICENSE_SERVICE"
        const val MSG_REQUEST_LICENSE = 1
        const val MSG_LICENSE_RESPONSE = 2
        const val MSG_ENTER_KIOSK = 3
        const val MSG_EXIT_KIOSK = 4
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_APPLICATION_CODE = "applicationCode"
        const val KEY_PACKAGE_NAME = "packageName"
        const val KEY_APP_VERSION = "appVersion"
        const val KEY_VERSION_CODE = "versionCode"
        const val KEY_PUBLIC_KEY = "publicKeySpkiBase64"
        const val KEY_APK_SIGNER_SHA256 = "apkSignerSha256"
        const val KEY_RESPONSE_JSON = "responseJson"
        private const val PREFS = "registered_application_licenses"
        private const val TAG = "AppLicensingService"
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val MAX_PACKAGE_NAME_LENGTH = 255
    }
}
