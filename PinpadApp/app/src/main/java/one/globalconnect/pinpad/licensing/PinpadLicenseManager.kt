package one.globalconnect.pinpad.licensing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Base64
import one.globalconnect.pinpad.BuildConfig
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object PinpadLicenseManager {
    private val kioskRequestGeneration = AtomicLong(0L)
    private const val APP_CODE = "PINPAD_APP"
    private const val LEGACY_KEY_ALIAS = "global-connect-license-PINPAD_APP"
    private const val PREFS = "pinpad_application_license"
    private const val CERTIFICATE = "certificate"
    private const val PUBLIC_KEY = "publicKeySpkiBase64"
    private const val KEY_ALIAS = "keyAlias"
    private const val ACTION = "one.globalconnect.xtmsagent.APPLICATION_LICENSE_SERVICE"
    private const val MSG_REQUEST = 1
    private const val MSG_RESPONSE = 2
    private const val MSG_ENTER_KIOSK = 3
    private const val MSG_EXIT_KIOSK = 4
    private const val MSG_REQUEST_MANAGED_IDENTITY = 7
    private const val MSG_MANAGED_IDENTITY_RESPONSE = 8
    private const val MANAGED_IDENTITY_TIMEOUT_MS = 5_000L

    fun isAuthorized(context: Context, serialNumber: String): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val certificate = preferences.getString(CERTIFICATE, null) ?: return false
        val publicKey = preferences.getString(PUBLIC_KEY, null) ?: return false
        return validate(context, serialNumber, certificate, publicKey)
    }

    fun requestLicense(context: Context, serialNumber: String, callback: (Boolean, String?) -> Unit) {
        val appContext = context.applicationContext
        val requestId = UUID.randomUUID().toString()
        val serviceIntent = resolveAgentService(appContext)
        if (serviceIntent == null) {
            callback(false, "AGENT_LICENSING_SERVICE_NOT_FOUND")
            return
        }

        val signerSha256 = runCatching { packageSignerSha256(appContext) }.getOrElse {
            callback(false, "APPLICATION_SIGNER_UNAVAILABLE")
            return
        }
        var agent: Messenger? = null
        var completed = false
        var licenseRequested = false
        var pendingLicensePublicKey = ""
        var pendingLicenseAlias = ""
        lateinit var connection: ServiceConnection
        lateinit var requestLegacyLicense: () -> Unit
        lateinit var responseMessenger: Messenger
        val mainHandler = Handler(Looper.getMainLooper())

        fun finish(success: Boolean, errorCode: String?) {
            if (completed) return
            completed = true
            mainHandler.removeCallbacksAndMessages(requestId)
            runCatching { appContext.unbindService(connection) }
            callback(success, errorCode)
        }

        fun cacheLicense(certificate: String, publicKey: String, alias: String) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CERTIFICATE, certificate)
                .putString(PUBLIC_KEY, publicKey)
                .putString(KEY_ALIAS, alias)
                .apply()
        }

        fun sendLicenseRequest(publicKey: String, alias: String) {
            if (completed || licenseRequested) return
            val service = agent ?: run {
                finish(false, "AGENT_LICENSING_SERVICE_UNAVAILABLE")
                return
            }
            licenseRequested = true
            pendingLicensePublicKey = publicKey
            pendingLicenseAlias = alias
            val message = Message.obtain(null, MSG_REQUEST).apply {
                replyTo = responseMessenger
                data = Bundle().apply {
                    putString("requestId", requestId)
                    putString("applicationCode", APP_CODE)
                    putString("packageName", appContext.packageName)
                    putString("appVersion", BuildConfig.VERSION_NAME)
                    putLong("versionCode", BuildConfig.VERSION_CODE.toLong())
                    putString("publicKeySpkiBase64", publicKey)
                    putString("apkSignerSha256", signerSha256)
                    putString("keyAlias", alias)
                }
            }
            runCatching { service.send(message) }
                .onFailure { finish(false, "AGENT_LICENSING_SERVICE_UNAVAILABLE") }
        }

        responseMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                val response = message.data.getString("responseJson").orEmpty()
                val result = runCatching { JSONObject(response) }.getOrNull()
                if (result?.optString("requestId") != requestId) return

                if (message.what == MSG_MANAGED_IDENTITY_RESPONSE) {
                    if (result.optBoolean("success") != true) {
                        requestLegacyLicense()
                        return
                    }
                    val publicKey = result.optString(PUBLIC_KEY)
                    val alias = result.optString(KEY_ALIAS)
                    if (publicKey.isBlank() || alias.isBlank()) {
                        requestLegacyLicense()
                        return
                    }
                    val storedCertificate = result.optJSONObject(CERTIFICATE)?.toString()
                    if (storedCertificate != null &&
                        validate(appContext, serialNumber, storedCertificate, publicKey)
                    ) {
                        cacheLicense(storedCertificate, publicKey, alias)
                        finish(true, null)
                    } else {
                        sendLicenseRequest(publicKey, alias)
                    }
                    return
                }

                if (message.what != MSG_RESPONSE) return
                if (result.optBoolean("success") != true) {
                    finish(false, result.optString("errorCode").ifBlank { "LICENSE_REQUEST_FAILED" })
                    return
                }
                val certificate = result.optJSONObject(CERTIFICATE)?.toString()
                val pendingPublicKey = pendingLicensePublicKey
                val pendingAlias = pendingLicenseAlias
                if (certificate.isNullOrBlank() || pendingPublicKey.isBlank() ||
                    !validate(appContext, serialNumber, certificate, pendingPublicKey)
                ) {
                    finish(false, "LICENSE_CERTIFICATE_INVALID")
                    return
                }
                cacheLicense(certificate, pendingPublicKey, pendingAlias)
                finish(true, null)
            }
        })

        requestLegacyLicense = {
            if (!completed && !licenseRequested) {
                val keyPair = getOrCreateLegacyKeyPair()
                sendLicenseRequest(
                    Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP),
                    "",
                )
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                agent = binder?.let(::Messenger)
                val service = agent ?: run {
                    finish(false, "AGENT_LICENSING_SERVICE_UNAVAILABLE")
                    return
                }
                val message = Message.obtain(null, MSG_REQUEST_MANAGED_IDENTITY).apply {
                    replyTo = responseMessenger
                    data = Bundle().apply {
                        putString("requestId", requestId)
                        putString("applicationCode", APP_CODE)
                        putString("packageName", appContext.packageName)
                        putString("apkSignerSha256", signerSha256)
                    }
                }
                runCatching { service.send(message) }
                    .onFailure { requestLegacyLicense() }
                mainHandler.postAtTime(
                    requestLegacyLicense,
                    requestId,
                    android.os.SystemClock.uptimeMillis() + MANAGED_IDENTITY_TIMEOUT_MS,
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                agent = null
                if (!completed) finish(false, "AGENT_LICENSING_SERVICE_UNAVAILABLE")
            }
        }

        if (!appContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            finish(false, "AGENT_LICENSING_SERVICE_UNAVAILABLE")
        }
    }

    fun setKioskMode(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val generation = kioskRequestGeneration.incrementAndGet()
        val serviceIntent = resolveAgentService(appContext) ?: return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (kioskRequestGeneration.get() != generation) {
                    runCatching { appContext.unbindService(this) }
                    return
                }
                runCatching {
                    Messenger(binder).send(Message.obtain(null, if (enabled) MSG_ENTER_KIOSK else MSG_EXIT_KIOSK).apply {
                        data = Bundle().apply { putString("packageName", appContext.packageName) }
                    })
                }
                runCatching { appContext.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        appContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun validate(
        context: Context,
        serialNumber: String,
        certificateJson: String,
        applicationPublicKeySpkiBase64: String,
    ): Boolean = runCatching {
        val envelope = JSONObject(certificateJson)
        if (envelope.optString("algorithm") != "ECDSA_SHA_256") return@runCatching false
        val trustedKey = BuildConfig.LICENSE_SIGNING_PUBLIC_KEY_SPKI_BASE64
        if (trustedKey.isBlank()) return@runCatching false
        val payloadBytes = Base64.decode(envelope.getString("payloadBase64"), Base64.DEFAULT)
        val signatureBytes = Base64.decode(envelope.getString("signatureBase64"), Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(trustedKey, Base64.DEFAULT)),
        )
        val signatureValid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payloadBytes)
            verify(signatureBytes)
        }
        if (!signatureValid) return@runCatching false

        val payload = JSONObject(String(payloadBytes, Charsets.UTF_8))
        payload.getString("applicationCode") == APP_CODE &&
            payload.getString("packageName") == context.packageName &&
            payload.getString("deviceSerial").trim().uppercase() == serialNumber.trim().uppercase() &&
            payload.getString("licenseMode") == "permanent" &&
            payload.getString("apkSignerSha256").equals(packageSignerSha256(context), ignoreCase = true) &&
            payload.getString("applicationPublicKeySha256").equals(
                sha256(Base64.decode(applicationPublicKeySpkiBase64, Base64.DEFAULT)),
                ignoreCase = true,
            )
    }.getOrDefault(false)

    private fun getOrCreateLegacyKeyPair(): java.security.KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingPrivate = keyStore.getKey(LEGACY_KEY_ALIAS, null) as? java.security.PrivateKey
        val existingPublic = keyStore.getCertificate(LEGACY_KEY_ALIAS)?.publicKey
        if (existingPrivate != null && existingPublic != null) return java.security.KeyPair(existingPublic, existingPrivate)

        return java.security.KeyPairGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore",
        ).run {
            initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    LEGACY_KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
    }

    private fun resolveAgentService(context: Context): Intent? {
        val implicit = Intent(ACTION)
        @Suppress("DEPRECATION")
        val service = context.packageManager.queryIntentServices(implicit, 0)
            .firstOrNull { it.serviceInfo.packageName.startsWith("one.globalconnect.xtmsagent") }
            ?.serviceInfo ?: return null
        return Intent(implicit).setComponent(ComponentName(service.packageName, service.name))
    }

    private fun packageSignerSha256(context: Context): String {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
        }
        val signer = signers?.firstOrNull()
            ?: throw IllegalStateException("Application signing certificate is unavailable")
        return sha256(signer.toByteArray())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
