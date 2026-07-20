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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import one.globalconnect.pinpad.BuildConfig
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.util.UUID

object PinpadLicenseManager {
    private const val APP_CODE = "PINPAD_APP"
    private const val KEY_ALIAS = "global-connect-license-PINPAD_APP"
    private const val PREFS = "pinpad_application_license"
    private const val CERTIFICATE = "certificate"
    private const val ACTION = "one.globalconnect.xtmsagent.APPLICATION_LICENSE_SERVICE"
    private const val MSG_REQUEST = 1
    private const val MSG_RESPONSE = 2
    private const val MSG_ENTER_KIOSK = 3
    private const val MSG_EXIT_KIOSK = 4

    fun isAuthorized(context: Context, serialNumber: String): Boolean {
        val certificate = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CERTIFICATE, null)
            ?: return false
        return validate(context, serialNumber, certificate)
    }

    fun requestLicense(context: Context, serialNumber: String, callback: (Boolean, String?) -> Unit) {
        val appContext = context.applicationContext
        val keyPair = getOrCreateKeyPair()
        val requestId = UUID.randomUUID().toString()
        val serviceIntent = resolveAgentService(appContext)
        if (serviceIntent == null) {
            callback(false, "AGENT_LICENSING_SERVICE_NOT_FOUND")
            return
        }

        val responseMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != MSG_RESPONSE) return
                val response = message.data.getString("responseJson").orEmpty()
                val result = runCatching { JSONObject(response) }.getOrNull()
                if (result?.optBoolean("success") != true) {
                    callback(false, result?.optString("errorCode") ?: "LICENSE_REQUEST_FAILED")
                    return
                }
                val certificate = result.optJSONObject("certificate")?.toString()
                if (certificate.isNullOrBlank() || !validate(appContext, serialNumber, certificate)) {
                    callback(false, "LICENSE_CERTIFICATE_INVALID")
                    return
                }
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(CERTIFICATE, certificate).apply()
                callback(true, null)
            }
        })

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val message = Message.obtain(null, MSG_REQUEST).apply {
                    replyTo = responseMessenger
                    data = Bundle().apply {
                        putString("requestId", requestId)
                        putString("applicationCode", APP_CODE)
                        putString("packageName", appContext.packageName)
                        putString("appVersion", BuildConfig.VERSION_NAME)
                        putLong("versionCode", BuildConfig.VERSION_CODE.toLong())
                        putString("publicKeySpkiBase64", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
                        putString("apkSignerSha256", packageSignerSha256(appContext))
                    }
                }
                runCatching { Messenger(binder).send(message) }
                    .onFailure { callback(false, "AGENT_LICENSING_SERVICE_UNAVAILABLE") }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        if (!appContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            callback(false, "AGENT_LICENSING_SERVICE_UNAVAILABLE")
        }
    }

    fun setKioskMode(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val serviceIntent = resolveAgentService(appContext) ?: return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
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

    private fun validate(context: Context, serialNumber: String, certificateJson: String): Boolean = runCatching {
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
        val keyPair = getOrCreateKeyPair()
        payload.getString("applicationCode") == APP_CODE &&
            payload.getString("packageName") == context.packageName &&
            payload.getString("deviceSerial").trim().uppercase() == serialNumber.trim().uppercase() &&
            payload.getString("licenseMode") == "permanent" &&
            payload.getString("apkSignerSha256").equals(packageSignerSha256(context), ignoreCase = true) &&
            payload.getString("applicationPublicKeySha256").equals(sha256(keyPair.public.encoded), ignoreCase = true)
    }.getOrDefault(false)

    private fun getOrCreateKeyPair(): java.security.KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingPrivate = keyStore.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
        val existingPublic = keyStore.getCertificate(KEY_ALIAS)?.publicKey
        if (existingPrivate != null && existingPublic != null) return java.security.KeyPair(existingPublic, existingPrivate)

        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
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
