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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.requirements.ApplicationRequirementManager
import org.json.JSONObject
import java.security.MessageDigest

class ApplicationLicensingService : Service() {
    private var messenger: Messenger? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var credentialStore: ManagedApplicationCredentialStore

    override fun onCreate() {
        super.onCreate()
        credentialStore = ManagedApplicationCredentialStore(this)
        messenger = Messenger(IncomingHandler(Looper.getMainLooper()))
        Log.i(TAG, "Application licensing service created")
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onDestroy() {
        serviceScope.cancel()
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
            if (message.what == MSG_REQUEST_MANAGED_IDENTITY) {
                handleManagedIdentity(message)
                return
            }
            if (message.what == MSG_REQUEST_APPLICATION_REQUIREMENT) {
                handleApplicationRequirement(message)
                return
            }
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
            val applicationCode = data.getString(KEY_APPLICATION_CODE).orEmpty()
            val publicKey = data.getString(KEY_PUBLIC_KEY).orEmpty()
            val keyAlias = data.getString(KEY_KEY_ALIAS).orEmpty()
            if (requestId.isBlank() || requestId.length > MAX_REQUEST_ID_LENGTH ||
                packageName.isBlank() || packageName.length > MAX_PACKAGE_NAME_LENGTH ||
                applicationCode.isBlank() || applicationCode.length > MAX_APPLICATION_CODE_LENGTH ||
                publicKey.isBlank() || publicKey.length > MAX_PUBLIC_KEY_LENGTH ||
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
                .put("applicationCode", applicationCode)
                .put("packageName", packageName)
                .put("appVersion", data.getString(KEY_APP_VERSION).orEmpty())
                .put("versionCode", data.getLong(KEY_VERSION_CODE))
                .put("publicKeySpkiBase64", publicKey)
                .put("apkSignerSha256", installedSigner)

            val registered = ApplicationLicenseBroker.register(requestId) { response ->
                val responseJson = runCatching { JSONObject(response) }.getOrNull()
                if (responseJson?.optBoolean("success") == true) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(packageName, true).apply()
                    val certificate = responseJson.optJSONObject(KEY_CERTIFICATE)?.toString()
                    if (keyAlias.isNotBlank() && certificate != null && !credentialStore.storeLicenseCertificate(
                            applicationCode = applicationCode,
                            packageName = packageName,
                            signerSha256 = installedSigner,
                            alias = keyAlias,
                            publicKeySpkiBase64 = publicKey,
                            certificateJson = certificate,
                        )
                    ) {
                        Log.w(TAG, "Managed license certificate was not persisted for $packageName")
                    }
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

        private fun handleManagedIdentity(message: Message) {
            val replyTo = message.replyTo ?: return
            val requestId = message.data.getString(KEY_REQUEST_ID).orEmpty()
            val packageName = message.data.getString(KEY_PACKAGE_NAME).orEmpty()
            val applicationCode = message.data.getString(KEY_APPLICATION_CODE).orEmpty()
            if (requestId.isBlank() || requestId.length > MAX_REQUEST_ID_LENGTH ||
                packageName.isBlank() || packageName.length > MAX_PACKAGE_NAME_LENGTH ||
                applicationCode.isBlank() || applicationCode.length > MAX_APPLICATION_CODE_LENGTH ||
                !callerOwnsPackage(message.sendingUid, packageName)
            ) {
                sendManagedIdentity(
                    replyTo,
                    requestId,
                    error(requestId, "MANAGED_IDENTITY_REQUEST_REJECTED"),
                )
                return
            }

            val installedSigner = packageSignerSha256(packageName)
            if (installedSigner == null || !installedSigner.equals(
                    message.data.getString(KEY_APK_SIGNER_SHA256),
                    ignoreCase = true,
                )
            ) {
                sendManagedIdentity(
                    replyTo,
                    requestId,
                    error(requestId, "CALLER_SIGNER_MISMATCH"),
                )
                return
            }

            serviceScope.launch {
                val response = credentialStore.prepareIdentity(
                    applicationCode = applicationCode,
                    packageName = packageName,
                    signerSha256 = installedSigner,
                ).fold(
                    onSuccess = { identity ->
                        JSONObject()
                            .put(KEY_REQUEST_ID, requestId)
                            .put("success", true)
                            .put(KEY_KEY_ALIAS, identity.alias)
                            .put(KEY_PUBLIC_KEY, identity.publicKeySpkiBase64)
                            .apply {
                                identity.certificateJson?.let {
                                    put(KEY_CERTIFICATE, JSONObject(it))
                                }
                            }
                            .toString()
                    },
                    onFailure = { failure ->
                        Log.w(TAG, "Managed application identity unavailable for $packageName", failure)
                        val code = failure.message?.takeIf { it in MANAGED_IDENTITY_ERROR_CODES }
                            ?: "MANAGED_IDENTITY_UNAVAILABLE"
                        error(requestId, code)
                    },
                )
                sendManagedIdentity(replyTo, requestId, response)
            }
        }

        private fun handleApplicationRequirement(message: Message) {
            val replyTo = message.replyTo ?: return
            val requestId = message.data.getString(KEY_REQUEST_ID).orEmpty()
            val packageName = message.data.getString(KEY_PACKAGE_NAME).orEmpty()
            val capability = message.data.getString(KEY_CAPABILITY).orEmpty()
            if (requestId.isBlank() || requestId.length > MAX_REQUEST_ID_LENGTH ||
                packageName.isBlank() || packageName.length > MAX_PACKAGE_NAME_LENGTH ||
                capability !in SUPPORTED_APPLICATION_REQUIREMENT_CAPABILITIES ||
                !callerOwnsPackage(message.sendingUid, packageName) ||
                !isRegisteredPackage(packageName)
            ) {
                sendRequirement(replyTo, requestId, error(requestId, "APPLICATION_REQUIREMENT_REJECTED"))
                return
            }

            serviceScope.launch {
                val response = runCatching {
                    ApplicationRequirementManager.request(
                        requestId,
                        capability,
                        packageName,
                    )
                }.getOrElse { exception ->
                    Log.e(TAG, "Application requirement request failed requestId=$requestId", exception)
                    error(requestId, "APPLICATION_REQUIREMENT_REQUEST_FAILED")
                }
                sendRequirement(replyTo, requestId, response)
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

    private fun sendRequirement(replyTo: Messenger, requestId: String, responseJson: String) {
        runCatching {
            replyTo.send(Message.obtain(null, MSG_APPLICATION_REQUIREMENT_RESPONSE).apply {
                data = Bundle().apply {
                    putString(KEY_REQUEST_ID, requestId)
                    putString(KEY_RESPONSE_JSON, responseJson)
                }
            })
        }
    }

    private fun sendManagedIdentity(replyTo: Messenger, requestId: String, responseJson: String) {
        runCatching {
            replyTo.send(Message.obtain(null, MSG_MANAGED_IDENTITY_RESPONSE).apply {
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
        const val MSG_REQUEST_APPLICATION_REQUIREMENT = 5
        const val MSG_APPLICATION_REQUIREMENT_RESPONSE = 6
        const val MSG_REQUEST_MANAGED_IDENTITY = 7
        const val MSG_MANAGED_IDENTITY_RESPONSE = 8
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_APPLICATION_CODE = "applicationCode"
        const val KEY_PACKAGE_NAME = "packageName"
        const val KEY_APP_VERSION = "appVersion"
        const val KEY_VERSION_CODE = "versionCode"
        const val KEY_PUBLIC_KEY = "publicKeySpkiBase64"
        const val KEY_APK_SIGNER_SHA256 = "apkSignerSha256"
        const val KEY_RESPONSE_JSON = "responseJson"
        const val KEY_CAPABILITY = "capability"
        const val KEY_KEY_ALIAS = "keyAlias"
        const val KEY_CERTIFICATE = "certificate"
        private val SUPPORTED_APPLICATION_REQUIREMENT_CAPABILITIES = setOf(
            "android.tts",
            "android.tts.language.es",
            "android.tts.voice.es.mateo",
        )
        private const val PREFS = "registered_application_licenses"
        private const val TAG = "AppLicensingService"
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val MAX_PACKAGE_NAME_LENGTH = 255
        private const val MAX_APPLICATION_CODE_LENGTH = 128
        private const val MAX_PUBLIC_KEY_LENGTH = 4096
        private val MANAGED_IDENTITY_ERROR_CODES = setOf(
            "MANAGED_IDENTITY_REQUIRES_ANDROID_11",
            "XTMSAGENT_IS_NOT_DEVICE_OWNER",
            "MANAGED_IDENTITY_KEY_GENERATION_FAILED",
            "MANAGED_IDENTITY_GRANT_FAILED",
        )
    }
}
