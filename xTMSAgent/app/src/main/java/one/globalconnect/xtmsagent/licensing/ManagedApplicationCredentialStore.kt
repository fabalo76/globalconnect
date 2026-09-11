package one.globalconnect.xtmsagent.licensing

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import one.globalconnect.xtmsagent.TmsDeviceAdminReceiver
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

internal data class ManagedApplicationIdentity(
    val alias: String,
    val publicKeySpkiBase64: String,
    val certificateJson: String?,
)

/**
 * Owns application identities independently from the lifecycle of a managed client APK.
 *
 * The private key is generated in Android's managed KeyChain by the device owner and is
 * granted to the currently installed client package. Only public identity metadata and the
 * signed application-license certificate are persisted in xTMSAgent private no-backup storage.
 */
internal class ManagedApplicationCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val devicePolicyManager =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val stateFile = AtomicFile(
        File(appContext.noBackupFilesDir, "managed_application_credentials.json"),
    )
    private val lock = Any()

    fun prepareIdentity(
        applicationCode: String,
        packageName: String,
        signerSha256: String,
    ): Result<ManagedApplicationIdentity> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(
                IllegalArgumentException("MANAGED_IDENTITY_REQUIRES_ANDROID_11"),
            )
        }
        return prepareIdentityApi30(applicationCode, packageName, signerSha256)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun prepareIdentityApi30(
        applicationCode: String,
        packageName: String,
        signerSha256: String,
    ): Result<ManagedApplicationIdentity> = runCatching {
        require(devicePolicyManager.isDeviceOwnerApp(appContext.packageName)) {
            "XTMSAGENT_IS_NOT_DEVICE_OWNER"
        }

        synchronized(lock) {
            val recordId = ManagedApplicationCredentialIds.recordId(applicationCode, packageName)
            val state = readState()
            val records = state.optJSONObject(KEY_RECORDS) ?: JSONObject().also {
                state.put(KEY_RECORDS, it)
            }
            val existing = records.optJSONObject(recordId)
            val alias = existing?.optString(KEY_ALIAS).orEmpty().ifBlank {
                ManagedApplicationCredentialIds.alias(applicationCode, packageName)
            }
            var publicKey = existing?.optString(KEY_PUBLIC_KEY).orEmpty()

            val grantedExisting = publicKey.isNotBlank() && runCatching {
                devicePolicyManager.grantKeyPairToApp(
                    TmsDeviceAdminReceiver.componentName(appContext),
                    alias,
                    packageName,
                )
            }.getOrDefault(false)

            if (!grantedExisting) {
                val attestedKeyPair = devicePolicyManager.generateKeyPair(
                    TmsDeviceAdminReceiver.componentName(appContext),
                    KeyProperties.KEY_ALGORITHM_EC,
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                    0,
                ) ?: error("MANAGED_IDENTITY_KEY_GENERATION_FAILED")
                val generatedKeyPair = attestedKeyPair.keyPair
                    ?: error("MANAGED_IDENTITY_KEY_GENERATION_FAILED")
                publicKey = Base64.encodeToString(generatedKeyPair.public.encoded, Base64.NO_WRAP)
                check(
                    devicePolicyManager.grantKeyPairToApp(
                        TmsDeviceAdminReceiver.componentName(appContext),
                        alias,
                        packageName,
                    ),
                ) { "MANAGED_IDENTITY_GRANT_FAILED" }
                Log.i(TAG, "Generated managed identity alias=$alias package=$packageName")
            } else {
                Log.i(TAG, "Restored managed identity alias=$alias package=$packageName")
            }

            val signerChanged = existing != null &&
                !existing.optString(KEY_SIGNER).equals(signerSha256, ignoreCase = true)
            val certificate = if (signerChanged) null else {
                existing?.optJSONObject(KEY_CERTIFICATE)?.toString()
            }
            records.put(
                recordId,
                JSONObject()
                    .put(KEY_APPLICATION_CODE, applicationCode)
                    .put(KEY_PACKAGE_NAME, packageName)
                    .put(KEY_SIGNER, signerSha256.lowercase())
                    .put(KEY_ALIAS, alias)
                    .put(KEY_PUBLIC_KEY, publicKey)
                    .apply {
                        certificate?.let { put(KEY_CERTIFICATE, JSONObject(it)) }
                    },
            )
            writeState(state)
            ManagedApplicationIdentity(alias, publicKey, certificate)
        }
    }

    fun storeLicenseCertificate(
        applicationCode: String,
        packageName: String,
        signerSha256: String,
        alias: String,
        publicKeySpkiBase64: String,
        certificateJson: String,
    ): Boolean = synchronized(lock) {
        val recordId = ManagedApplicationCredentialIds.recordId(applicationCode, packageName)
        val state = readState()
        val record = state.optJSONObject(KEY_RECORDS)?.optJSONObject(recordId) ?: return false
        if (!record.optString(KEY_SIGNER).equals(signerSha256, ignoreCase = true) ||
            record.optString(KEY_ALIAS) != alias ||
            record.optString(KEY_PUBLIC_KEY) != publicKeySpkiBase64
        ) {
            return false
        }
        val certificate = runCatching { JSONObject(certificateJson) }.getOrNull() ?: return false
        record.put(KEY_CERTIFICATE, certificate)
        writeState(state)
        Log.i(TAG, "Persisted managed license certificate alias=$alias package=$packageName")
        true
    }

    private fun readState(): JSONObject = runCatching {
        JSONObject(String(stateFile.readFully(), Charsets.UTF_8))
    }.getOrElse { JSONObject().put(KEY_RECORDS, JSONObject()) }

    private fun writeState(state: JSONObject) {
        val stream = stateFile.startWrite()
        try {
            stream.write(state.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
            stateFile.finishWrite(stream)
        } catch (error: Exception) {
            stateFile.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val KEY_RECORDS = "records"
        const val KEY_APPLICATION_CODE = "applicationCode"
        const val KEY_PACKAGE_NAME = "packageName"
        const val KEY_SIGNER = "apkSignerSha256"
        const val KEY_ALIAS = "keyAlias"
        const val KEY_PUBLIC_KEY = "publicKeySpkiBase64"
        const val KEY_CERTIFICATE = "certificate"
        const val TAG = "ManagedAppCredentials"
    }
}

internal object ManagedApplicationCredentialIds {
    fun recordId(applicationCode: String, packageName: String): String =
        sha256("${applicationCode.trim().uppercase()}|${packageName.trim().lowercase()}")

    fun alias(applicationCode: String, packageName: String): String =
        "gc-app-${recordId(applicationCode, packageName).take(32)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
